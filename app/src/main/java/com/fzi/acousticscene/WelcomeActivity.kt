package com.fzi.acousticscene

import android.app.Dialog
import android.content.Intent
import android.content.res.AssetManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.fzi.acousticscene.model.ModelConfig
import com.fzi.acousticscene.util.ThemeHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Welcome Screen / Landing Page
 * Allows selection between User Mode and Development Mode
 */
class WelcomeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_MODEL_NAME = "model_name"
        const val EXTRA_NUM_CLASSES = "num_classes"
        const val EXTRA_IS_DEV_MODE = "is_dev_mode"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved theme BEFORE super.onCreate()
        ThemeHelper.applySavedTheme(this)

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

        setupThemeToggle()

        val userModeCard: MaterialCardView = findViewById(R.id.userModeCard)
        val devModeCard: MaterialCardView = findViewById(R.id.devModeCard)
        val viewHistoryButton: MaterialButton = findViewById(R.id.viewHistoryButton)

        // User Mode - Auto-load default model
        userModeCard.setOnClickListener {
            startMainActivity(ModelConfig.createUserMode())
        }

        // Development Mode - Show model selection dialog
        devModeCard.setOnClickListener {
            showModelSelectionDialog()
        }

        // Navigate to History
        viewHistoryButton.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        }
    }

    /**
     * Sets up the Light/Dark mode slide switch toggle
     */
    private fun setupThemeToggle() {
        val themeSwitch: MaterialSwitch = findViewById(R.id.themeSwitch)
        val iconLight: ImageView = findViewById(R.id.iconLightMode)
        val iconDark: ImageView = findViewById(R.id.iconDarkMode)

        // Switch ON = Dark Mode, OFF = Light Mode
        val isDark = ThemeHelper.isDarkMode(this)
        themeSwitch.isChecked = isDark
        updateThemeIcons(iconLight, iconDark, isDark)

        themeSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateThemeIcons(iconLight, iconDark, isChecked)
            ThemeHelper.setDarkMode(this, isChecked)
            // Activity will be recreated by AppCompatDelegate
        }
    }

    /**
     * Updates the opacity of sun/moon icons based on current theme
     */
    private fun updateThemeIcons(iconLight: ImageView, iconDark: ImageView, isDarkMode: Boolean) {
        iconLight.alpha = if (isDarkMode) 0.4f else 1.0f
        iconDark.alpha = if (isDarkMode) 1.0f else 0.4f
    }

    /**
     * Shows a modern dialog to select a model from dev_models/
     */
    private fun showModelSelectionDialog() {
        val models = listDevModels()

        if (models.isEmpty()) {
            Toast.makeText(this, R.string.no_models_found, Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(this, R.style.ModernDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_model_selection)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val modelListContainer = dialog.findViewById<LinearLayout>(R.id.modelListContainer)
        val emptyStateText = dialog.findViewById<TextView>(R.id.emptyStateText)
        val btnCancel = dialog.findViewById<MaterialButton>(R.id.btnCancel)

        if (models.isEmpty()) {
            emptyStateText.visibility = View.VISIBLE
            modelListContainer.visibility = View.GONE
        } else {
            emptyStateText.visibility = View.GONE
            modelListContainer.visibility = View.VISIBLE

            // Add model items
            models.forEach { modelFileName ->
                val modelItem = createModelItemView(modelFileName) {
                    dialog.dismiss()
                    val config = ModelConfig.createDevMode(modelFileName)
                    Toast.makeText(
                        this,
                        getString(R.string.model_info, config.modelName, config.numClasses),
                        Toast.LENGTH_SHORT
                    ).show()
                    startMainActivity(config)
                }
                modelListContainer.addView(modelItem)
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * Creates a view for a single model item in the selection dialog
     */
    private fun createModelItemView(modelFileName: String, onClick: () -> Unit): View {
        val numClasses = ModelConfig.getClassCountForModel(modelFileName)

        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12
            }
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.dialog_surface_light))
            radius = 16f * resources.displayMetrics.density
            cardElevation = 0f
            strokeWidth = 1
            strokeColor = ContextCompat.getColor(context, R.color.accent_blue)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 40, 48, 40)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        // Model icon
        val iconText = TextView(this).apply {
            text = "🧠"
            textSize = 24f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 32
            }
        }

        // Text container
        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Model name
        val nameText = TextView(this).apply {
            text = modelFileName
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }

        // Class count
        val classText = TextView(this).apply {
            text = getString(R.string.model_classes, numClasses)
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.accent_blue))
        }

        textContainer.addView(nameText)
        textContainer.addView(classText)

        content.addView(iconText)
        content.addView(textContainer)

        card.addView(content)
        return card
    }

    /**
     * Lists all .pt model files in dev_models/ directory
     */
    private fun listDevModels(): List<String> {
        return try {
            val assetManager: AssetManager = assets
            val files = assetManager.list(ModelConfig.DEV_MODELS_DIR) ?: emptyArray()
            files.filter { it.endsWith(".pt") }.sorted()
        } catch (e: Exception) {
            android.util.Log.e("WelcomeActivity", "Error listing dev models", e)
            emptyList()
        }
    }

    /**
     * Starts MainActivity with the selected model configuration
     */
    private fun startMainActivity(config: ModelConfig) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_MODEL_PATH, config.modelPath)
            putExtra(EXTRA_MODEL_NAME, config.modelName)
            putExtra(EXTRA_NUM_CLASSES, config.numClasses)
            putExtra(EXTRA_IS_DEV_MODE, config.isDevMode)
        }
        startActivity(intent)
        finish()
    }
}
