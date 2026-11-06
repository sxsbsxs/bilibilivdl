plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
// Compose-first UI shell; business logic remains in the existing Kotlin layers.
android {
 namespace="com.sxsbsxs.bilivdl"; compileSdk=36
 defaultConfig {
  applicationId="com.sxsbsxs.bilivdl"; minSdk=24; targetSdk=35; versionCode=39; versionName="2.0.19"
  ndk { abiFilters += listOf("arm64-v8a") }
 }
 buildFeatures { viewBinding=true; compose=true }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
 implementation(files("libs/ffmpeg-kit.aar"))
 implementation("com.arthenica:smart-exception-java:0.2.1")
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.appcompat:appcompat:1.7.0")
 implementation("androidx.fragment:fragment-ktx:1.8.5")
 implementation("androidx.recyclerview:recyclerview:1.3.2")
 implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
 implementation("com.google.android.material:material:1.12.0")
 implementation("androidx.constraintlayout:constraintlayout:2.2.0")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
 implementation("com.github.bumptech.glide:glide:4.16.0")
 implementation("androidx.media3:media3-exoplayer:1.11.0")
 implementation("androidx.media3:media3-exoplayer-dash:1.11.0")
 implementation("androidx.media3:media3-ui:1.11.0")
 implementation(platform("androidx.compose:compose-bom:2025.12.00"))
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.foundation:foundation")
 implementation("androidx.compose.material3:material3")
 implementation("com.chibatching.kotpref:kotpref:2.13.2")
 implementation("org.openani.mediamp:mediamp-api:0.3.2")
 implementation(project(":bilimiao-comm"))
 implementation(project(":bilimiao-compose"))
 implementation(project(":bvd-downloader"))
 implementation(project(":bilimiao-download"))
 implementation(project(":danmaku-engine"))
 implementation("com.squareup.okhttp3:okhttp:4.10.0")
 implementation("com.google.zxing:core:3.5.3")
}
