import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform") version "1.9.23"
    id("org.jetbrains.compose") version "1.6.2"
    kotlin("plugin.serialization") version "1.9.23"
}

kotlin {
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("composeApp/src/commonMain/kotlin")
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
                // Remove logging framework from commonMain - move to desktopMain only
            }
        }

        val desktopMain by getting {
            kotlin.srcDir("composeApp/src/desktopMain/kotlin")
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
                // Add Coil for desktop image loading
                implementation("io.coil-kt:coil-compose:2.4.0")
                implementation("io.coil-kt:coil:2.4.0")
                // Add metadata-extractor for EXIF orientation handling
                implementation("com.drewnoakes:metadata-extractor:2.19.0")
                // Add SLF4J backend for logging
                implementation("ch.qos.logback:logback-classic:1.4.11")
                // Add logging framework
                implementation("io.github.oshai:kotlin-logging-jvm:5.1.0")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "de.fotofilter.MainKt"

        // Add JVM args for development logging
        jvmArgs += listOf(
            "-Dlogback.configurationFile=composeApp/src/desktopMain/resources/logback-dev.xml"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "FotoFilter"
            packageVersion = "1.0.1"

            // Configure app icon
            macOS {
                iconFile.set(project.file("composeApp/src/desktopMain/resources/icons/app-icon.icns"))
            }
            windows {
                iconFile.set(project.file("composeApp/src/desktopMain/resources/icons/app-icon.ico"))
            }
            linux {
                iconFile.set(project.file("composeApp/src/desktopMain/resources/icons/app-icon.png"))
            }
        }
    }
}
