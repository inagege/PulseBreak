package com.example.commonlibrary

data class SettingsData(
    val isDarkMode: Boolean = false,
    val buttonColor: Int = 0xFF90EE90.toInt(),
    val buttonTextColor: Int = 0xFF2F4F4F.toInt(),
    val screenSelection: String = "Grid"
)