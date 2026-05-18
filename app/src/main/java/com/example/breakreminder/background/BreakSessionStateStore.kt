package com.example.breakreminder.background

import android.content.Context

object BreakSessionStateStore {
    private const val PREFS_NAME = "break_session_state"
    private const val KEY_ACTIVE = "active"
    private const val KEY_STARTED_AT = "started_at"
    private const val MAX_SESSION_AGE_MILLIS = 6 * 60 * 60 * 1000L

    fun markSessionStarted(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_STARTED_AT, nowMillis)
            .apply()
    }

    fun markSessionEnded(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, false)
            .remove(KEY_STARTED_AT)
            .apply()
    }

    fun isSessionActive(context: Context, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val active = prefs.getBoolean(KEY_ACTIVE, false)
        if (!active) return false

        val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
        if (startedAt <= 0L) return true

        val expired = nowMillis - startedAt > MAX_SESSION_AGE_MILLIS
        if (expired) {
            markSessionEnded(context)
            return false
        }
        return true
    }
}
