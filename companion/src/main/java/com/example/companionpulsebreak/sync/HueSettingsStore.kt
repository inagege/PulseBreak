package com.example.companionpulsebreak.sync

import android.content.Context

object HueSettingsStore {
    private const val PREFS_NAME = "hue_prefs"
    private const val KEY_BRIDGE_IP = "bridge_ip"
    private const val KEY_HUE_USERNAME = "hue_username"

    fun getBridgeIp(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_BRIDGE_IP, null)
    }

    fun getHueUsername(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_HUE_USERNAME, null)
    }

    fun persist(context: Context, ip: String?, username: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (ip == null) editor.remove(KEY_BRIDGE_IP) else editor.putString(KEY_BRIDGE_IP, ip)
        if (username == null) editor.remove(KEY_HUE_USERNAME) else editor.putString(KEY_HUE_USERNAME, username)
        editor.apply()
    }
}
