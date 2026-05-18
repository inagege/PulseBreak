package com.example.breakreminder.stress

import android.content.Context

class StressFeedbackStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class PendingStressFeedback(
        val adjustedScore: Float,
        val personalizationEnabled: Boolean
    )

    fun getPersonalizationBias(): Float = prefs.getFloat(KEY_BIAS, 0f)

    fun getFeedbackCount(): Int = prefs.getInt(KEY_FEEDBACK_COUNT, 0)

    fun setPendingPrompt(
        adjustedScore: Float,
        personalizationEnabled: Boolean
    ) {
        prefs.edit()
            .putBoolean(KEY_PENDING_PROMPT, true)
            .putFloat(KEY_PENDING_SCORE, adjustedScore)
            .putBoolean(KEY_PENDING_PERSONALIZATION, personalizationEnabled)
            .apply()
    }

    fun getPendingPrompt(): PendingStressFeedback? {
        val hasPending = prefs.getBoolean(KEY_PENDING_PROMPT, false)
        if (!hasPending) return null
        return PendingStressFeedback(
            adjustedScore = prefs.getFloat(KEY_PENDING_SCORE, 0f),
            personalizationEnabled = prefs.getBoolean(KEY_PENDING_PERSONALIZATION, true)
        )
    }

    fun clearPendingPrompt() {
        prefs.edit()
            .remove(KEY_PENDING_PROMPT)
            .remove(KEY_PENDING_SCORE)
            .remove(KEY_PENDING_PERSONALIZATION)
            .apply()
    }

    fun submitPendingFeedback(userFeelsStressed: Boolean) {
        val pending = getPendingPrompt() ?: return
        recordFeedback(
            adjustedScore = pending.adjustedScore,
            userFeelsStressed = userFeelsStressed,
            personalizationEnabled = pending.personalizationEnabled
        )
        clearPendingPrompt()
    }

    fun recordFeedback(
        adjustedScore: Float,
        userFeelsStressed: Boolean,
        personalizationEnabled: Boolean
    ) {
        val currentBias = getPersonalizationBias()
        val currentCount = getFeedbackCount()
        val target = if (userFeelsStressed) 1f else 0f
        val error = (target - adjustedScore).coerceIn(-1f, 1f)

        val nextCount = currentCount + 1
        val nextBias = if (personalizationEnabled) {
            val learningRate = 0.10f / (1f + (currentCount / 25f))
            (currentBias + (learningRate * error)).coerceIn(-0.25f, 0.25f)
        } else {
            currentBias
        }

        prefs.edit()
            .putInt(KEY_FEEDBACK_COUNT, nextCount)
            .putFloat(KEY_BIAS, nextBias)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "stress_feedback"
        private const val KEY_BIAS = "bias"
        private const val KEY_FEEDBACK_COUNT = "feedback_count"
        private const val KEY_PENDING_PROMPT = "pending_prompt"
        private const val KEY_PENDING_SCORE = "pending_score"
        private const val KEY_PENDING_PERSONALIZATION = "pending_personalization"
    }
}
