package app.revanced.runner

import app.revanced.patcher.patcher
import app.revanced.patches.uniqueone.subscription.SubscriptionBypassPatch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.logging.Logger

/**
 * Standalone APK patcher.
 *
 * Takes an input APK, applies all patch objects on the classpath, and produces
 * a signed output APK. Designed to run in CI without external ReVanced CLI.
 *
 * Usage:
 *   java -jar patcher-runner-all.jar input.apk output.apk
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Usage: patcher-runner input.apk output.apk")
        System.exit(1)
    }

    val inputApk = File(args[0])
    val outputApk = File(args[1])
    val tempDir = File(outputApk.parentFile, "${outputApk.nameWithoutExtension}-temp")
    val cacheDir = File(tempDir, "cache")

    require(inputApk.exists()) { "Input APK not found: ${inputApk.absolutePath}" }
    tempDir.mkdirs()
    cacheDir.mkdirs()

    val logger = Logger.getLogger("PatcherRunner")

    logger.info("Input:  ${inputApk.absolutePath}")
    logger.info("Output: ${outputApk.absolutePath}")

    // ---- Create the patcher and register our patch ----
    val patch = patcher(
        inputApk,
        tempDir,
        aaptBinaryPath = null,
        resourceCachePath = cacheDir.absolutePath,
    ) { packageName, versionName ->
        logger.info("Target: $packageName v$versionName")
        // Return the patches to apply.
        setOf(SubscriptionBypassPatch)
    }

    // ---- Execute patches ----
    val patcherResult = patch { patchResult ->
        val exception = patchResult.exception
        if (exception != null) {
            StringWriter().use { writer ->
                exception.printStackTrace(PrintWriter(writer))
                logger.severe("\"${patchResult.patch}\" FAILED:\n$writer")
            }
            throw RuntimeException("Patch failed", exception)
        }
        logger.info("\"${patchResult.patch}\" OK")
    }

    // ---- Apply patch results to the APK copy ----
    val patchedApk = File(tempDir, inputApk.name)
    inputApk.copyTo(patchedApk, overwrite = true)

    patcherResult.applyTo(patchedApk)

    // ---- Sign the patched APK ----
    // Generate a throwaway debug keystore if needed.
    val keystoreFile = File(tempDir, "debug.keystore")
    if (!keystoreFile.exists()) {
        createDebugKeystore(keystoreFile)
    }

    app.revanced.library.ApkUtils.signApk(
        patchedApk,
        outputApk,
        signer = "ReVanced",
        app.revanced.library.ApkUtils.KeyStoreDetails(
            keystoreFile,
            storePassword = "android",
            keyAlias = "androiddebugkey",
            keyPassword = "android",
        )
    )

    logger.info("Done: ${outputApk.absolutePath}")

    // Clean up temporary files.
    tempDir.deleteRecursively()
}

/**
 * Creates a standard Android debug keystore using the JDK keytool command.
 * (keytool is included in every JDK.)
 */
private fun createDebugKeystore(keystoreFile: File) {
    val process = ProcessBuilder(
        "keytool",
        "-genkeypair",
        "-keystore", keystoreFile.absolutePath,
        "-storepass", "android",
        "-alias", "androiddebugkey",
        "-keypass", "android",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10000",
        "-dname", "CN=ReVanced Runner, OU=CI, O=ReVanced, L=Unknown, ST=Unknown, C=US",
        "-noprompt"
    )
        .inheritIO()
        .start()

    val exitCode = process.waitFor()
    require(exitCode == 0) { "keytool failed with exit code $exitCode" }
}
