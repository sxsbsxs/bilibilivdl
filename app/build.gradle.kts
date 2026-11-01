plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); alias(libs.plugins.ksp) }
android {
 namespace="com.sxsbsxs.bilivdl"; compileSdk=36
 defaultConfig { applicationId="com.sxsbsxs.bilivdl"; minSdk=24; targetSdk=35; versionCode=22; versionName="2.0.2" }
 buildFeatures { viewBinding=true }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.appcompat:appcompat:1.7.0")
 implementation("androidx.fragment:fragment-ktx:1.8.5")
 implementation("androidx.recyclerview:recyclerview:1.3.2")
 implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
 implementation("com.google.android.material:material:1.12.0")
 implementation("androidx.constraintlayout:constraintlayout:2.2.0")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
 implementation("com.github.bumptech.glide:glide:4.16.0")
 implementation("androidx.media3:media3-exoplayer:1.5.1")
 implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
 implementation("androidx.media3:media3-ui:1.5.1")
 implementation(project(":bilimiao-comm"))
 implementation(project(":bilimiao-download"))
 implementation(project(":danmaku-engine"))
 implementation("com.squareup.okhttp3:okhttp:4.10.0")
 implementation("com.google.zxing:core:3.5.3")
 // Keep Room aligned with the upstream Kotlin/KSP toolchain.
 implementation("androidx.room:room-runtime:2.7.2")
 implementation("androidx.room:room-ktx:2.7.2")
 ksp("androidx.room:room-compiler:2.7.2")
}
