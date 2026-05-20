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

    fun submitPendingFeedback(feedbackScore: Int): Boolean {
        val pending = getPendingPrompt() ?: return false
        val normalizedScore = feedbackScore.coerceIn(1, 4)
        recordFeedback(
            adjustedScore = pending.adjustedScore,
            feedbackScore = normalizedScore,
            personalizationEnabled = pending.personalizationEnabled
        )
        clearPendingPrompt()
        return normalizedScore >= 3
    }

    fun recordFeedback(
        adjustedScore: Float,
        feedbackScore: Int,
        personalizationEnabled: Boolean
    ) {
        val currentBias = getPersonalizationBias()
        val currentCount = getFeedbackCount()
        val clampedScore = feedbackScore.coerceIn(1, 4)
        val target = (clampedScore - 1) / 3f
        val rawError = (target - adjustedScore).coerceIn(-1f, 1f)
        val error = if (clampedScore >= 3 && rawError < 0f) 0f else rawError

        val nextCount = currentCount + 1
        val nextBias = if (personalizationEnabled) {
            val baseLearningRate = 0.10f / (1f + (currentCount / 25f))
            val weight = if (clampedScore <= 2) 1.6f else 1f
            val learningRate = baseLearningRate * weight
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
