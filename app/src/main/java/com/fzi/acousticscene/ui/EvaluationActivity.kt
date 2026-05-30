package com.fzi.acousticscene.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fzi.acousticscene.R
import com.fzi.acousticscene.data.PredictionRepository
import com.fzi.acousticscene.model.SceneClass
import com.fzi.acousticscene.util.SceneClassColors
import com.google.android.material.button.MaterialButton

/**
 * v2 evaluation surface. The user picks the actual scene from a vertical list
 * of tile-styled rows or types a free-form note under "Other". Auto-skips after
 * [EVALUATION_TIMEOUT_MS]. The selected class is written back to the
 * PredictionRepository as the user's ground-truth tag; the note field, when
 * filled, is stored alongside.
 */
class EvaluationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PREDICTION_ID = "prediction_id"
        const val EXTRA_MODEL_PREDICTED_CLASS = "model_predicted_class"
        const val EVALUATION_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes

        private const val STATE_PREDICTION_ID = "state_prediction_id"
        private const val STATE_DEADLINE = "state_deadline"
        private const val STATE_SELECTED_INDEX = "state_selected_index"
        private const val STATE_OTHER_ACTIVE = "state_other_active"
        private const val STATE_NOTE = "state_note"
        private const val STATE_REVEALED = "state_revealed"
        private const val NOTE_MAX_LENGTH = 240
    }

    private var predictionId: Long = -1
    private var countDownTimer: CountDownTimer? = null
    private var deadlineElapsed: Long = 0L

    private var selectedScene: SceneClass? = null
    private var otherActive: Boolean = false
    private var modelClass: SceneClass? = null
    private var revealed: Boolean = false
    private var submitButton: MaterialButton? = null

    private val rowsByScene: MutableMap<SceneClass, RowViews> = mutableMapOf()

    private data class RowViews(
        val container: LinearLayout,
        val nameView: TextView,
        val circleView: View
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_evaluation)

        // Back row (chevron + "Back" label) finishes the activity without
        // submitting an evaluation — same effect as the Skip button. The
        // hardware/system back key already does this via the default Activity
        // handling.
        findViewById<LinearLayout>(R.id.evaluationBackRow).setOnClickListener { finish() }

        predictionId = savedInstanceState?.getLong(STATE_PREDICTION_ID, -1)
            ?: intent.getLongExtra(EXTRA_PREDICTION_ID, -1)
        if (predictionId == -1L) {
            finish()
            return
        }

        val modelClassStr = intent.getStringExtra(EXTRA_MODEL_PREDICTED_CLASS)
        modelClass = modelClassStr?.let { name ->
            try { SceneClass.valueOf(name) } catch (_: Exception) { null }
        }
        // Anti-anchoring: the predicted tile stays hidden (layout default) until
        // the user submits their own pick. See reveal().

        buildSceneList()

        val otherOption: LinearLayout = findViewById(R.id.otherOption)
        val otherCircle: View = findViewById(R.id.otherCircle)
        val noteTile: LinearLayout = findViewById(R.id.noteTile)
        val noteInput: EditText = findViewById(R.id.noteInput)
        val noteCounter: TextView = findViewById(R.id.noteCounter)
        noteCounter.text = formatCounter(0)

        otherOption.setOnClickListener {
            if (revealed) return@setOnClickListener
            otherActive = !otherActive
            renderOtherSelection(otherOption, otherCircle, noteTile)
            if (otherActive) {
                selectedScene = null
                rowsByScene.forEach { (_, r) -> renderRowSelection(r, false) }
                noteInput.requestFocus()
            }
        }

        noteInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                noteCounter.text = formatCounter(s?.length ?: 0)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Restore state
        savedInstanceState?.let { state ->
            val selectedIdx = state.getInt(STATE_SELECTED_INDEX, -1)
            if (selectedIdx >= 0) {
                val scene = SceneClass.fromIndex(selectedIdx)
                if (scene != null) {
                    selectedScene = scene
                    rowsByScene[scene]?.let { renderRowSelection(it, true) }
                }
            }
            otherActive = state.getBoolean(STATE_OTHER_ACTIVE, false)
            state.getString(STATE_NOTE)?.let { noteInput.setText(it) }
            renderOtherSelection(otherOption, otherCircle, noteTile)
        }

        // Submit / reveal. The button gates the model reveal: the first tap needs
        // a picked class, persists it, then reveals the model guess plus a
        // match/differ comparison. The second tap (now "Done") closes.
        val submit = findViewById<MaterialButton>(R.id.btnSubmit)
        submitButton = submit
        submit.text = getString(R.string.eval_reveal)
        submit.setOnClickListener {
            if (revealed) {
                finish()
                return@setOnClickListener
            }
            val sel = selectedScene
            if (sel == null && !otherActive) {
                // The whole point is to commit to a guess before seeing the
                // model, so ignore the tap until the user picks something.
                return@setOnClickListener
            }
            if (sel != null) {
                PredictionRepository.getInstance(this).updatePredictionEvaluation(predictionId, sel)
            }
            reveal()
        }

        // Skip
        findViewById<MaterialButton>(R.id.btnSkip).setOnClickListener { finish() }

        // Countdown timer (survives rotation)
        deadlineElapsed = savedInstanceState?.getLong(STATE_DEADLINE, 0L) ?: 0L
        if (deadlineElapsed == 0L) {
            deadlineElapsed = SystemClock.elapsedRealtime() + EVALUATION_TIMEOUT_MS
        }
        val remainingMs = (deadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        if (remainingMs == 0L) {
            finish()
            return
        }

        val timerText: TextView = findViewById(R.id.timerText)
        countDownTimer = object : CountDownTimer(remainingMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val minutes = (millisUntilFinished / 1000) / 60
                val seconds = (millisUntilFinished / 1000) % 60
                timerText.text = getString(R.string.evaluation_timeout, minutes.toInt(), seconds.toInt())
            }
            override fun onFinish() { finish() }
        }.start()

        // Restore the revealed state after a rotation: re-show the model tile and
        // stop the auto-skip timer we just started.
        if (savedInstanceState?.getBoolean(STATE_REVEALED, false) == true) {
            reveal()
        }
    }

    private fun renderModelPrediction(scene: SceneClass?) {
        val emoji: TextView = findViewById(R.id.predictedEmoji)
        val name: TextView = findViewById(R.id.predictedName)
        val pct: TextView = findViewById(R.id.predictedPct)
        if (scene != null) {
            emoji.text = scene.emoji
            name.text = scene.labelShort
            name.setTextColor(SceneClassColors.color(this, scene))
            val sel = selectedScene
            when {
                sel == null -> pct.text = ""
                sel == scene -> {
                    pct.text = getString(R.string.eval_match)
                    pct.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                }
                else -> {
                    pct.text = getString(R.string.eval_differs)
                    pct.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                }
            }
        } else {
            emoji.text = ""
            name.text = "N/A"
            name.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            pct.text = ""
        }
    }

    /**
     * Reveals the model's guess after the user has committed to their own pick.
     * Shows the predicted tile, sets a match/differ comparison, stops the
     * auto-skip timer, and turns the primary button into a plain close action.
     */
    private fun reveal() {
        revealed = true
        countDownTimer?.cancel()
        renderModelPrediction(modelClass)
        findViewById<View>(R.id.predictedTile).visibility = View.VISIBLE
        submitButton?.text = getString(R.string.eval_done)
    }

    private fun buildSceneList() {
        val container: LinearLayout = findViewById(R.id.sceneClassContainer)
        container.removeAllViews()
        rowsByScene.clear()

        SceneClass.entries.forEach { scene ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(this@EvaluationActivity, R.drawable.bg_tile)
                setPadding(dp(13), dp(11), dp(13), dp(11))
                isClickable = true
                isFocusable = true
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(7)
                layoutParams = lp
            }

            row.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(SceneClassColors.color(this@EvaluationActivity, scene))
                }
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
            })

            row.addView(TextView(this).apply {
                text = scene.emoji
                textSize = 14f
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.marginStart = dp(11)
                layoutParams = lp
            })

            val name = TextView(this).apply {
                text = scene.labelShort
                textSize = 12.5f
                setTextColor(ContextCompat.getColor(this@EvaluationActivity, R.color.text_secondary))
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginStart = dp(11)
                layoutParams = lp
            }
            row.addView(name)

            val circle = View(this).apply {
                background = ContextCompat.getDrawable(
                    this@EvaluationActivity, R.drawable.bg_radio_circle_unselected
                )
                layoutParams = LinearLayout.LayoutParams(dp(19), dp(19))
            }
            row.addView(circle)

            val views = RowViews(row, name, circle)
            rowsByScene[scene] = views

            row.setOnClickListener {
                if (revealed) return@setOnClickListener
                otherActive = false
                renderOtherSelection(
                    findViewById(R.id.otherOption),
                    findViewById(R.id.otherCircle),
                    findViewById(R.id.noteTile)
                )
                selectedScene = scene
                rowsByScene.forEach { (s, r) -> renderRowSelection(r, s == scene) }
            }

            container.addView(row)
        }
    }

    private fun renderRowSelection(row: RowViews, selected: Boolean) {
        row.container.background = ContextCompat.getDrawable(
            this,
            if (selected) R.drawable.bg_tile_accent else R.drawable.bg_tile
        )
        row.nameView.setTextColor(ContextCompat.getColor(
            this,
            if (selected) R.color.text_primary else R.color.text_secondary
        ))
        row.nameView.setTypeface(
            row.nameView.typeface,
            if (selected) Typeface.BOLD else Typeface.NORMAL
        )
        row.circleView.background = ContextCompat.getDrawable(
            this,
            if (selected) R.drawable.bg_radio_circle_selected else R.drawable.bg_radio_circle_unselected
        )
    }

    private fun renderOtherSelection(otherOption: LinearLayout, otherCircle: View, noteTile: LinearLayout) {
        otherOption.background = ContextCompat.getDrawable(
            this,
            if (otherActive) R.drawable.bg_tile_accent else R.drawable.bg_tile
        )
        otherCircle.background = ContextCompat.getDrawable(
            this,
            if (otherActive) R.drawable.bg_radio_circle_selected else R.drawable.bg_radio_circle_unselected
        )
        noteTile.visibility = if (otherActive) View.VISIBLE else View.GONE
    }

    private fun formatCounter(len: Int): String =
        getString(R.string.eval_note_counter, len, NOTE_MAX_LENGTH)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_PREDICTION_ID, predictionId)
        outState.putLong(STATE_DEADLINE, deadlineElapsed)
        outState.putInt(STATE_SELECTED_INDEX, selectedScene?.index ?: -1)
        outState.putBoolean(STATE_OTHER_ACTIVE, otherActive)
        outState.putBoolean(STATE_REVEALED, revealed)
        outState.putString(STATE_NOTE, findViewById<EditText>(R.id.noteInput).text.toString())
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        if (predictionId != -1L) {
            EvaluationPromptBus.dismiss(predictionId)
        }
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
