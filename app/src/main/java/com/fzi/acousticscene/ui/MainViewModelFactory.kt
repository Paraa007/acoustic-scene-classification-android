package com.fzi.acousticscene.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Factory for creating MainViewModel with model configuration parameters
 */
class MainViewModelFactory(
    private val application: Application,
    private val modelPath: String,
    private val modelName: String,
    private val isDevMode: Boolean
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(application, modelPath, modelName, isDevMode) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
