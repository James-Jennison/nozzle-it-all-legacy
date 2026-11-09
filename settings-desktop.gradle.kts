pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "NozzleItAllDesktop"

// Desktop validation deliberately excludes the Android application and its
// device-only helper modules. `./gradlew --settings-file
// settings-desktop.gradle.kts :desktop:test` must therefore configure and run
// without ANDROID_HOME, ANDROID_SDK_ROOT, an Android SDK, or an NDK.
include(
    ":environment-api",
    ":environment-core",
    ":domain",
    ":transport",
    ":printer-api",
    ":project-format",
    ":adapter-common",
    ":adapter-paxx",
    ":adapter-octoprint",
    ":adapter-prusa",
    ":adapter-bambu",
    ":adapter-elegoo",
    ":desktop",
)
