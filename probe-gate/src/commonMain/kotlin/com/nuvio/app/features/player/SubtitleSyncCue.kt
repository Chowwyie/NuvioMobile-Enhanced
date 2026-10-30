package com.nuvio.app.features.player

// Minimal redeclaration of the app's SubtitleSyncCue for the gate only.
// The app's copy lives in SubtitleAudioModels.kt alongside Compose UI state,
// which the gate must not drag in. The aligner reads only startTimeMs and
// endTimeMs, so this is behaviorally identical for probe purposes.
data class SubtitleSyncCue(
    val startTimeMs: Long,
    val endTimeMs: Long = startTimeMs + 5_000L,
    val text: String,
)
