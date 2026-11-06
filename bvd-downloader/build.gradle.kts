plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.kotlin.compose)
    id("com.android.legacy-kapt")
    alias(libs.plugins.ksp)
}
android {
    namespace = "cc.kafuu.bilidownload"
    compileSdk = 36
    defaultConfig { minSdk = 24; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { dataBinding = true; viewBinding = true; compose = true; buildConfig = true }
    sourceSets {
        getByName("main") {
            java.srcDir("$rootDir/upstream/BiliDownload/app/src/main/java")
            res.srcDirs(
                "$rootDir/upstream/BiliDownload/app/src/main/res",
                "$rootDir/upstream/BiliDownload/app/src/main/res/views/activity",
                "$rootDir/upstream/BiliDownload/app/src/main/res/views/fragment",
                "$rootDir/upstream/BiliDownload/app/src/main/res/views/include",
                "$rootDir/upstream/BiliDownload/app/src/main/res/views/dialog",
                "$rootDir/upstream/BiliDownload/app/src/main/res/views/item"
            )
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
    }
    packaging { resources.excludes.add("META-INF/INDEX.LIST") }
}
ksp {
    arg("room.schemaLocation", "$rootDir/upstream/BiliDownload/app/schemas")
    arg("room.incremental", "true")
    arg("room.expandProjection", "true")
}
dependencies {
    compileOnly(files("$rootDir/app/libs/ffmpeg-kit.aar"))
    implementation("com.arthenica:smart-exception-java:0.2.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity:1.8.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.3.10")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.room:room-runtime:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.11.0")
    implementation("cn.hutool:hutool-crypto:5.8.27")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("io.github.scwang90:refresh-layout-kernel:2.1.0")
    implementation("io.github.scwang90:refresh-header-classics:2.1.0")
    implementation("io.github.scwang90:refresh-footer-classics:2.1.0")
    implementation("org.greenrobot:eventbus:3.3.1")
    implementation("org.jsoup:jsoup:1.16.1")
    implementation("com.chibatching.kotpref:kotpref:2.13.2")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
