package com.fzi.acousticscene.ui

import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fzi.acousticscene.R
import com.fzi.acousticscene.data.PredictionRepository
import com.fzi.acousticscene.model.SceneClass
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * Activity for user evaluation of acoustic scene classification predictions.
 * Launched from a notification after each LONG-mode recording.
 * User has 5 minutes to select the actual scene class and optionally add a comment.
 */
class EvaluationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PREDICTION_ID = "prediction_id"
        const val EXTRA_MODEL_PREDICTED_CLASS = "model_predicted_class"
        const val EVALUATION_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes - shared with MainViewModel

        private const val STATE_PREDICTION_ID = "state_prediction_id"
        private const val STATE_DEADLINE = "state_deadline"
        private const val STATE_SELECTED_INDEX = "state_selected_index"
        private const val STATE_COMMENT = "state_comment"
    }

    private var predictionId: Long = -1
    private var countDownTimer: CountDownTimer? = null
    private var deadlineElapsed: Long = 0L // SystemClock.elapsedRealtime() based deadline

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_evaluation)

        // Restore or init prediction ID
        predictionId = savedInstanceState?.getLong(STATE_PREDICTION_ID, -1)
            ?: intent.getLongExtra(EXTRA_PREDICTION_ID, -1)

        if (predictionId == -1L) {
            finish()
            return
        }

        // Show model prediction
        val modelClassStr = intent.getStringExtra(EXTRA_MODEL_PREDICTED_CLASS)
        val modelClass = modelClassStr?.let { name ->
            try { SceneClass.valueOf(name) } catch (_: Exception) { null }
        }
        val modelPredictionText = findViewById<TextView>(R.id.modelPredictionText)
        modelPredictionText.text = modelClass?.let { "${it.emoji} ${it.labelShort}" } ?: "N/A"

        // Build radio buttons for 9 scene classes
        val radioGroup = findViewById<RadioGroup>(R.id.sceneClassRadioGroup)
        SceneClass.entries.forEach { scene ->
            val radioButton = RadioButton(this).apply {
                id = scene.index + 1000 // offset to avoid ID conflicts
                text = "${scene.emoji}  ${scene.labelShort}"
                textSize = 16f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setPadding(0, 16, 0, 16)
                buttonTintList = ContextCompat.getColorStateList(context, R.color.accent_green_light)
                tag = scene
            }
            radioGroup.addView(radioButton)
        }

        // Restore selection state
        savedInstanceState?.let { state ->
            val selectedIdx = state.getInt(STATE_SELECTED_INDEX, -1)
            if (selectedIdx >= 0) {
                radioGroup.check(selectedIdx + 1000)
            }
        }

        // Submit button
        val btnSubmit = findViewById<MaterialButton>(R.id.btnSubmit)
        val commentEditText = findViewById<TextInputEditText>(R.id.commentEditText)

        // Restore comment
        savedInstanceState?.getString(STATE_COMMENT)?.let {
            commentEditText.setText(it)
        }

        btnSubmit.setOnClickListener {
            val selectedId = radioGroup.checkedRadioButtonId
            val selectedClass = if (selectedId != -1) {
                radioGroup.findViewById<RadioButton>(selectedId)?.tag as? SceneClass
            } else null

            val comment = commentEditText.text?.toString()?.trim()?.ifEmpty { null }

            if (selectedClass != null || comment != null) {
                PredictionRepository.getInstance(this)
                    .updatePredictionEvaluation(predictionId, selectedClass, comment)
            }
            finish()
        }

        // Skip button
        findViewById<MaterialButton>(R.id.btnSkip).setOnClickListener {
            finish()
        }

        // Calculate remaining time (survive rotation)
        deadlineElapsed = savedInstanceState?.getLong(STATE_DEADLINE, 0L) ?: 0L
        if (deadlineElapsed == 0L) {
            deadlineElapsed = SystemClock.elapsedRealtime() + EVALUATION_TIMEOUT_MS
        }
        val remainingMs = (deadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        if (remainingMs == 0L) {
            finish()
            return
        }

        // Start countdown with remaining time
        val timerText = findViewById<TextView>(R.id.timerText)
        countDownTimer = object : CountDownTimer(remainingMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val minutes = (millisUntilFinished / 1000) / 60
                val seconds = (millisUntilFinished / 1000) % 60
                timerText.text = getString(R.string.evaluation_timeout, minutes.toInt(), seconds.toInt())
            }

            override fun onFinish() {
                finish()
            }
        }.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_PREDICTION_ID, predictionId)
        outState.putLong(STATE_DEADLINE, deadlineElapsed)

        val radioGroup = findViewById<RadioGroup>(R.id.sceneClassRadioGroup)
        val selectedId = radioGroup.checkedRadioButtonId
        if (selectedId != -1) {
            outState.putInt(STATE_SELECTED_INDEX, selectedId - 1000)
        }

        val comment = findViewById<TextInputEditText>(R.id.commentEditText).text?.toString()
        if (!comment.isNullOrEmpty()) {
            outState.putString(STATE_COMMENT, comment)
        }
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        super.onDestroy()
    }
}
