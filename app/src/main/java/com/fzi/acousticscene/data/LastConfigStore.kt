package com.fzi.acousticscene.data

import android.content.Context
import com.fzi.acousticscene.model.LongInterval
import com.fzi.acousticscene.model.ModelTrainingDuration
import com.fzi.acousticscene.model.RecordingCategory
import com.fzi.acousticscene.model.SessionConfig
import com.fzi.acousticscene.model.SessionDuration
import com.fzi.acousticscene.model.SessionMode
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Persists the most recent [SessionConfig] so the Config Welcome page's Quick
 * Start button can skip the wizard. Stored as JSON in a small dedicated prefs
 * file. Never throws — corrupt blobs simply yield null and the button hides.
 *
 * v2 simplification: method maps used to be persisted (back when the user could
 * tick them). Now methods are derived from each model's training duration, so
 * the persisted payload only carries the user-driven fields.
 *
 * v3 (slider rework): the pause interval and session duration moved from fixed
 * enums to open value carriers. The schema now stores the minute counts
 * directly (`intervalPauseMin`, `sessionDurationMin`). Legacy payloads that
 * carried the old enum names (`intervalPause`, `sessionDuration` strings) are
 * mapped back through a best-effort migration so old prefs still load.
 */
object LastConfigStore {
    private const val PREFS = "last_config_prefs"
    private const val KEY = "last_session_config"

    private val gson = Gson()

    private data class Persisted(
        val modelNames: List<String>,
        val category: String,
        // v3 fields
        val intervalPauseMin: Int? = null,
        val sessionDurationMin: Int? = null,
        // marker so the loader can tell a v3 payload that picked Manual
        // (sessionDurationMin = null) apart from a v2 payload that didn't
        // carry the minute field at all.
        val schemaVersion: Int = 1,
        val mode: String? = null,
        // v4 fields — both nullable so v3 payloads (and Gson's Unsafe
        // instantiation, which skips Kotlin defaults) read back as null and
        // get mapped to their defaults on load.
        val sessionEndDateMillis: Long? = null,
        val ratingPercent: Int? = null,
        // v2 legacy fields — kept nullable for backward read.
        val intervalPause: String? = null,
        val sessionDuration: String? = null
    )

    fun save(context: Context, config: SessionConfig) {
        val payload = Persisted(
            modelNames = config.modelNames,
            category = config.category.name,
            intervalPauseMin = config.intervalPause?.pauseMinutes,
            sessionDurationMin = config.sessionDuration.totalMinutes,
            schemaVersion = 3,
            mode = config.mode.name,
            sessionEndDateMillis = config.sessionDuration.endDateMillis,
            ratingPercent = config.ratingPercent
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, gson.toJson(payload))
            .apply()
    }

    fun load(context: Context): SessionConfig? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        return try {
            val p = gson.fromJson(raw, Persisted::class.java) ?: return null
            val methods = p.modelNames.associateWith {
                ModelTrainingDuration.requiredMethodsForModel(it)
            }
            val intervalPause = resolveLongInterval(p)
            val sessionDuration = resolveSessionDuration(p)
            SessionConfig(
                modelNames = p.modelNames,
                category = RecordingCategory.valueOf(p.category),
                continuousMethodsByModel = methods,
                intervalPause = intervalPause,
                intervalMethodsByModel = methods,
                sessionDuration = sessionDuration,
                ratingPercent = p.ratingPercent ?: 100,
                mode = p.mode?.let { runCatching { SessionMode.valueOf(it) }.getOrNull() }
                    ?: SessionMode.CONFIG
            )
        } catch (_: JsonSyntaxException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun has(context: Context): Boolean = load(context) != null

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply()
    }

    /**
     * v3 payloads carry the minute count directly. v2 payloads carry the old
     * enum name ("TEN_MIN", "ONE_HOUR", …) — map those back to the minute
     * count the enum used to expose so saved configs survive the migration.
     */
    private fun resolveLongInterval(p: Persisted): LongInterval? {
        p.intervalPauseMin?.let { return LongInterval.fromMinutes(it) }
        val legacy = p.intervalPause ?: return null
        val min = legacyLongIntervalMinutes(legacy) ?: return null
        return LongInterval.fromMinutes(min)
    }

    private fun resolveSessionDuration(p: Persisted): SessionDuration {
        // v4: an end date wins over everything — it's exclusive with minutes.
        p.sessionEndDateMillis?.let { return SessionDuration.untilDate(it) }
        // v3: schemaVersion >= 3 tells us null means MANUAL, not "missing"
        if (p.schemaVersion >= 3) {
            return p.sessionDurationMin?.let { SessionDuration.fromMinutes(it) }
                ?: SessionDuration.MANUAL
        }
        if (p.sessionDurationMin != null) {
            return SessionDuration.fromMinutes(p.sessionDurationMin)
        }
        val legacy = p.sessionDuration ?: return SessionDuration.DEFAULT
        return legacySessionDuration(legacy)
    }

    /** "TEN_MIN" → 10, "ONE_HOUR" → 60, "THREE_HOURS" → 180, etc. */
    private fun legacyLongIntervalMinutes(name: String): Int? = when (name) {
        "TEN_MIN" -> 10
        "FIFTEEN_MIN" -> 15
        "THIRTY_MIN" -> 30
        "FORTY_FIVE_MIN" -> 45
        "ONE_HOUR" -> 60
        "THREE_HOURS" -> 180
        else -> null
    }

    /** "MIN_30" → 30 min, "HOUR_3" → 180 min, "MANUAL" → null, etc. */
    private fun legacySessionDuration(name: String): SessionDuration = when (name) {
        "MIN_30" -> SessionDuration.fromMinutes(30)
        "HOUR_1" -> SessionDuration.fromMinutes(60)
        "HOUR_3" -> SessionDuration.fromMinutes(180)
        "HOUR_6" -> SessionDuration.fromMinutes(360)
        "HOUR_12" -> SessionDuration.fromMinutes(720)
        "MANUAL" -> SessionDuration.MANUAL
        else -> SessionDuration.DEFAULT
    }
}
