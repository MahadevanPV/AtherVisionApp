// ModelPreferences.kt
package com.example.yololitertobjectdetection

import android.content.Context

object ModelPreferences {
    private const val PREFS_NAME = "model_preferences"
    private const val KEY_SELECTED_MODEL = "selected_model"
    private const val DEFAULT_MODEL = "yolov10n_float16.tflite"

    fun saveSelectedModel(context: Context, modelPath: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SELECTED_MODEL, modelPath).apply()
    }

    fun getSelectedModel(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SELECTED_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    }
}