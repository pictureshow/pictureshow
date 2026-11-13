import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.compose") version "1.12.2"
    id("com.android.kotlin.multiplatform.library")
}
kotlin {
    jvmToolchain(21)
    jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    androidLibrary { namespace = "app.pictureshow.ui"; compileSdk = 37; minSdk = 23 }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.coil.compose)
        }
    }
}
