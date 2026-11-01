plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace="com.sxsbsxs.bilivdl"; compileSdk=35
 defaultConfig { applicationId="com.sxsbsxs.bilivdl"; minSdk=24; targetSdk=35; versionCode=5; versionName="0.5.0" }
 buildFeatures { viewBinding=true }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget="17" }
}

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
}