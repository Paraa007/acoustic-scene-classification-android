package com.fzi.acousticscene

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

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

        // Edge-to-Edge aktivieren für moderne Geräte
        enableEdgeToEdge()

        setContentView(R.layout.activity_welcome)

        // Window Insets für dynamisches Padding (Status Bar, Navigation Bar)
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

        // Navigation zu HistoryActivity
        viewHistoryButton.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        }
    }

    /**
     * Scans dev_models/ directory and shows a selection dialog
     */
    private fun showDevModelSelectionDialog() {
        val devModels = getDevModels()

        if (devModels.isEmpty()) {
            // No models found - show info dialog
            AlertDialog.Builder(this)
                .setTitle("No Models Found")
                .setMessage("No .pt model files found in assets/dev_models/.\n\nPlease add experimental models to the dev_models folder.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        // Show model selection dialog
        val modelNames = devModels.map { it.removeSuffix(".pt") }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Experimental Model")
            .setItems(modelNames) { _, which ->
                val selectedModel = devModels[which]
                startMainActivityWithModel(
                    modelPath = "$DEV_MODELS_DIR/$selectedModel",
                    modelName = selectedModel.removeSuffix(".pt"),
                    isDevMode = true
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        finish() // WelcomeActivity nicht im Back Stack behalten
    }
}
