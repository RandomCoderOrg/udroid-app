package org.randomcoder.udroid.runtime

internal val ANDROID_PROOT_BIND_MOUNTS =
    listOf(
        "/system",
        "/apex",
        "/dev",
        "/proc",
        "/sys",
        "/linkerconfig/ld.config.txt",
    )

internal fun MutableList<String>.addAndroidProotBindMounts() {
    ANDROID_PROOT_BIND_MOUNTS.forEach { path ->
        add("-b")
        add(path)
    }
}
