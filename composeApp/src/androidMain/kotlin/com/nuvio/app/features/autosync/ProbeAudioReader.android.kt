package com.nuvio.app.features.autosync

/** Audio probing is iOS-only for now; Android keeps the existing subtitle behavior. */
internal actual suspend fun readProbeAudioEnvelope(
    sourceUrl: String,
    headers: Map<String, String>,
    windowStartMs: Long,
    windowMs: Long,
): ProbeAudioEnvelope? = null
