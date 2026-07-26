package app.revanced.patches.uniqueone.subscription.fingerprints

import app.revanced.patcher.fingerprint.MethodFingerprint

internal object GetSharedPreferencesFingerprint : MethodFingerprint(
    returnType = "Landroid/content/SharedPreferences;",
    parameters = emptyList(),
    strings = listOf("app_setting")
)
