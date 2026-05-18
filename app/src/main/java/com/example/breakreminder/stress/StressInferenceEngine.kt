package com.example.breakreminder.stress

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

data class StressSample(
    val timestampMillis: Long,
    val heartRateBpm: Float,
    val motionMagnitude: Float?,
    val ambientTemperatureC: Float?
)

data class StressFeedbackConfig(
    val feedbackPromptEnabled: Boolean,
    val personalizationEnabled: Boolean
)

data class StressPrediction(
    val baselineScore: Float,
    val adjustedScore: Float,
    val threshold: Float,
    val isStress: Boolean,
    val usedSignals: Set<String>
)

class StressInferenceEngine(
    private val modelRunner: StressModelRunner? = null
) {
    private val fallbackThreshold = 0.62f

    fun predict(
        samples: List<StressSample>,
        personalizationBias: Float,
        personalizationEnabled: Boolean
    ): StressPrediction {
        if (samples.isEmpty()) {
            return StressPrediction(
                baselineScore = 0f,
                adjustedScore = 0f,
                threshold = fallbackThreshold,
                isStress = false,
                usedSignals = emptySet()
            )
        }

        val hrValues = samples.map { it.heartRateBpm }.filter { it > 0f }
        if (hrValues.size < 4) {
            return StressPrediction(
                baselineScore = 0f,
                adjustedScore = 0f,
                threshold = fallbackThreshold,
                isStress = false,
                usedSignals = setOf("hr")
            )
        }

        val motionValues = samples.mapNotNull { it.motionMagnitude }
        val tempValues = samples.mapNotNull { it.ambientTemperatureC }
        val features = buildFeatureVector(hrValues, motionValues, tempValues)
        val baselineScore = modelRunner?.predict(features) ?: heuristicScore(features)
        val modelThreshold = modelRunner?.decisionThreshold
        val baseThreshold = (modelThreshold ?: fallbackThreshold).coerceIn(0.05f, 0.95f)
        val adaptiveThreshold = if (personalizationEnabled) {
            (baseThreshold - personalizationBias).coerceIn(0.05f, 0.95f)
        } else {
            baseThreshold
        }
        val adjustedScore = baselineScore
        val usedSignals = buildSet {
            add("hr")
            if (motionValues.isNotEmpty()) add("motion")
            if (tempValues.isNotEmpty()) add("temperature")
        }

        return StressPrediction(
            baselineScore = baselineScore,
            adjustedScore = adjustedScore,
            threshold = adaptiveThreshold,
            isStress = adjustedScore >= adaptiveThreshold,
            usedSignals = usedSignals
        )
    }

    private fun buildFeatureVector(
        hrValues: List<Float>,
        motionValues: List<Float>,
        tempValues: List<Float>
    ): FloatArray {
        val hrMean = hrValues.average().toFloat()
        val hrStd = stdDev(hrValues, hrMean)
        val hrMin = hrValues.minOrNull() ?: hrMean
        val hrMax = hrValues.maxOrNull() ?: hrMean
        val hrDelta = hrValues.last() - hrValues.first()
        val rmssd = rmssd(hrValues)
        val pnn50 = pnn50(hrValues)
        val motionMean = motionValues.averageOrZero()
        val motionStd = if (motionValues.isNotEmpty()) stdDev(motionValues, motionMean) else 0f
        val tempMean = tempValues.averageOrZero()
        val tempSpread = if (tempValues.size >= 2) {
            (tempValues.maxOrNull() ?: tempMean) - (tempValues.minOrNull() ?: tempMean)
        } else {
            0f
        }
        return floatArrayOf(
            hrMean,
            hrStd,
            hrMin,
            hrMax,
            abs(hrDelta),
            rmssd,
            pnn50,
            motionMean,
            motionStd,
            tempMean,
            tempSpread
        )
    }

    private fun heuristicScore(features: FloatArray): Float {
        val hrMean = features[0]
        val hrStd = features[1]
        val hrDeltaAbs = features[4]
        val pnn50 = features[6]
        val motionMean = features[7]
        val tempSpread = features[10]

        val logit =
            (-2.8f) +
                (0.032f * (hrMean - 70f)) +
                (0.08f * hrStd) +
                (0.04f * hrDeltaAbs) +
                (-0.7f * pnn50) +
                (-0.65f * motionMean) +
                (0.05f * tempSpread)
        return sigmoid(logit)
    }

    private fun stdDev(values: List<Float>, mean: Float): Float {
        if (values.size <= 1) return 0f
        val variance = values.fold(0f) { acc, v ->
            val diff = v - mean
            acc + (diff * diff)
        } / values.size
        return sqrt(variance)
    }

    private fun sigmoid(x: Float): Float {
        return (1f / (1f + exp(-x))).coerceIn(0f, 1f)
    }

    private fun rmssd(values: List<Float>): Float {
        if (values.size < 2) return 0f
        var sum = 0f
        var count = 0
        for (i in 1 until values.size) {
            val diff = values[i] - values[i - 1]
            sum += diff * diff
            count++
        }
        return sqrt(sum / count.coerceAtLeast(1))
    }

    private fun pnn50(values: List<Float>): Float {
        if (values.size < 2) return 0f
        var nn50 = 0
        for (i in 1 until values.size) {
            if (abs(values[i] - values[i - 1]) > 5f) nn50++
        }
        return nn50.toFloat() / (values.size - 1).toFloat()
    }

    private fun List<Float>.averageOrZero(): Float {
        return if (isEmpty()) 0f else average().toFloat()
    }
}
