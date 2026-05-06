package com.fzi.acousticscene.data

import android.content.Context
import com.fzi.acousticscene.model.LongInterval
import com.fzi.acousticscene.model.LongSubMode
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
        val continuousSubMode: String,
        val intervalPause: String?,
        val intervalMethodsByModel: Map<String, List<String>>,
        val sessionDuration: String
    )

    fun save(context: Context, config: SessionConfig) {
        val payload = Persisted(
            modelNames = config.modelNames,
            category = config.category.name,
            continuousSubMode = config.continuousSubMode.name,
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
            SessionConfig(
                modelNames = p.modelNames,
                category = RecordingCategory.valueOf(p.category),
                continuousSubMode = LongSubMode.valueOf(p.continuousSubMode),
                intervalPause = p.intervalPause?.let { runCatching { LongInterval.valueOf(it) }.getOrNull() },
                intervalMethodsByModel = p.intervalMethodsByModel.mapValues { (_, list) ->
                    list.mapNotNull { runCatching { LongSubMode.valueOf(it) }.getOrNull() }.toSet()
                },
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
