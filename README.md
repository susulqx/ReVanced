# ReVanced Subscription Bypass Patches for `app.unique.one`

This repository contains a ReVanced patch port of the universal Xposed subscription bypass module.

## Project structure

```
ReVanced/
├── patches/                                       # BytecodePatch module
│   └── src/main/kotlin/app/revanced/patches/uniqueone/subscription/
│       ├── SubscriptionBypassPatch.kt
│       └── fingerprints/
│           └── GetSharedPreferencesFingerprint.kt
├── patcher-runner/                                # Self-contained APK patcher
│   └── src/main/kotlin/app/revanced/runner/
│       └── PatcherRunner.kt
├── extensions/shared/                             # Reserved; not required
├── .github/workflows/patch-apk.yml                # CI: build + patch + output APK
├── gradle/
│   └── libs.versions.toml
├── settings.gradle.kts
├── build.gradle.kts
└── patches/build.gradle.kts
```

## What it does

The patch rewrites the subscription verification logic in `app.unique.one` so the app always follows its own "valid subscription" code path, without requiring the companion verification app (`app.jjyy.passstore`).

## How it works

1. **Fingerprint** the subscription manager class by locating the static no-arg method that opens `SharedPreferences` named `"app_setting"`.
2. Inside that class, locate:
   - `(String, int) -> int` SP int reader
   - `() -> lf` subscription data factory
   - two `() -> boolean` methods distinguished by the strings `"oas"` and `"osv"`
3. Discover the subscription data class (`lf`) from the factory return type and verify its field signature (`3×int + 2×boolean + 2×long`, annotated with JSON keys `st/pu/ex/al/ca/v/te`).
4. Rewrite the methods:
   - SP int reader returns `1` for `"oas"`, `0` for `"osv"`, otherwise passes through.
   - Data factory returns a fully populated fake `lf` instance.
   - Version-check boolean returns `false`.
   - Feature-gate boolean returns `true`.

## Build

```bash
# Build patches JAR only
./gradlew :patches:build

# Build patcher-runner (fat JAR that can patch APKs directly)
./gradlew :patcher-runner:shadowJar
```

### Patch an APK locally

```bash
java -jar patcher-runner/build/libs/patcher-runner-all.jar input.apk output.apk
```

### GitHub Actions (one-click)

Trigger **"Build & Patch APK"** workflow from the Actions tab, provide the APK download URL, and get the patched APK as an artifact. See [BUILD.md](BUILD.md) for details.

> Note: ReVanced Patcher is hosted on GitHub Packages. Configure `gpr.user` / `gpr.key` in `gradle.properties` or set `GITHUB_ACTOR` / `GITHUB_TOKEN` environment variables.

## Usage

Use ReVanced CLI / Manager to patch `app.unique.one` with the generated patches bundle.

```bash
java -jar revanced-cli.jar patch \
  --patch-path patches/build/libs/patches.jar \
  --out app.unique.one.patched.apk \
  app.unique.one.apk
```

## Compatibility

The patch is intentionally version-agnostic. It relies only on stable functional strings and type signatures that survive obfuscation:

- `"app_setting"`
- `"oas"` / `"osv"`
- Field annotation values `st/pu/ex/al/ca/v/te`
- Field type combination `3×int + 2×boolean + 2×long`
- Method signatures `(String,int)->int`, `() -> lf`, `() -> boolean`
