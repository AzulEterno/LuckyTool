package com.luckyzyx.luckytool.utils

/** The stacked layout depends on the four-bar ColorOS 16.1 status bar renderer. */
internal object MobileSignalDisplay {
    const val KEY = "mobile_signal_display"
    const val SYSTEM = "system"
    const val STACKED = "stacked"
    const val ROTATED = "stacked_rotated"

    fun isSupported(sdk: Int, osCode: Int) = sdk == 36 && osCode == 38
    fun isStacked(value: String?) = value == STACKED || value == ROTATED

    fun stockLevel(resourceEntry: String): Int? {
        val prefix = "stat_signal_signal_lte_single_"
        if (!resourceEntry.startsWith(prefix) || resourceEntry.length != prefix.length + 1) return null
        return resourceEntry.last().digitToIntOrNull()?.takeIf { it in 0..4 }
    }
}
