// Constants.kt
package com.example.yololitertobjectdetection

import android.content.Context

object Constants {
    // Dynamic model path based on preferences
    fun getModelPath(context: Context): String {
        return ModelPreferences.getSelectedModel(context)
    }

    val LABELS_PATH: String? = null
}