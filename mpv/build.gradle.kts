import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// libmpv for Android with an LGPL-only closure: mpv (-Dgpl=false), FFmpeg without GPL, version3 or nonfree parts,
// and permissive or LGPL dependencies. Prebuilt in src/main/jniLibs from pinned sources by tools/mpv/build.sh;
// builds do not compile native code. The Kotlin binding here is MPL-2.0 like the rest of the app.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "app.pictureshow.mpv"
    compileSdk = 37
    defaultConfig {
        minSdk = 23
        consumerProguardFiles("proguard-rules.txt")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = true
    }
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.annotation)
}

tasks.named("check") { dependsOn("ktlintCheck", "lintDebug") }
