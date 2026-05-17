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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.app.Dialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.model.ModelConfig
import com.fzi.acousticscene.model.ModelInfo
import com.fzi.acousticscene.model.ModelInfoRegistry
import com.fzi.acousticscene.model.SceneClass
import com.fzi.acousticscene.util.ModelDisplayNameHelper
import com.fzi.acousticscene.util.ThemeHelper
import com.fzi.acousticscene.util.stripModelSuffix
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
        view.findViewById<ImageButton>(R.id.settingsBack).setOnClickListener {
            findNavController().popBackStack()
        }
        setupThemeToggle(view)
        setupModelList(view)
        setupSceneLegend(view)
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
        val devModelsContainer: LinearLayout = view.findViewById(R.id.devModelsContainer)
        val bestModel = ModelInfoRegistry.bestTestAccuracyModel()

        val models = listModelsInDir(ModelConfig.DEV_MODELS_DIR)
        devModelsContainer.removeAllViews()
        if (models.isEmpty()) {
            devModelsContainer.addView(createEmptyLabel())
        } else {
            models.forEach { fileName ->
                devModelsContainer.addView(
                    createModelRow(fileName, ModelConfig.DEV_MODELS_DIR, devModelsContainer, fileName == bestModel)
                )
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

    /**
     * Uncompressed size of an asset in bytes. `.pt` files are delivered through
     * the APK asset channel (may be compressed), so we read through the stream
     * to get the real decompressed size instead of relying on `openFd().length`.
     * Returns -1 on failure.
     */
    private fun assetSizeBytes(path: String): Long {
        return try {
            requireContext().assets.open(path).use { input ->
                var total = 0L
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                }
                total
            }
        } catch (_: Exception) {
            -1L
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 0) return "? KB"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(java.util.Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(java.util.Locale.US, "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(java.util.Locale.US, "%.2f GB", gb)
    }

    private fun createEmptyLabel(): TextView {
        return TextView(requireContext()).apply {
            text = "—"
            textSize = 14f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(0, 4, 0, 4)
        }
    }

    private fun createModelRow(fileName: String, dir: String, parentContainer: LinearLayout, isBest: Boolean = false): View {
        val ctx = requireContext()
        val displayName = ModelDisplayNameHelper.getDisplayName(ctx, fileName)
        val numClasses = ModelConfig.getClassCountForModel(fileName)
        val sizeStr = formatFileSize(assetSizeBytes("$dir/$fileName"))

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
            text = if (ModelDisplayNameHelper.hasCustomDisplayName(ctx, fileName)) {
                "${fileName.stripModelSuffix()} · $numClasses Classes · $sizeStr"
            } else {
                "$numClasses Classes · $sizeStr"
            }
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.accent_blue))
        }

        textContainer.addView(nameText)
        textContainer.addView(detailText)

        val infoButton = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_info)
            layoutParams = LinearLayout.LayoutParams(
                (32 * resources.displayMetrics.density).toInt(),
                (32 * resources.displayMetrics.density).toInt()
            ).apply { marginEnd = (4 * resources.displayMetrics.density).toInt() }
            setPadding(
                (4 * resources.displayMetrics.density).toInt(),
                (4 * resources.displayMetrics.density).toInt(),
                (4 * resources.displayMetrics.density).toInt(),
                (4 * resources.displayMetrics.density).toInt()
            )
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.model_info_title)
            if (isBest) {
                imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.accent_green)
                )
            }
            setOnClickListener {
                showModelInfoDialog(fileName, displayName)
            }
        }

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
        row.addView(infoButton)
        row.addView(editButton)
        return row
    }

    private fun showModelInfoDialog(fileName: String, displayName: String) {
        val ctx = context ?: return
        val info = ModelInfoRegistry.get(fileName)

        val dialog = Dialog(ctx, R.style.ModernDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val dp = resources.displayMetrics.density

        val card = MaterialCardView(ctx).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val m = (24 * dp).toInt()
                setMargins(m, m, m, m)
            }
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.dialog_surface))
            radius = 28 * dp
            cardElevation = 8 * dp
        }

        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val title = TextView(ctx).apply {
            text = getString(R.string.model_info_title)
            textSize = 20f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val topCloseBtn = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close)
            layoutParams = LinearLayout.LayoutParams(
                (32 * dp).toInt(),
                (32 * dp).toInt()
            )
            val p = (4 * dp).toInt()
            setPadding(p, p, p, p)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.close)
            setOnClickListener { dialog.dismiss() }
        }

        titleRow.addView(title)
        titleRow.addView(topCloseBtn)

        val nameLabel = TextView(ctx).apply {
            text = displayName
            textSize = 14f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }

        val fileLabel = TextView(ctx).apply {
            text = fileName.stripModelSuffix()
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setPadding(0, (2 * dp).toInt(), 0, (12 * dp).toInt())
        }

        outer.addView(titleRow)
        outer.addView(nameLabel)
        outer.addView(fileLabel)

        val scroll = androidx.core.widget.NestedScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isFillViewport = true
        }
        val scrollContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(scrollContent)

        if (info == null) {
            scrollContent.addView(TextView(ctx).apply {
                text = getString(R.string.model_info_no_data)
                textSize = 14f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            })
        } else {
            renderModelInfoSections(ctx, scrollContent, info)
        }

        outer.addView(scroll)

        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(0, (16 * dp).toInt(), 0, 0)
        }

        val closeBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.close)
            setTextColor(ContextCompat.getColor(ctx, R.color.accent_green))
            setOnClickListener { dialog.dismiss() }
        }
        buttonRow.addView(closeBtn)
        outer.addView(buttonRow)

        card.addView(outer)
        dialog.setContentView(card)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun renderModelInfoSections(
        ctx: android.content.Context,
        parent: LinearLayout,
        info: ModelInfo
    ) {
        val dp = resources.displayMetrics.density

        // Hyperparameters
        parent.addView(sectionHeader(ctx, getString(R.string.model_info_section_hyperparams)))
        info.runId?.let {
            parent.addView(metricRow(ctx, getString(R.string.model_info_run_id), it.toString()))
        }
        parent.addView(metricRow(ctx, getString(R.string.model_info_batch_size), info.batchSize.toString()))
        parent.addView(metricRow(ctx, getString(R.string.model_info_learning_rate), formatFloat(info.learningRate, 4)))
        parent.addView(metricRow(ctx, getString(R.string.model_info_weight_decay), formatFloat(info.weightDecay, 5)))
        parent.addView(metricRow(ctx, getString(R.string.model_info_dropout), formatFloat(info.dropout, 2)))
        parent.addView(metricRow(ctx, getString(R.string.model_info_label_smoothing), formatFloat(info.labelSmoothing, 2)))

        // Test metrics
        parent.addView(sectionHeader(ctx, getString(R.string.model_info_section_test)))
        parent.addView(metricRow(ctx, getString(R.string.model_info_val_accuracy), formatPct(info.valAccuracy)))
        parent.addView(metricRow(ctx, getString(R.string.model_info_test_accuracy), formatPct(info.testAccuracy)))

        // Augmentations
        info.augmentations?.takeIf { it.isNotEmpty() }?.let { augs ->
            parent.addView(sectionHeader(ctx, getString(R.string.model_info_section_augmentations)))
            parent.addView(TextView(ctx).apply {
                text = augs.joinToString(" · ")
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            })
        }

        // Training
        parent.addView(sectionHeader(ctx, getString(R.string.model_info_section_training)))
        parent.addView(metricRow(ctx, getString(R.string.model_info_best_epoch), "${info.bestEpoch} / ${info.totalEpochs}"))
        parent.addView(metricRow(ctx, getString(R.string.model_info_train_val_gap), formatSignedPct(info.trainValGap)))
        parent.addView(metricRow(ctx, getString(R.string.model_info_val_test_diff), formatSignedPct(info.valTestDiff)))

        // Per-class F1 with bars
        parent.addView(sectionHeader(ctx, getString(R.string.model_info_section_per_class_f1)))
        SceneClass.entries.forEach { scene ->
            val f1 = info.perClassF1.getOrNull(scene.index) ?: return@forEach
            parent.addView(perClassF1Row(ctx, scene, f1))
        }

        // Bottom padding so the last row doesn't hug the dialog edge
        parent.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (4 * dp).toInt()
            )
        })
    }

    private fun sectionHeader(ctx: android.content.Context, text: String): TextView {
        val dp = resources.displayMetrics.density
        return TextView(ctx).apply {
            this.text = text
            textSize = 14f
            setTextColor(ContextCompat.getColor(ctx, R.color.accent_blue))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, (16 * dp).toInt(), 0, (6 * dp).toInt())
        }
    }

    private fun metricRow(ctx: android.content.Context, label: String, value: String): View {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        }
        val labelView = TextView(ctx).apply {
            this.text = label
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueView = TextView(ctx).apply {
            this.text = value
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        row.addView(labelView)
        row.addView(valueView)
        return row
    }

    private fun perClassF1Row(ctx: android.content.Context, scene: SceneClass, f1: Double): View {
        val dp = resources.displayMetrics.density
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val emoji = TextView(ctx).apply {
            text = scene.emoji
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * dp).toInt() }
        }
        val label = TextView(ctx).apply {
            text = scene.labelShort
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val value = TextView(ctx).apply {
            text = formatPct(f1)
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
        }
        header.addView(emoji)
        header.addView(label)
        header.addView(value)

        val bar = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 10000
            progress = (f1 * 100).toInt().coerceIn(0, 10000)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (6 * dp).toInt()
            ).apply { topMargin = (4 * dp).toInt() }
            progressTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.accent_green)
            )
        }
        container.addView(header)
        container.addView(bar)
        return container
    }

    private fun formatPct(v: Double): String = String.format(java.util.Locale.US, "%.2f%%", v)

    private fun formatSignedPct(v: Double): String =
        if (v >= 0) String.format(java.util.Locale.US, "+%.2f%%", v) else String.format(java.util.Locale.US, "%.2f%%", v)

    private fun formatFloat(v: Double, decimals: Int): String {
        // Trim trailing zeros for readability while keeping precision.
        val raw = String.format(java.util.Locale.US, "%.${decimals}f", v)
        return if ('.' in raw) raw.trimEnd('0').trimEnd('.') else raw
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
            text = fileName.stripModelSuffix()
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, (16 * resources.displayMetrics.density).toInt())
        }

        val editText = EditText(ctx).apply {
            hint = getString(R.string.model_display_name_hint)
            setText(if (ModelDisplayNameHelper.hasCustomDisplayName(ctx, fileName)) currentDisplayName else "")
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
                detailText.text = if (ModelDisplayNameHelper.hasCustomDisplayName(ctx, fileName)) "${fileName.stripModelSuffix()} · $numClasses Classes" else "$numClasses Classes"
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

    private fun setupSceneLegend(view: View) {
        val ctx = requireContext()
        val container: LinearLayout = view.findViewById(R.id.sceneLegendContainer)

        SceneClass.entries.forEach { scene ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }

            val emojiText = TextView(ctx).apply {
                text = scene.emoji
                textSize = 20f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (16 * resources.displayMetrics.density).toInt() }
            }

            val labelText = TextView(ctx).apply {
                text = scene.label
                textSize = 14f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            row.addView(emojiText)
            row.addView(labelText)
            container.addView(row)
        }
    }
}
