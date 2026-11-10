import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.serialization.json)
            implementation(libs.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.ktor.client.mock)
        }
    }
}

tasks.named("check") { dependsOn("ktlintCheck") }

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

// The same entry point can run in Gradle or from an exported JVM runtime on a server host.
val compatibilityCompilation = kotlin.targets.getByName("jvm").compilations.getByName("test")
tasks.register<JavaExec>("serverCompat") {
    dependsOn("jvmTestClasses")
    classpath(compatibilityCompilation.output.allOutputs, compatibilityCompilation.runtimeDependencyFiles)
    mainClass.set("app.pictureshow.core.ServerCompatibilityKt")
}
tasks.register<Sync>("serverCompatRuntime") {
    dependsOn("jvmTestClasses")
    from(compatibilityCompilation.output.allOutputs) { into("classes") }
    from(tasks.named("jvmJar")) { into("lib") }
    from(requireNotNull(compatibilityCompilation.runtimeDependencyFiles)) { into("lib") }
    into(layout.buildDirectory.dir("reports/server-compat/runtime"))
}

// Run the repository parity contract as part of ordinary :core:jvmTest (and check).
tasks.named<ProcessResources>("jvmTestProcessResources") {
    val controlSuites =
        listOf(
            "session-owner.json",
            "session-command.json",
            "queue-edit.json",
            "queue-order.json",
            "playback-mode.json",
            "cast-handoff.json",
            "cast-capability.json",
            "cast-session.json",
            "music-catalog.json",
            "music-queue.json",
            "music-session.json",
            "music-reports.json",
            "music-quality.json",
            "music-playlists.json"
        )
    val remoteSuites = listOf(
        "remote-targets.json",
        "remote-wire.json",
        "remote-receive.json",
        "remote-reconnect.json",
        "remote-queue.json"
    )
    val liveSuites = listOf("live-guide.json", "live-dvr.json", "live-source.json")
    val offlineSuites =
        listOf(
            "offline-job.json",
            "offline-budget.json",
            "offline-resume.json",
            "offline-manifest.json",
            "offline-outbox.json",
            "offline-plan.json",
            "offline-reconnect.json",
            "offline-next.json",
            "offline-fmp4.json"
        )
    from(rootProject.file("spec/vectors")) {
        exclude(liveSuites)
        // Native formatter/layout vectors run in the Android presentation suite.
        exclude("live-presentation.json")
        exclude(controlSuites)
        exclude(remoteSuites)
        exclude(offlineSuites)
        exclude("grid-navigation.json")
        into("parity-vectors")
    }
    from(rootProject.file("spec/vectors")) {
        include(liveSuites)
        into("live-vectors")
    }
    from(rootProject.file("spec/vectors")) {
        include(offlineSuites)
        into("offline-vectors")
    }
    from(rootProject.file("spec/vectors")) {
        include("grid-navigation.json")
        into("grid-vectors")
    }
    from(rootProject.file("spec/vectors")) {
        include(remoteSuites)
        into("remote-vectors")
    }
    from(rootProject.file("spec/vectors")) {
        include(controlSuites)
        into("control-vectors")
    }
}
