package com.fzi.acousticscene.ui.common

import android.app.Dialog
import android.content.res.AssetManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.model.ModelConfig
import com.fzi.acousticscene.model.ModelInfo
import com.fzi.acousticscene.model.ModelInfoRegistry
import com.fzi.acousticscene.model.ModelMetadataRegistry
import com.fzi.acousticscene.model.SceneClass
import com.fzi.acousticscene.util.ModelDisplayNameHelper
import com.fzi.acousticscene.util.SceneClassColors
import com.fzi.acousticscene.util.ThemeHelper
import com.fzi.acousticscene.util.stripModelSuffix
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * v2 minimal Settings: segmented Light/Dark theme toggle, Models list as tile
 * rows (mono filename + clip-length badge + chevron + edit/info entry points),
 * About + 2-column Scene Legend grid.
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
        // Hardware back mirrors the chevron so the user can't get stuck on Settings.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    findNavController().popBackStack()
                }
            }
        )
        setupThemeToggle(view)
        setupModelList(view)
        setupSceneLegend(view)
    }

    private fun setupThemeToggle(view: View) {
        val ctx = requireContext()
        val lightOpt: LinearLayout = view.findViewById(R.id.themeLightOption)
        val darkOpt: LinearLayout = view.findViewById(R.id.themeDarkOption)
        val lightLabel: TextView = view.findViewById(R.id.themeLightLabel)
        val darkLabel: TextView = view.findViewById(R.id.themeDarkLabel)
        val status: TextView = view.findViewById(R.id.themeStatus)

        fun render(isDark: Boolean) {
            // Active pill: accent_green bg + accent_ink text; inactive: transparent bg + faint text.
            lightOpt.background = if (!isDark)
                ContextCompat.getDrawable(ctx, R.drawable.bg_segmented_active) else null
            darkOpt.background = if (isDark)
                ContextCompat.getDrawable(ctx, R.drawable.bg_segmented_active) else null
            lightLabel.setTextColor(ContextCompat.getColor(
                ctx,
                if (!isDark) R.color.accent_ink else R.color.text_faint
            ))
            darkLabel.setTextColor(ContextCompat.getColor(
                ctx,
                if (isDark) R.color.accent_ink else R.color.text_faint
            ))
            status.text = getString(
                if (isDark) R.string.settings_theme_active_dark
                else R.string.settings_theme_active_light
            )
        }

        render(ThemeHelper.isDarkMode(ctx))

        lightOpt.setOnClickListener {
            if (ThemeHelper.isDarkMode(ctx)) {
                render(false)
                ThemeHelper.setDarkMode(ctx, false)
            }
        }
        darkOpt.setOnClickListener {
            if (!ThemeHelper.isDarkMode(ctx)) {
                render(true)
                ThemeHelper.setDarkMode(ctx, true)
            }
        }
    }

    private fun setupModelList(view: View) {
        val ctx = requireContext()
        val devModelsContainer: LinearLayout = view.findViewById(R.id.devModelsContainer)

        val models = listModelsInDir(ModelConfig.DEV_MODELS_DIR)
        devModelsContainer.removeAllViews()
        if (models.isEmpty()) {
            devModelsContainer.addView(createEmptyLabel())
            return
        }
        models.forEachIndexed { idx, fileName ->
            devModelsContainer.addView(
                createModelRow(fileName, ModelConfig.DEV_MODELS_DIR, devModelsContainer)
            )
            if (idx != models.lastIndex) {
                devModelsContainer.addView(View(ctx).apply {
                    setBackgroundColor(ContextCompat.getColor(ctx, R.color.hairline))
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    )
                    lp.marginStart = dp(9)
                    lp.marginEnd = dp(9)
                    layoutParams = lp
                })
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
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(dp(9), dp(11), dp(9), dp(11))
        }
    }

    /**
     * v2 model row: monospace filename + clip-length badge (1s / 10s) + chevron.
     * Below it sits a compact test-accuracy line — present when metadata carries
     * a value, otherwise a red "TEST ACC MISSING" badge with the same dashed-track
     * red treatment as the wizard's Select-Models step.
     *
     * Tap opens the info dialog (existing behavior); long-press opens the rename
     * dialog. The whole card is the touch target.
     */
    private fun createModelRow(fileName: String, dir: String, parentContainer: LinearLayout): View {
        val ctx = requireContext()
        val displayName = ModelDisplayNameHelper.getDisplayName(ctx, fileName)
        val testAccuracy = ModelMetadataRegistry.get(ctx, fileName)?.testAccuracy

        // Vertical container — top row stays "filename + badge + chevron",
        // bottom row carries the test-acc info.
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(11), dp(9), dp(11))
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(
                ctx,
                android.R.color.transparent
            )
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val nameView = TextView(ctx).apply {
            text = fileName.stripModelSuffix()
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(nameView)

        val clipLen = clipLengthBadgeText(fileName)
        if (clipLen != null) {
            row.addView(TextView(ctx).apply {
                text = clipLen
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_meta_pill)
                setPadding(dp(7), dp(3), dp(7), dp(3))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = dp(7)
                layoutParams = lp
            })
        }

        row.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_arrow_back)
            rotation = 180f
            setColorFilter(ContextCompat.getColor(ctx, R.color.text_faint))
            layoutParams = LinearLayout.LayoutParams(dp(13), dp(13))
        })

        card.addView(row)
        card.addView(buildTestAccBlock(ctx, testAccuracy))

        card.setOnClickListener {
            showModelInfoDialog(fileName, displayName)
        }
        card.setOnLongClickListener {
            showRenameDialog(fileName, nameView, parentContainer)
            true
        }
        return card
    }

    /**
     * Test-accuracy sub-block under each model row. Mirrors the wizard's
     * Select-Models step: present accuracy as label + green percentage + filled
     * progress track; missing accuracy as a red badge + red hint line + dashed
     * red empty track.
     */
    private fun buildTestAccBlock(
        ctx: android.content.Context,
        testAccuracy: Double?
    ): View {
        val block = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            layoutParams = lp
        }

        val accent = ContextCompat.getColor(ctx, R.color.accent_green)
        val accentRed = ContextCompat.getColor(ctx, R.color.accent_red)

        if (testAccuracy != null) {
            val labelRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            labelRow.addView(TextView(ctx).apply {
                text = getString(R.string.test_acc_label)
                textSize = 10f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                setTypeface(typeface, Typeface.BOLD)
            })
            labelRow.addView(TextView(ctx).apply {
                text = "%.1f%%".format(testAccuracy * 100)
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(accent)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginStart = dp(6)
                layoutParams = lp
            })
            block.addView(labelRow)

            // Filled progress track
            val track = LinearLayout(ctx).apply {
                background = pillShape(
                    ContextCompat.getColor(ctx, R.color.surface_variant),
                    dp(3).toFloat()
                )
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(5)
                )
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            val pct = testAccuracy.toFloat().coerceIn(0f, 1f)
            track.addView(View(ctx).apply {
                background = pillShape(accent, dp(3).toFloat())
                layoutParams = LinearLayout.LayoutParams(0, dp(5), pct)
            })
            track.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(5), 1f - pct)
            })
            block.addView(track)
        } else {
            block.addView(TextView(ctx).apply {
                text = getString(R.string.test_acc_missing)
                isAllCaps = true
                textSize = 10f
                letterSpacing = 0.04f
                setTextColor(accentRed)
                setTypeface(typeface, Typeface.BOLD)
            })
            block.addView(TextView(ctx).apply {
                text = getString(R.string.test_acc_missing_hint)
                textSize = 10.5f
                setTextColor(accentRed)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = dp(2)
                layoutParams = lp
            })
            // Empty dashed track in red
            block.addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(ContextCompat.getColor(ctx, R.color.surface_variant))
                    cornerRadius = dp(3).toFloat()
                    setStroke(dp(1), accentRed, dp(4).toFloat(), dp(3).toFloat())
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(5)
                )
                lp.topMargin = dp(8)
                layoutParams = lp
            })
        }
        return block
    }

    private fun pillShape(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }
    }

    private fun clipLengthBadgeText(fileName: String): String? = when {
        fileName.contains("_1s_") || fileName.contains("_1s.") -> "1s"
        fileName.contains("_10s_") || fileName.contains("_10s.") -> "10s"
        else -> null
    }

    private fun showModelInfoDialog(fileName: String, displayName: String) {
        val ctx = context ?: return
        val info = ModelInfoRegistry.get(fileName)

        val dialog = Dialog(ctx, R.style.ModernDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val dpF = resources.displayMetrics.density

        val card = MaterialCardView(ctx).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val m = (24 * dpF).toInt()
                setMargins(m, m, m, m)
            }
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.dialog_surface))
            radius = 22 * dpF
            cardElevation = 8 * dpF
            strokeWidth = (1 * dpF).toInt()
            strokeColor = ContextCompat.getColor(ctx, R.color.border_subtle)
        }

        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (22 * dpF).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(ctx).apply {
            text = getString(R.string.model_info_title)
            textSize = 18f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val topCloseBtn = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close)
            layoutParams = LinearLayout.LayoutParams(
                (32 * dpF).toInt(),
                (32 * dpF).toInt()
            )
            val p = (4 * dpF).toInt()
            setPadding(p, p, p, p)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.close)
            setOnClickListener { dialog.dismiss() }
        }

        titleRow.addView(title)
        titleRow.addView(topCloseBtn)

        val fileLabel = TextView(ctx).apply {
            text = fileName.stripModelSuffix()
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setPadding(0, (6 * dpF).toInt(), 0, (12 * dpF).toInt())
        }

        outer.addView(titleRow)
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
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                setPadding(0, (8 * dpF).toInt(), 0, (8 * dpF).toInt())
            })
        } else {
            renderModelInfoSections(ctx, scrollContent, info)
        }

        outer.addView(scroll)

        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (16 * dpF).toInt(), 0, 0)
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
        val dpF = resources.displayMetrics.density

        parent.addView(sectionHeader(ctx, getString(R.string.model_info_section_hyperparams)))
        info.runId?.let {
            parent.addView(metricRow(ctx, getString(R.string.model_info_run_id), it.toString()))
        }
        parent.addView(metricRow(ctx, getString(R.string.model_info_batch_size), info.batchSize.toString()))
        parent.addView(metricRow(ctx, getString(R.string.model_info_learning_rate), formatFloat(info.learningRate, 4)))
        parent.addView(metricRow(ctx, getString(R.string.model_info_weight_decay), formatFloat(info.weightDecay, 5)))
        parent.addView(metricRow(ctx, getString(R.string.model_info_dropout), formatFloat(info.dropout, 2)))
        parent.addView(metricRow(ctx, getString(R.string.model_info_label_smoothing), formatFloat(info.labelSmoothing, 2)))

        parent.addView(sectionHeader(ctx, getString(R.string.model_info_section_test)))
        parent.addView(metricRow(ctx, getString(R.string.model_info_val_accuracy), formatPct(info.valAccuracy)))
        parent.addView(metricRow(ctx, getString(R.string.model_info_test_accuracy), formatPct(info.testAccuracy)))

        info.augmentations?.takeIf { it.isNotEmpty() }?.let { augs ->
            parent.addView(sectionHeader(ctx, getString(R.string.model_info_section_augmentations)))
            parent.addView(TextView(ctx).apply {
                text = augs.joinToString(" · ")
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                setPadding(0, (4 * dpF).toInt(), 0, (4 * dpF).toInt())
            })
        }

        parent.addView(sectionHeader(ctx, getString(R.string.model_info_section_training)))
        parent.addView(metricRow(ctx, getString(R.string.model_info_best_epoch), "${info.bestEpoch} / ${info.totalEpochs}"))
        parent.addView(metricRow(ctx, getString(R.string.model_info_train_val_gap), formatSignedPct(info.trainValGap)))
        parent.addView(metricRow(ctx, getString(R.string.model_info_val_test_diff), formatSignedPct(info.valTestDiff)))

        parent.addView(sectionHeader(ctx, getString(R.string.model_info_section_per_class_f1)))
        SceneClass.entries.forEach { scene ->
            val f1 = info.perClassF1.getOrNull(scene.index) ?: return@forEach
            parent.addView(perClassF1Row(ctx, scene, f1))
        }

        parent.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (4 * dpF).toInt()
            )
        })
    }

    private fun sectionHeader(ctx: android.content.Context, text: String): TextView {
        val dpF = resources.displayMetrics.density
        return TextView(ctx).apply {
            this.text = text
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(ctx, R.color.text_faint))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.15f
            isAllCaps = true
            setPadding(0, (16 * dpF).toInt(), 0, (6 * dpF).toInt())
        }
    }

    private fun metricRow(ctx: android.content.Context, label: String, value: String): View {
        val dpF = resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (4 * dpF).toInt(), 0, (4 * dpF).toInt())
        }
        val labelView = TextView(ctx).apply {
            this.text = label
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueView = TextView(ctx).apply {
            this.text = value
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setTypeface(typeface, Typeface.BOLD)
        }
        row.addView(labelView)
        row.addView(valueView)
        return row
    }

    private fun perClassF1Row(ctx: android.content.Context, scene: SceneClass, f1: Double): View {
        val dpF = resources.displayMetrics.density
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (6 * dpF).toInt(), 0, (6 * dpF).toInt())
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val emoji = TextView(ctx).apply {
            text = scene.emoji
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * dpF).toInt() }
        }
        val label = TextView(ctx).apply {
            text = scene.labelShort
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val value = TextView(ctx).apply {
            text = formatPct(f1)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
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
                (6 * dpF).toInt()
            ).apply { topMargin = (4 * dpF).toInt() }
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
        val raw = String.format(java.util.Locale.US, "%.${decimals}f", v)
        return if ('.' in raw) raw.trimEnd('0').trimEnd('.') else raw
    }

    private fun showRenameDialog(
        fileName: String,
        nameText: TextView,
        parentContainer: LinearLayout
    ) {
        val ctx = context ?: return
        val currentDisplayName = ModelDisplayNameHelper.getDisplayName(ctx, fileName)
        val dpF = resources.displayMetrics.density

        val dialog = Dialog(ctx, R.style.ModernDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val card = MaterialCardView(ctx).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = (24 * dpF).toInt()
                setMargins(margin, margin, margin, margin)
            }
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.dialog_surface))
            radius = 22 * dpF
            cardElevation = 8 * dpF
            strokeWidth = (1 * dpF).toInt()
            strokeColor = ContextCompat.getColor(ctx, R.color.border_subtle)
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (22 * dpF).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(ctx).apply {
            text = getString(R.string.rename_model)
            textSize = 18f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setTypeface(null, Typeface.BOLD)
        }

        val fileLabel = TextView(ctx).apply {
            text = fileName.stripModelSuffix()
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setPadding(0, (4 * dpF).toInt(), 0, (16 * dpF).toInt())
        }

        val editText = EditText(ctx).apply {
            hint = getString(R.string.model_display_name_hint)
            setText(if (ModelDisplayNameHelper.hasCustomDisplayName(ctx, fileName)) currentDisplayName else "")
            textSize = 14f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setPadding(
                (16 * dpF).toInt(),
                (12 * dpF).toInt(),
                (16 * dpF).toInt(),
                (12 * dpF).toInt()
            )
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_password_field)
        }

        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val topMargin = (20 * dpF).toInt()
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
                nameText.text = fileName.stripModelSuffix()
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
        container.removeAllViews()

        // 2 columns x 5 rows = 9 entries (last row has 1 entry in the right column empty).
        val classes = SceneClass.entries
        val rows = (classes.size + 1) / 2

        for (r in 0 until rows) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                if (r > 0) lp.topMargin = dp(9)
                layoutParams = lp
            }
            val leftIdx = r * 2
            val rightIdx = leftIdx + 1
            row.addView(legendCell(ctx, classes[leftIdx]))
            if (rightIdx < classes.size) {
                row.addView(legendCell(ctx, classes[rightIdx]))
            } else {
                row.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            container.addView(row)
        }
    }

    private fun legendCell(ctx: android.content.Context, scene: SceneClass): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val dot = View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(SceneClassColors.color(ctx, scene))
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
        }
        row.addView(dot)
        row.addView(TextView(ctx).apply {
            text = scene.emoji
            textSize = 11f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(7)
            layoutParams = lp
        })
        row.addView(TextView(ctx).apply {
            text = scene.labelShort
            textSize = 11f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(7)
            layoutParams = lp
        })
        return row
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
