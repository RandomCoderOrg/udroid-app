package org.randomcoder.udroid.runtime

internal val ANDROID_PROOT_BIND_MOUNTS =
    PROOT_DEFAULT_MOUNTS.map(ProotDefaultMount::hostSource)

internal fun MutableList<String>.addProotBindMounts(mounts: List<ResolvedProotMount>) {
    mounts.forEach { mount ->
        add("-b")
        add(mount.argument)
    }
}

internal fun MutableList<String>.addAndroidProotBindMounts() =
    addProotBindMounts(ProotMountResolver.defaults())
