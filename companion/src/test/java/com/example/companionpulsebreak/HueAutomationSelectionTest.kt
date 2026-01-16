package com.example.companionpulsebreak

import com.example.commonlibrary.HueAutomationData
import com.example.companionpulsebreak.screens.computeTestAffectedLights
import com.example.companionpulsebreak.sync.HueLight
import org.junit.Assert.assertEquals
import org.junit.Test

class HueAutomationSelectionTest {
    @Test
    fun test_no_selection_returns_empty() {
        val lights = listOf(
            HueLight("1", "A", true, 50),
            HueLight("2", "B", true, 50)
        )
        val settings = HueAutomationData(lightIds = emptyList())
        val affected = computeTestAffectedLights(lights, settings, requireOn = true)
        assertEquals(0, affected.size)
    }

    @Test
    fun test_selected_on_and_off_filters_to_on() {
        val lights = listOf(
            HueLight("1", "A", true, 50),
            HueLight("2", "B", false, 0)
        )
        val settings = HueAutomationData(lightIds = listOf("1", "2"))
        val affected = computeTestAffectedLights(lights, settings, requireOn = true)
        assertEquals(1, affected.size)
        assertEquals("1", affected[0].id)
    }

    @Test
    fun test_requireOn_false_includes_all_selected() {
        val lights = listOf(
            HueLight("1", "A", true, 50),
            HueLight("2", "B", false, 0)
        )
        val settings = HueAutomationData(lightIds = listOf("1", "2"))
        val affected = computeTestAffectedLights(lights, settings, requireOn = false)
        assertEquals(2, affected.size)
    }
}

