package com.nuvio.app.features.autosync

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

/**
 * iOS persistence for the AutoSync preferences, mirroring AutoSyncPreferencesAndroid.
 * Installed once from the app entry point; the repository keeps working with
 * defaults until then, so a missed install can only leave the probe language at None.
 */
internal object AutoSyncPreferencesIos {
    private const val probeLanguageKey = "probe_auto_sync_language"

    fun initialize() {
        val defaults = NSUserDefaults.standardUserDefaults
        AutoSyncPreferencesRepository.installPersistence(
            loadProbeLanguage = {
                val key = ProfileScopedKey.of(probeLanguageKey)
                defaults.stringForKey(key)?.takeIf { it.isNotBlank() }
            },
            saveProbeLanguage = { language ->
                defaults.setObject(language, forKey = ProfileScopedKey.of(probeLanguageKey))
            },
        )
    }
}
