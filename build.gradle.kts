plugins {
    id("org.jetbrains.kotlin.kapt") version "2.3.10" apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    alias(libs.plugins.jetbrains.kotlin.multiplatform) apply false
    alias(libs.plugins.jetbrains.kotlin.compose) apply false
    alias(libs.plugins.jetbrains.compose) apply false
    alias(libs.plugins.jetbrains.kotlin.serialization) apply false
    alias(libs.plugins.jetbrains.kotlin.parcelize) apply false
    alias(libs.plugins.google.protobuf) apply false
    alias(libs.plugins.ksp) apply false
}

/*
 * The upstream Compose Multiplatform UI module is newer than this app's
 * AGP/API baseline. Keep its AndroidX Compose artifacts aligned to the
 * stable Compose BOM 2025.12.00: core 1.10.0 and Material3 1.4.0.
 */
subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            when (requested.group) {
                "androidx.compose.runtime",
                "androidx.compose.foundation",
                "androidx.compose.animation",
                "androidx.compose.ui" -> useVersion("1.10.0")
                "androidx.compose.material3" -> useVersion("1.5.0-beta02")
                "androidx.compose.material" -> useVersion("1.11.4")
            }
        }
    }
}
