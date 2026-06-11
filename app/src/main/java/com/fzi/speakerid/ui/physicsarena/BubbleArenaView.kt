package com.fzi.speakerid.ui.physicsarena

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.fzi.acousticscene.R
import com.fzi.speakerid.library.data.Cluster
import com.fzi.speakerid.ui.SpeakerIdTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Port von siqas `gui/widgets/bubble_container.py` + `bubble_widget.py`
 * (+ .kv) und `library/calculations/physics.py::PhysicsService` als ein
 * Custom-View: Blasen pro Sprecher, Wachstum mit Sprechzeit
 * (`calculate_bubble_radius`), Target-Anziehung zur Mitte,
 * Kollision/Abstossung, Drag per Finger, Rendering auf Canvas mit
 * Choreographer-Loop (Kivy: `Clock.schedule_interval(update, 1/60)`).
 */
class BubbleArenaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /**
     * Pendant zu den in die Cluster-Objekte "injizierten" Physik-Attributen
     * (x, y, vx, vy, radius, is_dragged) plus den BubbleWidget-Properties.
     */
    class SpeakerBubble(val id: String) {
        var isTarget: Boolean = false
        var x: Float = 0f
        var y: Float = 0f
        var vx: Float = 0f
        var vy: Float = 0f
        var radius: Float = 28f
        var color: Int = Color.WHITE
        var totalTime: Double = 0.0
        var isDragged: Boolean = false

        /** Pulse-Flag aus `sync_with_clusters` (im Original ohne Optik). */
        var isActive: Boolean = false
    }

    private val density = resources.displayMetrics.density

    /** Sichtbare Blasen in Einfuege-Reihenfolge (Kivy-Zeichenreihenfolge). */
    private val bubbles = LinkedHashMap<String, SpeakerBubble>()

    private val accentColor = ContextCompat.getColor(context, R.color.speakerid_accent)

    /** `assets/images/bubble_texture.png`, von Kivy mit rgba getintet. */
    private val texture: Bitmap =
        BitmapFactory.decodeResource(resources, R.drawable.speakerid_bubble_texture)
    private val textureRect = RectF()

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.speakerid_dejavu_sans_bold)
            ?: Typeface.DEFAULT_BOLD
    }

    /** `outline_width: 1`, `outline_color: [1,1,1,0.4]` aus bubble_widget.kv. */
    private val labelOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        textAlign = Paint.Align.CENTER
        color = Color.argb(102, 255, 255, 255)
        typeface = labelFillPaint.typeface
    }

    private val targetPrefix =
        context.getString(R.string.speakerid_physics_target_marker) + " "

    // ── Choreographer-Loop (~60 fps, fester Physik-Schritt 1/60 s) ─────────

    private var running = false
    private var lastFrameNanos = 0L
    private var accumulatorS = 0.0

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameNanos != 0L) {
                accumulatorS += (frameTimeNanos - lastFrameNanos) / 1e9
                // Schutz gegen Spiralen nach langen Pausen
                if (accumulatorS > 0.25) accumulatorS = 0.25
                while (accumulatorS >= PHYSICS_STEP_S) {
                    stepPhysics()
                    accumulatorS -= PHYSICS_STEP_S
                }
            }
            lastFrameNanos = frameTimeNanos
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        lastFrameNanos = 0L
        accumulatorS = 0.0
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        // Positionen merken, damit die Blasen beim Rueckkehren dort
        // weiterschweben, wo sie waren (siqas: Attribute leben am Cluster).
        for ((id, b) in bubbles) {
            ArenaPositionCache.entries[id] = floatArrayOf(b.x, b.y, b.vx, b.vy)
        }
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    // ── Datenabgleich (BubbleContainer.sync_with_clusters) ─────────────────

    /**
     * Gleicht die Blasen mit dem DataManager-Zustand ab: erstellt neue,
     * aktualisiert Radius/Farbe/Aktiv-Status, entfernt verschwundene.
     */
    fun syncWithClusters(clusters: Map<String, Cluster>, activeSpeakerId: String) {
        val activeIds = HashSet<String>()
        val containerSize = min(width, height).toFloat()

        for ((cid, cluster) in clusters) {
            // Stille oder ungelabelte Chunks bekommen keine Blase
            if (cluster.isUnlabeled || cluster.isSilence || cluster.totalTime <= 0.0) continue
            activeIds.add(cid)

            val bubble = bubbles.getOrPut(cid) { createBubble(cid, cluster.isTarget) }
            bubble.isTarget = cluster.isTarget
            bubble.radius = calculateBubbleRadius(cluster.totalTime, containerSize)
            // Target bekommt Accent-Farbe, der Rest aus der Palette
            bubble.color = if (cluster.isTarget) {
                accentColor
            } else {
                SpeakerIdTheme.speakerColor(context, cid)
            }
            bubble.totalTime = cluster.totalTime
            bubble.isActive = cid == activeSpeakerId
        }

        // Alte Blasen entfernen, die nicht mehr in den Daten existieren
        val stale = bubbles.keys.filter { it !in activeIds }
        for (id in stale) {
            bubbles.remove(id)
            ArenaPositionCache.entries.remove(id)
        }
        invalidate()
    }

    /** Loescht alle Blasen (BubbleContainer.clear_all_speakers). */
    fun clearAllSpeakers() {
        bubbles.clear()
        ArenaPositionCache.entries.clear()
        invalidate()
    }

    /** Port von `BubbleContainer.add_speaker` (Startposition + Streuung). */
    private fun createBubble(id: String, isTarget: Boolean): SpeakerBubble {
        val bubble = SpeakerBubble(id)
        bubble.isTarget = isTarget

        val cached = ArenaPositionCache.entries[id]
        if (cached != null) {
            bubble.x = cached[0]
            bubble.y = cached[1]
            bubble.vx = cached[2]
            bubble.vy = cached[3]
            return bubble
        }

        var cx: Float
        var cy: Float
        if (width > 200f * density) {
            cx = width / 2f
            cy = height / 2f
        } else {
            // Fallback wie im Original (Window-Zentrum bzw. 800/600-Haelfte)
            cx = 400f
            cy = 300f
        }
        if (cx < 100f) cx = 400f
        if (cy < 100f) cy = 300f

        // Groessere Streuung, damit die Abstossung nicht durch 0 teilt
        val spread = 60f * density
        bubble.x = cx + (Random.nextFloat() * 2f - 1f) * spread
        bubble.y = cy + (Random.nextFloat() * 2f - 1f) * spread
        return bubble
    }

    /** Port von `visualization_calculations.calculate_bubble_radius`. */
    private fun calculateBubbleRadius(seconds: Double, containerSize: Float): Float {
        val base: Float
        val scale: Float
        if (containerSize > 0f) {
            val scaleFactor = containerSize / 400f
            base = 5f * scaleFactor
            scale = 22f * scaleFactor
        } else {
            base = 18f * density
            scale = 22f * density
        }
        return base + sqrt(max(0.0, seconds)).toFloat() * scale
    }

    // ── Physik (PhysicsService) ─────────────────────────────────────────────

    private fun stepPhysics() {
        if (bubbles.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val objects = bubbles.values.toList()

        for (obj in objects) {
            // 1. Dragging-Check: Wer gezogen wird, unterliegt nicht der Physik
            if (obj.isDragged) {
                obj.vx = 0f
                obj.vy = 0f
                continue
            }
            // 2. Anziehung zur Mitte: Nur fuer den Target-Sprecher
            if (obj.isTarget) applyMidpointGravity(obj, w / 2f, h / 2f)
            // 3. Reibung & Bewegung
            obj.vx *= 0.95f
            obj.vy *= 0.95f
            obj.x += obj.vx
            obj.y += obj.vy
        }

        // 4. Kollisionen loesen (Abstossung)
        resolveCollisions(objects)

        // 5. Grenzen (Sicherheitsnetz)
        for (obj in objects) keepInBounds(obj, w, h)

        // BubbleWidget.update_from_data: NaN/Inf-Notfallschutz + Hard-Clamp
        for (obj in objects) sanitize(obj)
    }

    private fun applyMidpointGravity(obj: SpeakerBubble, midX: Float, midY: Float) {
        val dx = midX - obj.x
        val dy = midY - obj.y
        val dist = sqrt(dx * dx + dy * dy) + 0.1f
        val strength = 0.5f
        obj.vx += (dx / dist) * strength * (dist * 0.01f)
        obj.vy += (dy / dist) * strength * (dist * 0.01f)
    }

    private fun resolveCollisions(objects: List<SpeakerBubble>) {
        for (i in objects.indices) {
            val obj1 = objects[i]
            for (j in i + 1 until objects.size) {
                val obj2 = objects[j]
                val dx = obj2.x - obj1.x
                val dy = obj2.y - obj1.y
                val distSq = dx * dx + dy * dy
                // Puffer-Abstand von 10 fuer bessere Optik
                val minDist = obj1.radius + obj2.radius + 10f * density
                if (distSq < minDist * minDist) {
                    handleHierarchicalCollision(obj1, obj2, dx, dy, distSq, minDist)
                }
            }
        }
    }

    private fun handleHierarchicalCollision(
        obj1: SpeakerBubble,
        obj2: SpeakerBubble,
        dx: Float,
        dy: Float,
        distSq: Float,
        minDist: Float,
    ) {
        var dist = sqrt(distSq)
        if (dist == 0f) dist = 0.01f
        val overlap = minDist - dist
        val nx = dx / dist
        val ny = dy / dist
        val amount = 0.05f // Staerke der Korrektur pro Frame

        val fixed1 = obj1.isTarget || obj1.isDragged
        val fixed2 = obj2.isTarget || obj2.isDragged

        if (fixed1 && !fixed2) {
            obj2.x += nx * overlap * amount * 2f
            obj2.y += ny * overlap * amount * 2f
            obj2.vx += nx * 0.2f
            obj2.vy += ny * 0.2f
        } else if (fixed2 && !fixed1) {
            obj1.x -= nx * overlap * amount * 2f
            obj1.y -= ny * overlap * amount * 2f
            obj1.vx -= nx * 0.2f
            obj1.vy -= ny * 0.2f
        } else if (!fixed1 && !fixed2) {
            val ratio = 0.5f
            obj1.x -= nx * overlap * ratio * amount
            obj1.y -= ny * overlap * ratio * amount
            obj2.x += nx * overlap * ratio * amount
            obj2.y += ny * overlap * ratio * amount
        }
    }

    private fun keepInBounds(obj: SpeakerBubble, w: Float, h: Float) {
        if (obj.x < obj.radius) {
            obj.x = obj.radius
            obj.vx *= -0.5f
        } else if (obj.x > w - obj.radius) {
            obj.x = w - obj.radius
            obj.vx *= -0.5f
        }
        if (obj.y < obj.radius) {
            obj.y = obj.radius
            obj.vy *= -0.5f
        } else if (obj.y > h - obj.radius) {
            obj.y = h - obj.radius
            obj.vy *= -0.5f
        }
        // Anti-NaN Schutz
        if (obj.x.isNaN() || obj.y.isNaN()) {
            obj.x = w / 2f
            obj.y = h / 2f
            obj.vx = 0f
            obj.vy = 0f
        }
    }

    /** `BubbleWidget.update_from_data` Schritt 1+2 (NaN-Schutz, Hard-Clamp). */
    private fun sanitize(obj: SpeakerBubble) {
        var x = obj.x
        var y = obj.y
        var r = obj.radius
        if (x.isNaN() || x.isInfinite()) x = 300f
        if (y.isNaN() || y.isInfinite()) y = 300f
        if (r.isNaN() || r.isInfinite() || r <= 0f) r = 28f
        x = x.coerceIn(-500f, 3000f)
        y = y.coerceIn(-500f, 3000f)
        obj.x = x
        obj.y = y
        obj.radius = r
    }

    // ── Touch / Drag (BubbleContainer.on_touch_down + BubbleWidget) ────────

    private var draggedBubble: SpeakerBubble? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Finde die Blase, die dem Finger am naechsten ist
                var closest: SpeakerBubble? = null
                var minDistSq = Float.POSITIVE_INFINITY
                for (b in bubbles.values) {
                    if (b.totalTime <= 0.0) continue // versteckt/deaktiviert
                    val dx = event.x - b.x
                    val dy = event.y - b.y
                    val distSq = dx * dx + dy * dy
                    if (distSq <= b.radius * b.radius && distSq < minDistSq) {
                        minDistSq = distSq
                        closest = b
                    }
                }
                if (closest != null) {
                    draggedBubble = closest
                    closest.isDragged = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val b = draggedBubble ?: return false
                b.x = event.x
                b.y = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val b = draggedBubble ?: return false
                b.isDragged = false
                draggedBubble = null
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ── Zeichnen (bubble_widget.kv) ─────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (b in bubbles.values) {
            // Blase ohne Sprechzeit: opacity 0
            if (b.totalTime <= 0.0) continue
            val r = b.radius

            // Haupt-Blase: Textur mit bubble_color getintet (Kivy-Multiply)
            bubblePaint.colorFilter = PorterDuffColorFilter(b.color, PorterDuff.Mode.MULTIPLY)
            textureRect.set(b.x - r, b.y - r, b.x + r, b.y + r)
            canvas.drawBitmap(texture, null, textureRect, bubblePaint)

            // Target-Ring (accent) + zweiter innerer Ring fuer Tiefe
            if (b.isTarget) {
                ringPaint.color = accentColor
                ringPaint.strokeWidth = 2.5f * density
                canvas.drawCircle(b.x, b.y, r + 4f * density, ringPaint)

                ringPaint.color =
                    (accentColor and 0x00FFFFFF) or (153 shl 24) // alpha 0.6
                ringPaint.strokeWidth = 1.2f * density
                canvas.drawCircle(b.x, b.y, r + 7f * density, ringPaint)
            }

            // Sprecher-ID-Label: ("⁂ " beim Target) + ID
            val label = (if (b.isTarget) targetPrefix else "") + b.id
            val textSize = max(10f * density, r * 0.45f)
            labelFillPaint.textSize = textSize
            labelOutlinePaint.textSize = textSize
            labelFillPaint.color = if (b.isTarget) accentColor else b.color
            val baseline =
                b.y - (labelFillPaint.ascent() + labelFillPaint.descent()) / 2f
            canvas.drawText(label, b.x, baseline, labelOutlinePaint)
            canvas.drawText(label, b.x, baseline, labelFillPaint)
        }
    }

    companion object {
        /** Kivy: `Clock.schedule_interval(self.update, 1/60.0)`. */
        private const val PHYSICS_STEP_S = 1.0 / 60.0
    }
}

/**
 * Prozessweiter Positions-Cache: In siqas leben x/y/vx/vy direkt an den
 * Cluster-Objekten und ueberleben so den Screen-Wechsel; hier uebernimmt
 * das dieser Cache (geleert, sobald ein Cluster verschwindet/Reset).
 */
private object ArenaPositionCache {
    val entries = HashMap<String, FloatArray>()
}
