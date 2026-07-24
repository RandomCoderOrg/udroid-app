pluginManagement {
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
        mavenCentral()
    }
}

rootProject.name = "uDroid"
include(":app")
include(":terminal-emulator")
include(":terminal-view")
project(":terminal-emulator").projectDir = file("third_party/termux/terminal-emulator")
project(":terminal-view").projectDir = file("third_party/termux/terminal-view")
