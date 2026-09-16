package com.foldduo.hinge.link

/** Parses `cmd power set-wakelock list`, which is how shell-held display wake locks are inspected. */
object ShellWakeLocks {
    private val DISPLAY_FULL_WAKE_LOCK = Regex("Display (\\d+), wakelock type: FULL_WAKE_LOCK:")

    /** Display IDs that currently hold a shell FULL_WAKE_LOCK. */
    fun heldDisplays(listOutput: String): Set<Int> =
        listOutput.lineSequence()
            .filter { it.contains("held=true") }
            .mapNotNull { line -> DISPLAY_FULL_WAKE_LOCK.find(line)?.groupValues?.get(1)?.toIntOrNull() }
            .toSet()

    fun isHeld(listOutput: String, displayId: Int): Boolean = displayId in heldDisplays(listOutput)
}
