import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform") version "1.9.23"
    id("org.jetbrains.compose") version "1.6.2"
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
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "de.fotofilter.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "FotoFilter"
            packageVersion = "1.0.0"
        }
    }
}
