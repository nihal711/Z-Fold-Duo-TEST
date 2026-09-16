package com.foldduo.hinge.link

/**
 * Device-state identifiers resolved from the running system instead of being
 * hard-coded. Samsung assigns the concurrent-display states different
 * identifiers on different models and One UI versions; the Fold7 build this
 * project started on used 4 and 5, which remain the fallbacks.
 */
data class DeviceStateCatalog(
    val statesByName: Map<String, Int>,
) {
    val closed: Int get() = firstId(CLOSED_NAMES) ?: DEFAULT_CLOSED
    val concurrentInnerDefault: Int get() = firstId(CONCURRENT_INNER_NAMES) ?: DEFAULT_CONCURRENT_INNER
    val concurrentOuterDefault: Int get() = firstId(CONCURRENT_OUTER_NAMES) ?: DEFAULT_CONCURRENT_OUTER

    /** True when the concurrent states were actually reported by the device. */
    val supportsConcurrentDisplays: Boolean
        get() = firstId(CONCURRENT_INNER_NAMES) != null && firstId(CONCURRENT_OUTER_NAMES) != null

    val isEmpty: Boolean get() = statesByName.isEmpty()

    fun nameOf(identifier: Int): String? = statesByName.entries.firstOrNull { it.value == identifier }?.key

    private fun firstId(candidates: List<String>): Int? =
        candidates.firstNotNullOfOrNull { statesByName[it] }

    override fun toString(): String =
        statesByName.entries.sortedBy { it.value }.joinToString(", ") { "${it.value}=${it.key}" }

    companion object {
        const val DEFAULT_CLOSED = 0
        const val DEFAULT_CONCURRENT_INNER = 4
        const val DEFAULT_CONCURRENT_OUTER = 5

        val EMPTY = DeviceStateCatalog(emptyMap())

        private val CLOSED_NAMES = listOf("CLOSED")
        private val CONCURRENT_INNER_NAMES = listOf(
            "CONCURRENT_INNER_DEFAULT",
            "CONCURRENT_INNER",
            "DUAL_DISPLAY_INNER_DEFAULT",
        )
        private val CONCURRENT_OUTER_NAMES = listOf(
            "CONCURRENT_OUTER_DEFAULT",
            "CONCURRENT_OUTER",
            "DUAL_DISPLAY_OUTER_DEFAULT",
        )

        /**
         * Matches `DeviceState{identifier=0, name='CLOSED', ...}` as printed by
         * `cmd device_state print-states` and `dumpsys device_state` on
         * Android 14 through 17. Whitespace and trailing fields are tolerated.
         */
        private val STATE_ENTRY = Regex("identifier\\s*=\\s*(\\d+)\\s*,\\s*name\\s*=\\s*'([^']*)'")

        /** Matches the dump field for a given key, e.g. `mBaseState=Optional[DeviceState{...}]`. */
        private fun dumpField(key: String) =
            Regex("$key\\s*=\\s*(?:Optional\\[)?\\s*DeviceState\\{\\s*identifier\\s*=\\s*(\\d+)\\s*,\\s*name\\s*=\\s*'([^']*)'")

        fun parse(printStatesOutput: String): DeviceStateCatalog {
            val states = linkedMapOf<String, Int>()
            for (match in STATE_ENTRY.findAll(printStatesOutput)) {
                val id = match.groupValues[1].toIntOrNull() ?: continue
                val name = match.groupValues[2].trim()
                if (name.isEmpty()) continue
                states.putIfAbsent(name, id)
            }
            return DeviceStateCatalog(states)
        }

        /** Identifier and name of the physical (base) state in a `dumpsys device_state` dump. */
        fun baseState(dump: String): Pair<Int, String>? = field(dump, "mBaseState")

        /** Identifier and name of the committed state in a `dumpsys device_state` dump. */
        fun committedState(dump: String): Pair<Int, String>? = field(dump, "mCommittedState")

        private fun field(dump: String, key: String): Pair<Int, String>? {
            val match = dumpField(key).find(dump) ?: return null
            val id = match.groupValues[1].toIntOrNull() ?: return null
            return id to match.groupValues[2]
        }
    }
}
