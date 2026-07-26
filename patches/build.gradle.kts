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
    maven {
        url = uri("https://repo.sleeping.town")
        content { includeGroup("com.unascribed") }
    }
}

dependencies {
    implementation(libs.revanced.patcher)
    implementation(libs.multidexlib2)
}

kotlin {
    jvmToolchain(17)
}
