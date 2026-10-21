package com.os4.musiccover.island

internal object NotificationPictureTheme {
    fun keys(keys: List<String>, dark: Boolean): List<String> {
        if (dark) return keys
        // Swap each themed pair in place, keeping AOD and other fallbacks last.
        return keys.map { key ->
            when {
                key.endsWith("Dark") && key.removeSuffix("Dark") in keys -> key.removeSuffix("Dark")
                "${key}Dark" in keys -> "${key}Dark"
                else -> key
            }
        }
    }
}
