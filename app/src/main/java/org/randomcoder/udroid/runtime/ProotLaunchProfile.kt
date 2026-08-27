package org.randomcoder.udroid.runtime

/** Optional, command-scoped additions to a PRoot launch. */
interface ProotLaunchProfile {
    fun addBindings(arguments: MutableList<String>)

    fun wrapGuestCommand(command: List<String>): List<String>
}
