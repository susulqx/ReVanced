plugins {
    alias(libs.plugins.kotlin.jvm)
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "app.revanced.runner"

application {
    mainClass.set("app.revanced.runner.PatcherRunnerKt")
}

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
    maven {
        url = uri("https://repo.sleeping.town")
        content { includeGroup("com.unascribed") }
    }
}

dependencies {
    implementation(project(":patches"))
    implementation(libs.revanced.patcher)
}

kotlin {
    jvmToolchain(17)
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("all")
    mergeServiceFiles()
    // Exclude signing-related files that might conflict.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
