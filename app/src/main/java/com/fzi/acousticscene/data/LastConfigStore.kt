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
 */
object LastConfigStore {
    private const val PREFS = "last_config_prefs"
    private const val KEY = "last_session_config"

    private val gson = Gson()

    private data class Persisted(
        val modelNames: List<String>,
        val category: String,
        val intervalPause: String?,
        val sessionDuration: String,
        val mode: String? = null
    )

    fun save(context: Context, config: SessionConfig) {
        val payload = Persisted(
            modelNames = config.modelNames,
            category = config.category.name,
            intervalPause = config.intervalPause?.name,
            sessionDuration = config.sessionDuration.name,
            mode = config.mode.name
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
            SessionConfig(
                modelNames = p.modelNames,
                category = RecordingCategory.valueOf(p.category),
                continuousMethodsByModel = methods,
                intervalPause = p.intervalPause?.let {
                    runCatching { LongInterval.valueOf(it) }.getOrNull()
                },
                intervalMethodsByModel = methods,
                sessionDuration = SessionDuration.valueOf(p.sessionDuration),
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
}
