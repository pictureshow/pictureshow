import app.pictureshow.buildlogic.Media3ControllerIdentityFix
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    id("pictureshow.media3-controller-identity")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
    id("com.mikepenz.aboutlibraries.plugin") version "15.3.0"
}

android {
    namespace = "app.pictureshow"
    compileSdk = 37
    ndkVersion = "26.1.10909125"
    defaultConfig {
        applicationId = "app.pictureshow"
        minSdk = 23
        targetSdk = 36
        versionCode = 16
        versionName = "0.3.0-rc14"
    }
    buildTypes {
        getByName("release") {
            // Keep function names in bundle metadata; APK libraries are still stripped.
            ndk.debugSymbolLevel = "SYMBOL_TABLE"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("deviceTest") {
            initWith(getByName("release"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    // Exercise the actual deviceTest provider through ContentResolver in host tests.
    sourceSets.getByName("test").kotlin.srcDir("src/deviceTest/kotlin")
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric API 36 initializes ApplicationSharedMemory through the JDK file-descriptor bridge.
        unitTests.all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
    lint {
        warningsAsErrors = true
        checkDependencies = true
        // The brief deliberately targets API 36 while compiling against 37.
        disable += "OldTargetApi"
    }
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// The compat stub reads the framework caller before MediaSession.Callback can intercept a key.
// Review/remove this narrow patch when upstream fixes https://github.com/androidx/media/issues/3214.
check(libs.versions.media3.get() == "1.11.1") { "Review the Media3 caller-identity workaround before upgrading" }
androidComponents.onVariants { variant ->
    variant.instrumentation.transformClassesWith(Media3ControllerIdentityFix::class.java, InstrumentationScope.ALL) {}
    variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.play.billing)
    implementation(libs.tvprovider)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.ui)
    implementation(libs.media3.decoder)
    implementation(libs.media3.common)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.okhttp)
    implementation(project(":media3-ffmpeg"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.tv.material)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    coreLibraryDesugaring(libs.desugar)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.media3.test.utils)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.ktor.client.mock)
}

tasks.named("check") { dependsOn("ktlintCheck", "lintDebug") }

// Export only release metadata. The asset task also checks the actual runtime artifacts,
// retains their embedded licenses/notices, and adds code outside Gradle's module graph.
aboutLibraries {
    collect {
        filterVariants.add("release")
        includePlatform = false
        fetchRemoteLicense = false
        fetchRemoteFunding = false
    }
    library { duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.KEEP }
    export {
        variant = "release"
        outputFile = layout.buildDirectory.file("licenses/aboutlibraries.json").get().asFile
    }
}
apply(from = "licenses.gradle")
