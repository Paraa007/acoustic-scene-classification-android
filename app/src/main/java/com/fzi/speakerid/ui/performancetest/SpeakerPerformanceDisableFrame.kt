package com.fzi.speakerid.ui.performancetest

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Pendant zu Kivys `disabled: True` fuer die Shared-Widgets
 * (SegmentedChoice/LabeledSlider verarbeiten Touches intern und kennen kein
 * Disabled): faengt bei [blocked] alle Touch-Events ab und dimmt den Inhalt
 * wie Kivys Disabled-Look (Alpha 0.5).
 *
 * Genutzt fuer `disabled: not root.use_noise` (Rauschunterdrueckungs-Methode)
 * und `disabled: not root.use_pyannote` (beide Pyannote-Slider) aus
 * `performance_test.kv`.
 */
class SpeakerPerformanceDisableFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var blocked: Boolean = false
        set(value) {
            field = value
            alpha = if (value) 0.5f else 1f
        }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = blocked

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = blocked || super.onTouchEvent(event)
}
