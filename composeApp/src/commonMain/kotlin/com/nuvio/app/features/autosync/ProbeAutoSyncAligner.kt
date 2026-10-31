package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue

/**
 * Probe-based subtitle sync: measure the constant offset between an add-on subtitle
 * and the playing audio from one short window, then apply it as a subtitle delay.
 *
 * Pure math, no platform code. The speech envelope comes from the platform audio
 * reader; the cue envelope comes from the parsed subtitle. Both live on the same
 * bin grid anchored at the probe window start, so correlation is a plain overlap sum.
 *
 * Sign convention: a positive result means the subtitles currently show early and
 * must be delayed. Example: a cue at bin 100 with speech at bin 130 scores best at
 * lag +30 bins, i.e. delay the subtitles by +30 bins so the cue lands on the speech.
 */
object ProbeAutoSyncAligner {
    /** Skip logos and cold opens: probe a minute of audio starting three minutes in. */
    const val PROBE_WINDOW_START_MS = 180_000L
    const val PROBE_WINDOW_MS = 60_000L
    const val BIN_MS = 100L

    /** How far the search looks in either direction. */
    const val MAX_LAG_MS = 60_000L

    /**
     * Computability floors, not confidence gates: a window with no speech, or a
     * subtitle with almost no cues in the window, cannot produce a measurement at
     * all, so the probe skips. Any real peak found is applied as-is.
     */
    const val MIN_SPEECH_BINS_FRACTION = 0.15
    const val MIN_CUE_BINS = 30

    /** Speech-activity envelope: true where the bin holds speech-level energy. */
    fun speechEnvelope(rms: List<Double>): BooleanArray {
        if (rms.isEmpty()) return BooleanArray(0)
        val max = rms.max()
        if (max <= 0.0) return BooleanArray(rms.size)
        val threshold = max * 0.15
        return BooleanArray(rms.size) { rms[it] >= threshold }
    }

    /** Cue-activity envelope on the same bin grid, window-relative. */
    fun cueEnvelope(
        cues: List<SubtitleSyncCue>,
        windowStartMs: Long,
        binCount: Int,
    ): BooleanArray {
        val env = BooleanArray(binCount)
        for (cue in cues) {
            val startBin = ((cue.startTimeMs - windowStartMs) / BIN_MS).toInt()
                .coerceIn(0, binCount)
            val endBin = ((cue.endTimeMs - windowStartMs) / BIN_MS).toInt()
                .coerceIn(0, binCount)
            for (b in startBin until endBin) {
                env[b] = true
            }
        }
        return env
    }

    /**
     * Best constant delay in ms, or null when the window holds too little signal
     * to measure. Both envelopes must share the bin grid and length.
     */
    fun bestDelayMs(speech: BooleanArray, cues: BooleanArray): Int? {
        if (speech.size != cues.size || speech.isEmpty()) return null
        if (speech.count { it } < speech.size * MIN_SPEECH_BINS_FRACTION) return null
        if (cues.count { it } < MIN_CUE_BINS) return null
        val maxLagBins = (MAX_LAG_MS / BIN_MS).toInt()
        // Lags in increasing |lag| order so ties resolve toward the smaller correction.
        val lags = (0..maxLagBins).flatMap { lag ->
            if (lag == 0) listOf(0) else listOf(lag, -lag)
        }
        var bestLag = 0
        var bestScore = -1
        for (lag in lags) {
            var score = 0
            for (i in speech.indices) {
                val cueIndex = i - lag
                if (cueIndex in cues.indices && speech[i] && cues[cueIndex]) {
                    score++
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestScore <= 0) return null
        return (bestLag * BIN_MS).toInt()
    }

    // ---- Sparse-chunk probe ----
    // Same measurement, fewer bytes: instead of one continuous 60s window,
    // probe a few short chunks of speech spread over a wide span and
    // correlate them against the full cue track. Cues cost nothing (the
    // subtitle file is already fetched); only speech costs bytes, so the
    // transfer cost is the chunks' bytes while the lag range comes from
    // the span. Speech outside the chunks is unknown, never assumed silent.
    const val SPARSE_CHUNK_MS = 5_000L
    const val SPARSE_CHUNK_COUNT = 5
    const val SPARSE_MIN_SEPARATION_MS = 30_000L
    const val SPARSE_SEARCH_START_MS = 60_000L
    const val SPARSE_SEARCH_END_MS = 300_000L
    const val SPARSE_MIN_CUE_BINS_PER_CHUNK = 15

    /**
     * Chunk starts (ms) by cue density: 5s slices over the search span,
     * greedy top slices with a minimum separation, densest first. Returns
     * ascending starts, or fewer than [SPARSE_CHUNK_COUNT] when the cues
     * cannot support that many (caller falls back to the continuous window).
     */
    fun pickSparseChunks(cues: List<SubtitleSyncCue>): List<Long> {
        val spanBins = ((SPARSE_SEARCH_END_MS - SPARSE_SEARCH_START_MS) / BIN_MS).toInt()
        if (spanBins <= 0) return emptyList()
        val cueEnv = cueEnvelope(cues, SPARSE_SEARCH_START_MS, spanBins)
        val chunkBins = (SPARSE_CHUNK_MS / BIN_MS).toInt()
        val sepBins = (SPARSE_MIN_SEPARATION_MS / BIN_MS).toInt()
        val scored = (0 until spanBins - chunkBins + 1 step chunkBins).map { start ->
            start to (start until start + chunkBins).count { cueEnv[it] }
        }.filter { it.second >= SPARSE_MIN_CUE_BINS_PER_CHUNK }
            .sortedByDescending { it.second }
        val picked = mutableListOf<Int>()
        for ((start, _) in scored) {
            if (picked.size >= SPARSE_CHUNK_COUNT) break
            if (picked.all { it - start >= sepBins || start - it >= sepBins }) {
                picked.add(start)
            }
        }
        return picked.sorted().map { SPARSE_SEARCH_START_MS + it * BIN_MS }
    }

    /**
     * Best constant delay from gapped speech: [known] marks bins where
     * speech was measured; unknown bins contribute nothing, neither matches
     * nor floor counts. Floors mirror [bestDelayMs] but over known bins.
     */
    fun bestDelayMsSparse(speech: BooleanArray, known: BooleanArray, cues: BooleanArray): Int? {
        if (speech.size != cues.size || speech.size != known.size || speech.isEmpty()) return null
        val knownCount = known.count { it }
        if (knownCount == 0) return null
        var speechBins = 0
        for (i in speech.indices) if (known[i] && speech[i]) speechBins++
        if (speechBins < knownCount * MIN_SPEECH_BINS_FRACTION) return null
        if (cues.count { it } < MIN_CUE_BINS) return null
        val maxLagBins = (MAX_LAG_MS / BIN_MS).toInt()
        val lags = (0..maxLagBins).flatMap { lag ->
            if (lag == 0) listOf(0) else listOf(lag, -lag)
        }
        var bestLag = 0
        var bestScore = -1
        for (lag in lags) {
            var score = 0
            for (i in speech.indices) {
                if (!known[i]) continue
                val cueIndex = i - lag
                if (cueIndex in cues.indices && speech[i] && cues[cueIndex]) {
                    score++
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestScore <= 0) return null
        return (bestLag * BIN_MS).toInt()
    }
}
