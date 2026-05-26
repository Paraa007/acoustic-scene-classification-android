package com.fzi.acousticscene.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.data.QuickstartRepository
import com.fzi.acousticscene.model.ModelConfig
import com.fzi.acousticscene.model.QuickstartSlot
import com.fzi.acousticscene.model.SessionMode
import com.google.android.material.button.MaterialButton

/**
 * Test mode home — the tester's entry point. Lists up to [QuickstartRepository.MAX_SLOTS]
 * quickstart slots. Filled slots launch the live recording immediately on tap
 * (no wizard, no confirmation). Empty slots are display-only (dashed border).
 *
 * Spec: no app logo or title on this screen — the chip + header carry it.
 * Slot descriptions are derived from the config (see [SessionConfig.slotDescription]).
 */
class TestWelcomeFragment : Fragment(R.layout.fragment_test_welcome) {

    private val viewModel: MainViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<LinearLayout>(R.id.testWelcomeBackButton).setOnClickListener {
            findNavController().popBackStack()
        }
        // System back-button mirrors the chevron — same destination (Mode Select).
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    findNavController().popBackStack()
                }
            }
        )
        view.findViewById<MaterialButton>(R.id.testWelcomeHistoryButton).setOnClickListener {
            val intent = Intent(requireContext(), HistoryActivity::class.java)
                .putExtra(HistoryActivity.EXTRA_MODE_FILTER, SessionMode.TEST.name)
            startActivity(intent)
        }
        renderSlots(view)
    }

    override fun onResume() {
        super.onResume()
        view?.let { renderSlots(it) }
    }

    private fun renderSlots(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.testWelcomeSlotsContainer)
        container.removeAllViews()

        val repo = QuickstartRepository.getInstance(requireContext())
        val slots = repo.getAll()
        val availableModels = listAvailableModels()

        if (slots.isEmpty()) {
            // 0 slots → single empty-state row instead of 5 dashed rows.
            container.addView(emptyStateRow())
            return
        }

        for (i in 1..QuickstartRepository.MAX_SLOTS) {
            val slot = slots.firstOrNull { it.index == i }
            if (slot != null) {
                val isPrimary = slot == slots.first()
                val executable = slot.config.isExecutable(availableModels)
                container.addView(filledSlotRow(slot, isPrimary, executable))
            } else {
                container.addView(emptySlotRow(i))
            }
        }
    }

    private fun emptyStateRow(): View {
        val ctx = requireContext()
        val tv = TextView(ctx).apply {
            text = getString(R.string.test_welcome_empty_state)
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(28f), dp(16f), dp(28f))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_slot_empty)
        }
        return tv
    }

    private fun filledSlotRow(
        slot: QuickstartSlot,
        isPrimary: Boolean,
        executable: Boolean
    ): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(13f), dp(16f), dp(13f))
            background = if (isPrimary) {
                solidBackground(ContextCompat.getColor(ctx, R.color.accent_green))
            } else {
                ContextCompat.getDrawable(ctx, R.drawable.bg_slot_secondary)
            }
            isClickable = executable
            isFocusable = executable
            alpha = if (executable) 1f else 0.45f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(9f)
            layoutParams = lp
        }
        val textColorPrimary = if (isPrimary) {
            Color.WHITE
        } else ContextCompat.getColor(ctx, R.color.text_primary)
        val textColorSecondary = if (isPrimary) {
            Color.argb(220, 255, 255, 255)
        } else ContextCompat.getColor(ctx, R.color.text_secondary)

        // 01/02/… numeral
        row.addView(TextView(ctx).apply {
            text = "%02d".format(slot.index)
            textSize = 12.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(if (isPrimary) textColorSecondary
                else ContextCompat.getColor(ctx, R.color.text_faint))
            layoutParams = LinearLayout.LayoutParams(
                dp(28f),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        val textColumn = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        textColumn.addView(TextView(ctx).apply {
            text = getString(R.string.test_welcome_slot_label, slot.index)
            textSize = 14.5f
            setTextColor(textColorPrimary)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        textColumn.addView(TextView(ctx).apply {
            text = slot.config.slotDescription()
            textSize = 10.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(textColorSecondary)
            setPadding(0, dp(2f), 0, 0)
        })
        row.addView(textColumn)

        // chevron
        val chevron = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_arrow_back)
            rotation = 180f
            val color = if (isPrimary) textColorPrimary
                else ContextCompat.getColor(ctx, R.color.text_secondary)
            setColorFilter(color)
            layoutParams = LinearLayout.LayoutParams(dp(14f), dp(14f))
        }
        row.addView(chevron)

        row.setOnClickListener {
            if (!executable) {
                Toast.makeText(
                    requireContext(),
                    R.string.welcome_no_models,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            launchSlot(slot)
        }
        row.setOnLongClickListener {
            confirmDeleteSlot(slot)
            true
        }
        return row
    }

    private fun emptySlotRow(index: Int): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_slot_empty)
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(9f)
            layoutParams = lp
        }
        row.addView(TextView(ctx).apply {
            text = "%02d".format(index)
            textSize = 12.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(ctx, R.color.text_faint))
            layoutParams = LinearLayout.LayoutParams(
                dp(28f),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        row.addView(TextView(ctx).apply {
            text = getString(R.string.test_welcome_slot_empty)
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_faint))
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        })
        return row
    }

    private fun launchSlot(slot: QuickstartSlot) {
        // Stage the config on the ViewModel so LiveRecording reads it via the
        // shared uiState. applySessionConfig flips appState to Loading and the
        // recording fragment auto-starts the session once the models load.
        viewModel.applySessionConfig(slot.config)
        findNavController().navigate(R.id.action_test_welcome_to_live)
    }

    private fun confirmDeleteSlot(slot: QuickstartSlot) {
        ModernDialogHelper.showDeleteDialog(
            context = requireContext(),
            title = getString(R.string.test_welcome_slot_delete),
            message = getString(R.string.test_welcome_slot_label, slot.index),
            deleteText = getString(R.string.delete),
            cancelText = getString(R.string.cancel),
            onDelete = {
                QuickstartRepository.getInstance(requireContext()).deleteSlot(slot.index)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.test_welcome_slot_deleted, slot.index),
                    Toast.LENGTH_SHORT
                ).show()
                view?.let { renderSlots(it) }
            }
        )
    }

    private fun listAvailableModels(): List<String> = try {
        requireContext().assets.list(ModelConfig.DEV_MODELS_DIR)
            ?.filter { it.endsWith(".pt") }
            ?.sorted()
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun solidBackground(color: Int): android.graphics.drawable.Drawable {
        val shape = android.graphics.drawable.GradientDrawable()
        shape.cornerRadius = dp(14f).toFloat()
        shape.setColor(color)
        return shape
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
