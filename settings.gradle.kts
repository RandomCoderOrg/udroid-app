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
include(":x11-lorie")
include(":shell-loader:stub")
project(":terminal-emulator").projectDir = file("third_party/termux/terminal-emulator")
project(":terminal-view").projectDir = file("third_party/termux/terminal-view")
project(":x11-lorie").projectDir = file("third_party/termux-x11/lorie")
project(":shell-loader:stub").projectDir = file("third_party/termux-x11/shell-loader/stub")
