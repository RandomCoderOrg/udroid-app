import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
}

val updateSigningStore = providers.environmentVariable("UDROID_SIGNING_STORE_FILE").orNull
val updateSigningStorePassword =
    providers.environmentVariable("UDROID_SIGNING_STORE_PASSWORD").orNull
val updateSigningKeyAlias = providers.environmentVariable("UDROID_SIGNING_KEY_ALIAS").orNull
val updateSigningKeyPassword =
    providers.environmentVariable("UDROID_SIGNING_KEY_PASSWORD").orNull
val hasUpdateSigning =
    listOf(
        updateSigningStore,
        updateSigningStorePassword,
        updateSigningKeyAlias,
        updateSigningKeyPassword,
    ).all { !it.isNullOrBlank() }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "org.randomcoder.udroid"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    testBuildType = "probe"

    defaultConfig {
        applicationId = "org.randomcoder.udroid"
        minSdk = 26

        // App-private Android ELFs are launched through /system/bin/linker(64),
        // following the execution bridge merged into official termux-exec.
        targetSdk = 36

        versionCode = 13
        versionName = "0.1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField(
            "String",
            "UPDATE_RELEASES_API",
            "\"https://api.github.com/repos/RandomCoderOrg/udroid-app/releases?per_page=20\"",
        )

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-Wall", "-Wextra", "-Werror")
            }
        }
    }

    signingConfigs {
        if (hasUpdateSigning) {
            create("updates") {
                storeFile = file(updateSigningStore!!)
                storePassword = updateSigningStorePassword
                keyAlias = updateSigningKeyAlias
                keyPassword = updateSigningKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig =
                if (hasUpdateSigning) {
                    signingConfigs.getByName("updates")
                } else {
                    signingConfigs.getByName("debug")
                }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("dev") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "+dev"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        create("probe") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".ociprobe"
            matchingFallbacks += "debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libproot-loader.so"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

}

dependencies {
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.compose.animation:animation:1.7.8")
    implementation("androidx.compose.ui:ui:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.8")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.tukaani:xz:1.12")
    implementation(project(":terminal-view"))
    implementation(project(":x11-lorie")) {
        // Upstream currently declares Lifecycle 2.11 but does not reference it.
        // That release requires compileSdk 37/AGP 9.1; keep the API 36 app free
        // of the unused transitive dependency.
        exclude(group = "androidx.lifecycle", module = "lifecycle-viewmodel-ktx")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }

    debugImplementation("androidx.compose.ui:ui-tooling:1.7.8")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.8")

    baselineProfile(project(":baseline-profile"))

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

val verifyGfxstreamRuntimeAssets by
    tasks.registering {
        val runtimeRoot = file("src/main/assets/runtime/arm64-v8a")
        inputs.dir(runtimeRoot.resolve("gfxstream-host"))
        inputs.dir(runtimeRoot.resolve("gfxstream-guest"))

        doLast {
            fun ByteArray.containsSequence(needle: ByteArray): Boolean {
                if (needle.isEmpty() || needle.size > size) return false
                for (start in 0..size - needle.size) {
                    var matched = true
                    for (offset in needle.indices) {
                        if (this[start + offset] != needle[offset]) {
                            matched = false
                            break
                        }
                    }
                    if (matched) return true
                }
                return false
            }

            val bundleNames = listOf("gfxstream-host", "gfxstream-guest")
            val manifests =
                bundleNames.associateWith { bundleName ->
                    val bundle = runtimeRoot.resolve(bundleName)
                    Properties().apply {
                        bundle.resolve("MANIFEST.properties").inputStream().use(::load)
                    }
                }
            val hostProtocolGeneration = manifests.getValue("gfxstream-host").getProperty("protocol_generation")
            val guestProtocolGeneration = manifests.getValue("gfxstream-guest").getProperty("protocol_generation")
            check(hostProtocolGeneration == "2") {
                "gfxstream host protocol_generation must be 2"
            }
            check(guestProtocolGeneration == hostProtocolGeneration) {
                "gfxstream host/guest protocol_generation mismatch"
            }

            bundleNames.forEach { bundleName ->
                val bundle = runtimeRoot.resolve(bundleName)
                val manifest = manifests.getValue(bundleName)
                manifest.stringPropertyNames()
                    .filter { it.endsWith(".sha256") }
                    .forEach { digestProperty ->
                        val entry = digestProperty.removeSuffix(".sha256")
                        val artifact = bundle.resolve(entry)
                        check(artifact.isFile) { "$bundleName is missing $entry" }
                        val digest = MessageDigest.getInstance("SHA-256")
                        artifact.inputStream().buffered().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                digest.update(buffer, 0, count)
                            }
                        }
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        check(actual == manifest.getProperty(digestProperty)) {
                            "$bundleName digest mismatch for $entry"
                        }
                    }

                if (bundleName == "gfxstream-guest") {
                    val expectedRevision = manifest.getProperty("mesa_commit").take(10)
                    val embeddedRevision = "git-$expectedRevision".encodeToByteArray()
                    val bytes = bundle.resolve("lib/libvulkan_gfxstream.so").readBytes()
                    check(bytes.containsSequence(embeddedRevision)) {
                        "gfxstream guest binary does not identify Mesa $expectedRevision"
                    }
                }
            }
        }
    }

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(verifyGfxstreamRuntimeAssets)
}
