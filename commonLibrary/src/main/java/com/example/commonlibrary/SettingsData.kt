package com.example.commonlibrary

// New enum for color modes used by the UI and proto mapping
enum class HueColorMode { SCENE, CUSTOM_COLOR, CUSTOM_WHITE }

data class HueAutomationData(
    val lightIds: List<String> = emptyList(),
    val groupIds: List<String> = emptyList(),
    val brightness: Int = 100,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val colorTemperature: Int = 0,
    val sceneId: String? = null,
    val colorMode: HueColorMode = HueColorMode.SCENE,
    val scenePreviewArgb: Int = 0xFFFFFFFF.toInt()
)

data class SettingsData(
    val isDarkMode: Boolean = false,
    val buttonColor: Int = 0xFF90EE90.toInt(),
    val buttonTextColor: Int = 0xFF2F4F4F.toInt(),
    val screenSelection: String = "Grid",
    val hueAutomation: HueAutomationData = HueAutomationData()
)