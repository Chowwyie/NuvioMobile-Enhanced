package com.nuvio.app.features.autosync

/** One window of audio energy: one RMS value per [binMs] bin, window-relative. */
data class ProbeAudioEnvelope(
    val windowStartMs: Long,
    val binMs: Long,
    val rms: List<Double>,
)

/**
 * Reads one short window of the playing stream's audio and returns its energy
 * envelope. The platform implementation downloads only what the window needs
 * (playlist segments for HLS, a byte range for a direct file) and deletes any
 * temporary files before returning. Returns null when the platform cannot probe
 * or the window holds no readable audio.
 */
internal expect suspend fun readProbeAudioEnvelope(
    sourceUrl: String,
    headers: Map<String, String>,
    windowStartMs: Long,
    windowMs: Long,
): ProbeAudioEnvelope?
