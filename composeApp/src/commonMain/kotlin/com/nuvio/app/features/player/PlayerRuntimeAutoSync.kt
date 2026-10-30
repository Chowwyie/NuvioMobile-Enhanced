package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.nuvio.app.features.autosync.AutoSyncPlayerController
import com.nuvio.app.features.autosync.AutoSyncPreferencesRepository
import com.nuvio.app.features.autosync.AutoSyncSubtitleCandidate
import com.nuvio.app.features.autosync.ProbeAutoSyncAligner
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.autosync.readProbeAudioEnvelope
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Probe autosync status surfaced in the subtitle picker. Idle = nothing shown. */
sealed interface ProbeAutoSyncStatus {
    data object Idle : ProbeAutoSyncStatus
    data object Running : ProbeAutoSyncStatus
    data class Applied(val delayMs: Int) : ProbeAutoSyncStatus
    data object Failed : ProbeAutoSyncStatus
}

private fun List<AddonSubtitle>.toAutoSyncCandidates(): List<AutoSyncSubtitleCandidate> =
    map { subtitle ->
        AutoSyncSubtitleCandidate(
            url = subtitle.url,
            language = subtitle.language,
            name = subtitle.display,
        )
    }

private fun PlayerScreenRuntime.currentAutoSyncCandidates(): List<AutoSyncSubtitleCandidate> =
    addonSubtitles.toAutoSyncCandidates()

internal fun PlayerScreenRuntime.configureAutoSyncController(
    controller: PlayerEngineController,
) {
    controller.setAutoSyncSubtitleCandidates(currentAutoSyncCandidates())
    controller.setAutoSyncAppliedListener { subtitleUrl, delayMs ->
        val appliedSubtitle = addonSubtitles.firstOrNull { it.url == subtitleUrl }
        selectedAddonSubtitleId = appliedSubtitle?.selectionKey ?: subtitleUrl
        selectedSubtitleIndex = -1
        useCustomSubtitles = true
        preferredSubtitleSelectionApplied = true
        if (appliedSubtitle != null && isUserExplicitSubtitleSelection) {
            persistAddonSubtitlePreference(appliedSubtitle)
        }

        val appliedDelayMs = delayMs
        subtitleDelayMs = appliedDelayMs
        PlayerTrackPreferenceStorage.saveSubtitleDelayMs(
            playbackSession.videoId,
            appliedDelayMs,
        )
    }
}

@Composable
internal fun PlayerScreenRuntime.BindAutoSyncRuntimeEffects() {
    val activeController = playerController
    DisposableEffect(activeController) {
        // Runs once playerController is set, which Nuvio only does for the live playback key.
        activeController?.let { configureAutoSyncController(it) }
        onDispose { activeController?.setAutoSyncAppliedListener(null) }
    }
    LaunchedEffect(playerController, playbackSnapshot.isLoading, isLoadingAddonSubtitles, addonSubtitles) {
        // Add-ons can finish after Nuvio has completed its first track scan with no subtitle.
        // Retry that automatic pick only for AutoSync, while the user still has no selection.
        if (playerController !is AutoSyncPlayerController || playbackSnapshot.isLoading) return@LaunchedEffect
        if (!AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value) return@LaunchedEffect
        if (isLoadingAddonSubtitles || addonSubtitles.isEmpty()) return@LaunchedEffect
        if (!preferredSubtitleSelectionApplied || isUserExplicitSubtitleSelection) return@LaunchedEffect
        if (selectedSubtitleIndex >= 0 || selectedAddonSubtitleId != null) return@LaunchedEffect
        preferredSubtitleSelectionApplied = false
        refreshTracks()
    }
    LaunchedEffect(playerController, externalSubtitles) {
        val controller = playerController ?: return@LaunchedEffect
        SubtitleRepository.addonSubtitles.collect { repositorySubtitles ->
            controller.setAutoSyncSubtitleCandidates(
                mergeStreamAndAddonSubtitles(
                    repositorySubtitles,
                    externalSubtitles,
                ).toAutoSyncCandidates(),
            )
        }
    }
    LaunchedEffect(playerController, selectedAddonSubtitleId, addonSubtitles) {
        runProbeAutoSyncIfNeeded()
    }
}

/**
 * Audio-probe autosync. Runs once per playback for any selected addon
 * subtitle, in whatever language it is and from whatever addon it came from:
 * the selection itself is the intent, so there is no language or provider
 * condition. Skipped when an explicit manual delay is stored for this video
 * (a reset delay of 0 re-enables probing). One short audio window is
 * measured against the subtitle cues and the resulting constant offset is
 * applied as a subtitle delay.
 */
private suspend fun PlayerScreenRuntime.runProbeAutoSyncIfNeeded(force: Boolean = false) {
    val videoId = playbackSession.videoId
    probeAutoSyncStatus = ProbeAutoSyncStatus.Idle
    if (force) {
        InAppLogger.info("Player/ProbeAutoSync", "probe manual retry videoId=${videoId}")
    }
    val subtitle = selectedAddonSubtitle
    if (subtitle == null) {
        InAppLogger.debug("Player/ProbeAutoSync", "probe skipped: no addon subtitle videoId=${videoId}")
        return
    }
    if (activeSourceUrl.startsWith("file:")) {
        InAppLogger.debug("Player/ProbeAutoSync", "probe skipped: file source videoId=${videoId}")
        return
    }
    val attemptKey = videoId + "|" + subtitle.url
    if (!force && probeAutoSyncAttemptedKey == attemptKey) {
        InAppLogger.debug("Player/ProbeAutoSync", "probe skipped: already attempted videoId=${videoId}")
        return
    }
    probeAutoSyncAttemptedKey = attemptKey
    val storedDelay = PlayerTrackPreferenceStorage.loadSubtitleDelayMs(videoId)
    if (!force && storedDelay?.let { it != 0 } == true) {
        InAppLogger.debug("Player/ProbeAutoSync", "probe skipped: stored delay ms=${storedDelay} videoId=${videoId}")
        return
    }
    probeAutoSyncStatus = ProbeAutoSyncStatus.Running
    InAppLogger.info("Player/ProbeAutoSync", "probe started videoId=${videoId}")
    val body = runCatching {
        httpGetTextWithHeaders(
            url = subtitle.url,
            headers = sanitizePlaybackHeaders(activeSourceHeaders),
        )
    }.getOrNull()
    if (body == null) {
        InAppLogger.warn("Player/ProbeAutoSync", "probe failed: subtitle fetch videoId=${videoId}")
        probeAutoSyncStatus = ProbeAutoSyncStatus.Failed
        return
    }
    val cues = runCatching { PlayerSubtitleCueParser.parse(body, subtitle.url) }.getOrNull()
    if (cues == null) {
        InAppLogger.warn("Player/ProbeAutoSync", "probe failed: subtitle parse videoId=${videoId}")
        probeAutoSyncStatus = ProbeAutoSyncStatus.Failed
        return
    }
    val envelope = runCatching {
        readProbeAudioEnvelope(
            sourceUrl = activeSourceUrl,
            headers = activeSourceHeaders,
            windowStartMs = ProbeAutoSyncAligner.PROBE_WINDOW_START_MS,
            windowMs = ProbeAutoSyncAligner.PROBE_WINDOW_MS,
        )
    }.getOrNull()
    if (envelope == null) {
        InAppLogger.warn("Player/ProbeAutoSync", "probe failed: audio envelope videoId=${videoId}")
        probeAutoSyncStatus = ProbeAutoSyncStatus.Failed
        return
    }
    val binCount = (ProbeAutoSyncAligner.PROBE_WINDOW_MS / ProbeAutoSyncAligner.BIN_MS).toInt()
    val speech = ProbeAutoSyncAligner.speechEnvelope(envelope.rms.take(binCount))
    val speechPadded = if (speech.size < binCount) {
        speech + BooleanArray(binCount - speech.size)
    } else {
        speech
    }
    val cueEnv = ProbeAutoSyncAligner.cueEnvelope(
        cues = cues,
        windowStartMs = envelope.windowStartMs,
        binCount = binCount,
    )
    val delayMs = ProbeAutoSyncAligner.bestDelayMs(speechPadded, cueEnv)
    if (delayMs == null) {
        val speechBins = speechPadded.count { it }
        val cueBins = cueEnv.count { it }
        InAppLogger.warn("Player/ProbeAutoSync", "probe abstained: no alignment speechBins=${speechBins} cueBins=${cueBins} binCount=${binCount} videoId=${videoId}")
        probeAutoSyncStatus = ProbeAutoSyncStatus.Failed
        return
    }
    InAppLogger.info("Player/ProbeAutoSync", "probe applied delayMs=${delayMs} videoId=${videoId}")
    probeAutoSyncStatus = ProbeAutoSyncStatus.Applied(delayMs)
    setSubtitleDelay(delayMs)
}


/** The user picked an addon subtitle: let AutoSync check it without replacing their choice. */
internal fun PlayerScreenRuntime.attachSelectedAddonSubtitleWithAutoSync(url: String) {
    subtitleAutoSyncState = SubtitleAutoSyncUiState()
    playerController?.setSubtitleUriWithSelectedAutoSync(url)
}

internal fun PlayerScreenRuntime.maybeAutoSyncRestoredSubtitleAtStart(url: String): Boolean {
    val controller = playerController ?: return false
    if (controller !is AutoSyncPlayerController) return false
    if (!AutoSyncPreferencesRepository.claimStartupRun(hashCode(), activePlaybackIdentity)) {
        return false
    }
    controller.setSubtitleUriWithAutoSync(url)
    return true
}

internal fun PlayerScreenRuntime.maybeAutoSyncPreferredSubtitleAtStart(
    subtitle: AddonSubtitle,
): Boolean {
    if (isUserExplicitSubtitleSelection) return false
    val preferredLanguage =
        normalizeLanguageCode(playerSettingsUiState.preferredSubtitleLanguage) ?: return false
    if (
        preferredLanguage.isBlank() ||
        preferredLanguage == SubtitleLanguageOption.NONE ||
        preferredLanguage == SubtitleLanguageOption.FORCED
    ) {
        return false
    }

    val controller = playerController ?: return false
    if (controller !is AutoSyncPlayerController) return false
    if (!AutoSyncPreferencesRepository.claimStartupRun(hashCode(), activePlaybackIdentity)) {
        return false
    }
    controller.setSubtitleUriWithAutoSync(subtitle.url)
    return true
}

/** Manual probe retry from the subtitle picker: bypasses the once-per-playback and stored-delay gates. */
internal fun PlayerScreenRuntime.requestProbeAutoSyncRetry() {
    scope.launch { runProbeAutoSyncIfNeeded(force = true) }
}
