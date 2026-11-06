plugins {
    id("com.chaquo.python") version "17.2.0" apply false
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
