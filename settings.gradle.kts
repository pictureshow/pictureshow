pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central, ahead of Central itself: GitHub's runners share addresses that Central
        // limits, and it sometimes answered 404 for every file a job asked for (desktop/licenses.gradle).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "Pictureshow"
include(":core", ":app", ":media3-ffmpeg", ":mpv")

include(":desktop")

include(":ui-shared")
