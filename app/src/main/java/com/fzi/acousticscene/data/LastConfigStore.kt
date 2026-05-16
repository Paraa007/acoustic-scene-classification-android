package com.fzi.acousticscene.data

import android.content.Context
import com.fzi.acousticscene.model.LongInterval
import com.fzi.acousticscene.model.LongSubMode
import com.fzi.acousticscene.model.ModelTrainingDuration
import com.fzi.acousticscene.model.RecordingCategory
import com.fzi.acousticscene.model.SessionConfig
import com.fzi.acousticscene.model.SessionDuration
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Persists the most recent [SessionConfig] so the welcome page's "Letzte Config
 * nutzen" button can skip the wizard. Stored as JSON in a small dedicated prefs
 * file. Never throws — corrupt blobs simply yield null and the button hides.
 */
object LastConfigStore {
    private const val PREFS = "last_config_prefs"
    private const val KEY = "last_session_config"

    private val gson = Gson()

    private data class Persisted(
        val modelNames: List<String>,
        val category: String,
        // Legacy single-method continuous selection. Reads still honor it for
        // configs saved before Continuous became multi-method per model; new
        // saves leave it null and use [continuousMethodsByModel] instead.
        val continuousSubMode: String? = null,
        val continuousMethodsByModel: Map<String, List<String>>? = null,
        val intervalPause: String?,
        val intervalMethodsByModel: Map<String, List<String>>,
        val sessionDuration: String
    )

    fun save(context: Context, config: SessionConfig) {
        val payload = Persisted(
            modelNames = config.modelNames,
            category = config.category.name,
            continuousSubMode = null,
            continuousMethodsByModel = config.continuousMethodsByModel.mapValues { (_, set) ->
                set.map { it.name }
            },
            intervalPause = config.intervalPause?.name,
            intervalMethodsByModel = config.intervalMethodsByModel.mapValues { (_, set) ->
                set.map { it.name }
            },
            sessionDuration = config.sessionDuration.name
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
            // Methods aren't user-pickable any more — derive them from each
            // model's training duration. We deliberately ignore any persisted
            // method maps (and the legacy continuousSubMode field) because
            // the spec narrowed: 1 s → FAST + AVG, 10 s → STANDARD.
            val methods = p.modelNames.associateWith {
                ModelTrainingDuration.requiredMethodsForModel(it)
            }
            SessionConfig(
                modelNames = p.modelNames,
                category = RecordingCategory.valueOf(p.category),
                continuousMethodsByModel = methods,
                intervalPause = p.intervalPause?.let { runCatching { LongInterval.valueOf(it) }.getOrNull() },
                intervalMethodsByModel = methods,
                sessionDuration = SessionDuration.valueOf(p.sessionDuration)
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
