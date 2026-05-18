package com.example.breakreminder.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.breakreminder.stress.StressFeedbackStore

class StressFeedbackActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val action = intent.action ?: return
        val adjustedScore = intent.getFloatExtra(BreakNotificationHelper.EXTRA_FEEDBACK_SCORE, Float.NaN)
        if (adjustedScore.isNaN()) return

        val personalizationEnabled = intent.getBooleanExtra(
            BreakNotificationHelper.EXTRA_PERSONALIZATION_ENABLED,
            true
        )

        val userFeelsStressed = when (action) {
            BreakNotificationHelper.ACTION_FEEDBACK_YES -> true
            BreakNotificationHelper.ACTION_FEEDBACK_NO -> false
            else -> return
        }

        try {
            StressFeedbackStore(context).recordFeedback(
                adjustedScore = adjustedScore,
                userFeelsStressed = userFeelsStressed,
                personalizationEnabled = personalizationEnabled
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record stress feedback action: ${e.message}")
        } finally {
            BreakNotificationHelper.dismissStressFeedbackPrompt(context)
        }
    }

    companion object {
        private const val TAG = "StressFeedbackReceiver"
    }
}
