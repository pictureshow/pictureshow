import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Media3's FFmpeg audio extension (androidx.media3.decoder.ffmpeg, Apache-2.0, sources unchanged from
// Media3 1.11.1) with prebuilt LGPL FFmpeg libraries in src/main/jniLibs. Builds do not compile native code;
// the app uses its pinned NDK to strip APK copies and extract release function symbols.
// tools/ffmpeg/build.sh rebuilds the libraries from pinned sources.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 37
    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("proguard-rules.txt")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = true
        lintConfig = file("lint.xml")
    }
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.media3.decoder)
    implementation(libs.media3.exoplayer)
    implementation(libs.androidx.annotation)
    compileOnly(libs.checker.qual)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlin.test.junit)
}

tasks.named("check") { dependsOn("ktlintCheck", "lintDebug") }
