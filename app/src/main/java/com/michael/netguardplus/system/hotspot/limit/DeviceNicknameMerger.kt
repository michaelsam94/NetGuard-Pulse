package com.michael.netguardplus.system.hotspot.limit

/**
 * Picks the best display name when hotspot discovery and the in-memory registry
 * disagree (e.g. "Unknown device" vs "Connected Device (192.168.x.x)").
 */
object DeviceNicknameMerger {

    private const val UNKNOWN = "Unknown device"

    fun best(incoming: String, existing: String): String {
        val inc = incoming.trim()
        val ex = existing.trim()
        val incScore = score(inc)
        val exScore = score(ex)
        return when {
            incScore > exScore -> inc
            exScore > incScore -> ex
            inc.isNotBlank() -> inc
            ex.isNotBlank() -> ex
            else -> UNKNOWN
        }
    }

    private fun score(name: String): Int = when {
        name.isBlank() -> 0
        name == UNKNOWN -> 1
        name == "Connected Device" -> 2
        name.startsWith("Connected Device (") -> 3
        name.startsWith("Device ") && name.length <= 14 -> 4
        else -> 10
    }
}
