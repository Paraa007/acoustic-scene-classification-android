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
 * Crash/reboot insurance for long-running interval studies. While an interval
 * session with a calendar end date is recording, this store holds the minimum
 * needed to offer a resume after an interruption: the session config, the
 * original session start, the planned end and the timestamp of the last
 * persisted cycle.
 *
 * Lifecycle: written by [com.fzi.acousticscene.service.RecordingEngine.start],
 * the last-cycle timestamp refreshed after every persisted cycle, and cleared
 * on every normal loop exit (user stop, soft stop, error exit). A snapshot
 * that survives until the next boot or app launch therefore means the process
 * died mid-session — that's when the recovery notification fires.
 *
 * Continuous sessions and sessions without an end date are never written here,
 * so they can never produce a false "interrupted" prompt.
 */
object ActiveSessionStore {
    private const val PREFS = "active_session_store"
    private const val KEY_SNAPSHOT = "interrupted_session"
    private const val KEY_LAST_CYCLE = "last_cycle_ts"

    private val gson = Gson()

    data class Snapshot(
        val config: SessionConfig,
        val sessionStartTime: Long,
        val plannedEndMillis: Long,
        val lastCycleTimestamp: Long
    )

    /**
     * Everything nullable: Gson instantiates via Unsafe and skips Kotlin
     * defaults, and a half-written or schema-drifted payload should degrade
     * to "no snapshot" instead of a crash.
     */
    private data class Persisted(
        val modelNames: List<String>? = null,
        val category: String? = null,
        val intervalPauseMin: Int? = null,
        val sessionDurationMin: Int? = null,
        val sessionEndDateMillis: Long? = null,
        val ratingPercent: Int? = null,
        val mode: String? = null,
        val sessionStartTime: Long? = null,
        val plannedEndMillis: Long? = null
    )

    fun save(
        context: Context,
        config: SessionConfig,
        sessionStartTime: Long,
        plannedEndMillis: Long,
        lastCycleTimestamp: Long
    ) {
        val payload = Persisted(
            modelNames = config.modelNames,
            category = config.category.name,
            intervalPauseMin = config.intervalPause?.pauseMinutes,
            sessionDurationMin = config.sessionDuration.totalMinutes,
            sessionEndDateMillis = config.sessionDuration.endDateMillis,
            ratingPercent = config.ratingPercent,
            mode = config.mode.name,
            sessionStartTime = sessionStartTime,
            plannedEndMillis = plannedEndMillis
        )
        prefs(context).edit()
            .putString(KEY_SNAPSHOT, gson.toJson(payload))
            .putLong(KEY_LAST_CYCLE, lastCycleTimestamp)
            .apply()
    }

    /** Cheap per-cycle refresh — only touches the long, not the JSON blob. */
    fun updateLastCycle(context: Context, timestamp: Long) {
        prefs(context).edit().putLong(KEY_LAST_CYCLE, timestamp).apply()
    }

    fun load(context: Context): Snapshot? {
        val raw = prefs(context).getString(KEY_SNAPSHOT, null) ?: return null
        return try {
            val p = gson.fromJson(raw, Persisted::class.java) ?: return null
            val modelNames = p.modelNames?.takeIf { it.isNotEmpty() } ?: return null
            val category = p.category
                ?.let { runCatching { RecordingCategory.valueOf(it) }.getOrNull() }
                ?: return null
            val start = p.sessionStartTime ?: return null
            val plannedEnd = p.plannedEndMillis ?: return null
            val methods = modelNames.associateWith {
                ModelTrainingDuration.requiredMethodsForModel(it)
            }
            val sessionDuration = when {
                p.sessionEndDateMillis != null -> SessionDuration.untilDate(p.sessionEndDateMillis)
                p.sessionDurationMin != null -> SessionDuration.fromMinutes(p.sessionDurationMin)
                else -> SessionDuration.MANUAL
            }
            Snapshot(
                config = SessionConfig(
                    modelNames = modelNames,
                    category = category,
                    continuousMethodsByModel = methods,
                    intervalPause = LongInterval.fromMinutesOrNull(p.intervalPauseMin),
                    intervalMethodsByModel = methods,
                    sessionDuration = sessionDuration,
                    ratingPercent = p.ratingPercent ?: 100,
                    mode = p.mode?.let { runCatching { SessionMode.valueOf(it) }.getOrNull() }
                        ?: SessionMode.CONFIG
                ),
                sessionStartTime = start,
                plannedEndMillis = plannedEnd,
                lastCycleTimestamp = prefs(context).getLong(KEY_LAST_CYCLE, start)
            )
        } catch (_: JsonSyntaxException) {
            null
        }
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_SNAPSHOT)
            .remove(KEY_LAST_CYCLE)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
