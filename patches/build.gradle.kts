plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "app.revanced.patches.uniqueone"

repositories {
    mavenCentral()
    mavenLocal()
    google()
    maven {
        url = uri("https://maven.pkg.github.com/revanced/revanced-patcher")
        credentials {
            username = project.findProperty("gpr.user") as? String ?: System.getenv("GITHUB_ACTOR") ?: ""
            password = project.findProperty("gpr.key") as? String ?: System.getenv("GITHUB_TOKEN") ?: ""
        }
    }
    // Required for FlexVer-Java (transitive dependency of ReVanced Patcher).
    maven {
        url = uri("https://repo.sleeping.town")
        content { includeGroup("com.unascribed") }
    }
}

dependencies {
    implementation(libs.revanced.patcher)
    // Required for direct dexlib2 API access (AccessFlags, etc.)
    implementation(libs.smali.dexlib2)
}

kotlin {
    jvmToolchain(17)
}

tasks {
    register("generateBundle") {
        description = "Generate dex files from build and bundle them in the jar file"
        dependsOn(build)
        doLast {
            val androidHome = System.getenv("ANDROID_HOME")
                ?: throw GradleException("ANDROID_HOME not found")
            val d8 = "$androidHome/build-tools/34.0.0/d8"
            val input = configurations.archives.get().allArtifacts.files.files.first().absolutePath
            val work = File("${buildDir}/libs")
            exec { workingDir = work; commandLine = listOf(d8, input) }
            exec { workingDir = work; commandLine = listOf("zip", "-u", input, "classes.dex") }
        }
    }
}
