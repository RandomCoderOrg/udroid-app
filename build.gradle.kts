plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}

val prepareTermuxX11Source by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Applies pinned Termux:X11 native compatibility patches idempotently"
    commandLine("bash", rootProject.file("tools/prepare-termux-x11-source.sh"))
}

project(":x11-lorie") {
    configurations.configureEach {
        exclude(group = "androidx.lifecycle", module = "lifecycle-viewmodel-ktx")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }

    pluginManager.withPlugin("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            ndkVersion = "28.2.13676358"
            defaultConfig {
                ndk {
                    abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
                }
            }
        }
    }

    tasks.configureEach {
        if (name.startsWith("configureCMake")) {
            dependsOn(prepareTermuxX11Source)
        }
    }
}
