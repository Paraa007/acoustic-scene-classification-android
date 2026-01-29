package com.fzi.acousticscene

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Welcome Screen / Landing Page
 *
 * Provides two mode options:
 * - User Mode: Uses the standard model from user_models/
 * - Development Mode: Allows selection of experimental models from dev_models/
 */
class WelcomeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODEL_PATH = "extra_model_path"
        const val EXTRA_MODEL_NAME = "extra_model_name"
        const val EXTRA_IS_DEV_MODE = "extra_is_dev_mode"

        private const val USER_MODELS_DIR = "user_models"
        private const val DEV_MODELS_DIR = "dev_models"
        private const val DEFAULT_USER_MODEL = "model1.pt"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable Edge-to-Edge for modern devices
        enableEdgeToEdge()

        setContentView(R.layout.activity_welcome)

        // Window Insets for dynamic padding (Status Bar, Navigation Bar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val userModeCard: MaterialCardView = findViewById(R.id.userModeCard)
        val devModeCard: MaterialCardView = findViewById(R.id.devModeCard)
        val viewHistoryButton: MaterialButton = findViewById(R.id.viewHistoryButton)

        // User Mode: Use default model from user_models/
        userModeCard.setOnClickListener {
            startMainActivityWithModel(
                modelPath = "$USER_MODELS_DIR/$DEFAULT_USER_MODEL",
                modelName = DEFAULT_USER_MODEL.removeSuffix(".pt"),
                isDevMode = false
            )
        }

        // Development Mode: Show model selection dialog
        devModeCard.setOnClickListener {
            showDevModelSelectionDialog()
        }

        // Navigate to HistoryActivity
        viewHistoryButton.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        }
    }

    /**
     * Scans dev_models/ directory and shows a custom Material 3 selection dialog
     */
    private fun showDevModelSelectionDialog() {
        val devModels = getDevModels()

        if (devModels.isEmpty()) {
            // No models found - show info dialog
            showNoModelsDialog()
            return
        }

        // Inflate custom dialog layout
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_model_selection, null)
        val modelListContainer = dialogView.findViewById<LinearLayout>(R.id.modelListContainer)

        val dialog = MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        // Add model rows
        devModels.forEach { modelFile ->
            val modelName = modelFile.removeSuffix(".pt")
            val classCount = getModelClassCount(modelName)

            val rowView = LayoutInflater.from(this).inflate(R.layout.item_model_row, modelListContainer, false)

            rowView.findViewById<TextView>(R.id.modelName).text = "$modelName.pt"
            rowView.findViewById<TextView>(R.id.modelClasses).text = getString(R.string.classes_count, classCount)

            // Set click listener
            rowView.setOnClickListener {
                dialog.dismiss()
                startMainActivityWithModel(
                    modelPath = "$DEV_MODELS_DIR/$modelFile",
                    modelName = modelName,
                    isDevMode = true
                )
            }

            modelListContainer.addView(rowView)
        }

        // Cancel button
        dialogView.findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    /**
     * Shows a dialog when no models are found
     */
    private fun showNoModelsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_confirmation, null)

        dialogView.findViewById<TextView>(R.id.dialogTitle).text = getString(R.string.no_models_found)
        dialogView.findViewById<TextView>(R.id.dialogMessage).text =
            "No .pt model files found in assets/dev_models/.\n\nPlease add experimental models to the dev_models folder."

        // Hide confirm button, only show cancel as OK
        dialogView.findViewById<MaterialButton>(R.id.btnConfirm).visibility = android.view.View.GONE
        dialogView.findViewById<MaterialButton>(R.id.btnCancel).text = getString(R.string.ok)

        val dialog = MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        dialogView.findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    /**
     * Gets the class count for a model based on its name
     * model1 -> 8 Classes, model2 -> 9 Classes
     */
    private fun getModelClassCount(modelName: String): Int {
        val name = modelName.lowercase()
        return when {
            name.contains("model2") -> 9
            name.contains("model1") -> 8
            else -> 8 // Default
        }
    }

    /**
     * Gets list of .pt files in dev_models/ directory
     */
    private fun getDevModels(): List<String> {
        return try {
            assets.list(DEV_MODELS_DIR)
                ?.filter { it.endsWith(".pt") }
                ?.sorted()
                ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("WelcomeActivity", "Error listing dev models", e)
            emptyList()
        }
    }

    /**
     * Starts MainActivity with the selected model configuration
     */
    private fun startMainActivityWithModel(modelPath: String, modelName: String, isDevMode: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_MODEL_PATH, modelPath)
            putExtra(EXTRA_MODEL_NAME, modelName)
            putExtra(EXTRA_IS_DEV_MODE, isDevMode)
        }
        startActivity(intent)
        finish() // Don't keep WelcomeActivity in back stack
    }
}
