import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.compose") version "1.12.2"
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.coroutines.test)
    implementation(compose.desktop.currentOs)
    implementation("net.java.dev.jna:jna:5.18.1")
    implementation("org.jogamp.jogl:jogl-all-main:2.6.0")
    implementation("org.jogamp.gluegen:gluegen-rt-main:2.6.0")
}

// Reuse the compiled suites: several test helpers and production internals rely on
// Kotlin's core friend compilation. Recompiling them in another module breaks that contract.
evaluationDependsOn(":core")
val coreTests = project(":core").extensions.getByType<KotlinMultiplatformExtension>()
    .targets.getByName("jvm").compilations.getByName("test")
val vectorTest = tasks.register<Test>("vectorTest") {
    dependsOn(":core:jvmTestClasses", tasks.testClasses)
    testClassesDirs = files(coreTests.output.classesDirs, sourceSets.test.get().output.classesDirs)
    classpath = files(coreTests.output.allOutputs, coreTests.runtimeDependencyFiles, sourceSets.test.get().runtimeClasspath)
    systemProperty("pictureshow.repository", rootProject.projectDir.absolutePath)
    include("**/*VectorsTest.class")
    testLogging { events("passed", "failed", "skipped") }
}
tasks.test {
    dependsOn(vectorTest)
    exclude("**/*VectorsTest.class")
}

compose.desktop {
    application {
        mainClass = "app.pictureshow.desktop.MainKt"
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED", "-Dcompose.interop.blending=true")
        if (System.getProperty("os.name").startsWith("Mac")) {
            jvmArgs += "--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED"
        }
    }
}
// A portable runtime layout; each OS resolves its own Skiko/JOGL native jars.
tasks.register<Sync>("spikeRuntime") {
    dependsOn(tasks.jar)
    from(configurations.runtimeClasspath) { into("lib") }
    from(tasks.jar) { into("lib") }
    into(layout.buildDirectory.dir("spike-runtime"))
}
