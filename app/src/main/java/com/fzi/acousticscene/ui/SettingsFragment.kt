package com.fzi.acousticscene.ui

import android.content.res.AssetManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.app.Dialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.fzi.acousticscene.R
import com.fzi.acousticscene.model.ModelConfig
import com.fzi.acousticscene.util.ModelDisplayNameHelper
import com.fzi.acousticscene.util.ThemeHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * SettingsFragment - App settings including dark mode toggle and model management
 */
class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupThemeToggle(view)
        setupModelList(view)
        setupVersionInfo(view)
    }

    private fun setupThemeToggle(view: View) {
        val themeSwitch: MaterialSwitch = view.findViewById(R.id.themeSwitch)
        val iconLight: ImageView = view.findViewById(R.id.iconLightMode)
        val iconDark: ImageView = view.findViewById(R.id.iconDarkMode)
        val themeLabel: TextView = view.findViewById(R.id.themeLabel)

        val isDark = ThemeHelper.isDarkMode(requireContext())
        themeSwitch.isChecked = isDark
        updateThemeIcons(iconLight, iconDark, isDark)
        themeLabel.text = if (isDark) getString(R.string.dark_mode) else getString(R.string.light_mode)

        themeSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateThemeIcons(iconLight, iconDark, isChecked)
            themeLabel.text = if (isChecked) getString(R.string.dark_mode) else getString(R.string.light_mode)
            ThemeHelper.setDarkMode(requireContext(), isChecked)
        }
    }

    private fun updateThemeIcons(iconLight: ImageView, iconDark: ImageView, isDarkMode: Boolean) {
        iconLight.alpha = if (isDarkMode) 0.4f else 1.0f
        iconDark.alpha = if (isDarkMode) 1.0f else 0.4f
    }

    private fun setupModelList(view: View) {
        val userModelContainer: LinearLayout = view.findViewById(R.id.userModelContainer)
        val devModelsContainer: LinearLayout = view.findViewById(R.id.devModelsContainer)

        // User models
        val userModels = listModelsInDir(ModelConfig.USER_MODEL_DIR)
        userModelContainer.removeAllViews()
        if (userModels.isEmpty()) {
            userModelContainer.addView(createEmptyLabel())
        } else {
            userModels.forEach { fileName ->
                userModelContainer.addView(createModelRow(fileName, userModelContainer))
            }
        }

        // Dev models
        val devModels = listModelsInDir(ModelConfig.DEV_MODELS_DIR)
        devModelsContainer.removeAllViews()
        if (devModels.isEmpty()) {
            devModelsContainer.addView(createEmptyLabel())
        } else {
            devModels.forEach { fileName ->
                devModelsContainer.addView(createModelRow(fileName, devModelsContainer))
            }
        }
    }

    private fun listModelsInDir(dir: String): List<String> {
        return try {
            val assetManager: AssetManager = requireContext().assets
            val files = assetManager.list(dir) ?: emptyArray()
            files.filter { it.endsWith(".pt") }.sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun createEmptyLabel(): TextView {
        return TextView(requireContext()).apply {
            text = "—"
            textSize = 14f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(0, 4, 0, 4)
        }
    }

    private fun createModelRow(fileName: String, parentContainer: LinearLayout): View {
        val ctx = requireContext()
        val displayName = ModelDisplayNameHelper.getDisplayName(ctx, fileName)
        val numClasses = ModelConfig.getClassCountForModel(fileName)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
        }

        val textContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameText = TextView(ctx).apply {
            text = displayName
            textSize = 15f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
        }

        val detailText = TextView(ctx).apply {
            text = if (displayName != fileName) "$fileName · $numClasses Classes" else "$numClasses Classes"
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.accent_blue))
        }

        textContainer.addView(nameText)
        textContainer.addView(detailText)

        val editButton = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_edit)
            layoutParams = LinearLayout.LayoutParams(
                (32 * resources.displayMetrics.density).toInt(),
                (32 * resources.displayMetrics.density).toInt()
            )
            setPadding(
                (4 * resources.displayMetrics.density).toInt(),
                (4 * resources.displayMetrics.density).toInt(),
                (4 * resources.displayMetrics.density).toInt(),
                (4 * resources.displayMetrics.density).toInt()
            )
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.rename_model)
            setOnClickListener {
                showRenameDialog(fileName, nameText, detailText, parentContainer)
            }
        }

        row.addView(textContainer)
        row.addView(editButton)
        return row
    }

    private fun showRenameDialog(
        fileName: String,
        nameText: TextView,
        detailText: TextView,
        parentContainer: LinearLayout
    ) {
        val ctx = context ?: return
        val currentDisplayName = ModelDisplayNameHelper.getDisplayName(ctx, fileName)

        val dialog = Dialog(ctx, R.style.ModernDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Build dialog content programmatically
        val card = MaterialCardView(ctx).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = (24 * resources.displayMetrics.density).toInt()
                setMargins(margin, margin, margin, margin)
            }
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.dialog_surface))
            radius = 28 * resources.displayMetrics.density
            cardElevation = 8 * resources.displayMetrics.density
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(ctx).apply {
            text = getString(R.string.rename_model)
            textSize = 20f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val fileLabel = TextView(ctx).apply {
            text = fileName
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, (16 * resources.displayMetrics.density).toInt())
        }

        val editText = EditText(ctx).apply {
            hint = getString(R.string.model_display_name_hint)
            setText(if (currentDisplayName != fileName) currentDisplayName else "")
            textSize = 16f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setPadding(
                (16 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt()
            )
            background = ContextCompat.getDrawable(ctx, android.R.drawable.edit_text)
        }

        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            val topMargin = (20 * resources.displayMetrics.density).toInt()
            setPadding(0, topMargin, 0, 0)
        }

        val cancelBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.cancel)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setOnClickListener { dialog.dismiss() }
        }

        val saveBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.save)
            setTextColor(ContextCompat.getColor(ctx, R.color.accent_green))
            setOnClickListener {
                val newName = editText.text.toString().trim()
                if (newName.isBlank()) {
                    ModelDisplayNameHelper.clearDisplayName(ctx, fileName)
                } else {
                    ModelDisplayNameHelper.setDisplayName(ctx, fileName, newName)
                }
                // Update the row in place
                val updatedDisplayName = ModelDisplayNameHelper.getDisplayName(ctx, fileName)
                nameText.text = updatedDisplayName
                val numClasses = ModelConfig.getClassCountForModel(fileName)
                detailText.text = if (updatedDisplayName != fileName) "$fileName · $numClasses Classes" else "$numClasses Classes"
                Toast.makeText(ctx, R.string.model_name_saved, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        buttonRow.addView(cancelBtn)
        buttonRow.addView(saveBtn)

        content.addView(title)
        content.addView(fileLabel)
        content.addView(editText)
        content.addView(buttonRow)
        card.addView(content)

        dialog.setContentView(card)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun setupVersionInfo(view: View) {
        val versionText: TextView = view.findViewById(R.id.versionText)
        try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            versionText.text = pInfo.versionName
        } catch (_: Exception) {
            versionText.text = "1.0"
        }
    }
}
