package app.revanced.patches.uniqueone.subscription.fingerprints

import app.revanced.patcher.fingerprint.MethodFingerprint

/**
 * Locates the subscription manager class by its entry point:
 * the static no-arg method that opens SharedPreferences named "app_setting".
 *
 * "app_setting" is a functional string that survives obfuscation, making it a
 * stable anchor for the whole patch.
 */
internal object GetSharedPreferencesFingerprint : MethodFingerprint(
    returnType = "Landroid/content/SharedPreferences;",
    parameters = emptyList(),
    strings = listOf("app_setting")
)
