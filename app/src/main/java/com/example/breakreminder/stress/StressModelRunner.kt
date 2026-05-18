package com.example.breakreminder.stress

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class StressModelRunner(context: Context) {
    private val interpreter: Interpreter?
    val decisionThreshold: Float?

    init {
        interpreter = try {
            val model = loadModelFile(context, MODEL_ASSET_PATH)
            Interpreter(model)
        } catch (t: Throwable) {
            Log.i(TAG, "No TFLite model loaded. Using heuristic fallback. ${t.message}")
            null
        }

        decisionThreshold = loadDecisionThreshold(context)
    }

    fun predict(featureVector: FloatArray): Float? {
        val tflite = interpreter ?: return null
        return try {
            val input = arrayOf(featureVector)
            val output = Array(1) { FloatArray(1) }
            tflite.run(input, output)
            output[0][0].coerceIn(0f, 1f)
        } catch (t: Throwable) {
            Log.w(TAG, "TFLite inference failed: ${t.message}")
            null
        }
    }

    private fun loadDecisionThreshold(context: Context): Float? {
        return try {
            context.assets.open(METADATA_ASSET_PATH).use { stream ->
                val text = stream.bufferedReader().use { it.readText() }
                val obj = JSONObject(text)
                if (obj.has("decision_threshold")) {
                    val value = obj.optDouble("decision_threshold", Double.NaN).toFloat()
                    if (value.isFinite()) value else null
                } else null
            }
        } catch (t: Throwable) {
            Log.i(TAG, "No metadata threshold available. ${t.message}")
            null
        }
    }

    private fun loadModelFile(context: Context, assetPath: String): MappedByteBuffer {
        context.assets.openFd(assetPath).use { fileDescriptor ->
            fileDescriptor.createInputStream().channel.use { fileChannel ->
                return fileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fileDescriptor.startOffset,
                    fileDescriptor.declaredLength
                )
            }
        }
    }

    companion object {
        private const val TAG = "StressModelRunner"
        private const val MODEL_ASSET_PATH = "ml/stress_model.tflite"
        private const val METADATA_ASSET_PATH = "ml/model_metadata.json"
    }
}
