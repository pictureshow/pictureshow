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
