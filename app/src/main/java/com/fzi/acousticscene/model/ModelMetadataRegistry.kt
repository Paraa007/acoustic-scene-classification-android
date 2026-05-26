package com.fzi.acousticscene.model

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

/**
 * Loads `assets/model_metadata.json` once per process and serves the parsed
 * entries by filename. Lookups are case-sensitive and return null when an entry
 * is missing — the wizard treats that as "TEST ACC MISSING" and renders the
 * red empty-state row, but still lets the user select the model.
 */
object ModelMetadataRegistry {
    private const val TAG = "ModelMetadataRegistry"
    private const val ASSET_PATH = "model_metadata.json"

    private val gson = Gson()

    @Volatile private var cache: Map<String, ModelMetadata>? = null

    /**
     * Returns the metadata for [modelFilename], or null if the JSON has no
     * entry. Triggers a one-time read of the asset on first call.
     */
    fun get(context: Context, modelFilename: String): ModelMetadata? =
        load(context)[modelFilename]

    /**
     * True when the registry knows the model and has a non-null `testAccuracy`.
     * Used by the wizard to decide whether to show the green percent or the
     * red "missing" treatment.
     */
    fun hasAccuracy(context: Context, modelFilename: String): Boolean =
        get(context, modelFilename)?.testAccuracy != null

    /** Drops the in-memory cache. Mostly useful in tests. */
    fun reload() { cache = null }

    private fun load(context: Context): Map<String, ModelMetadata> {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: parse(context).also { cache = it }
        }
    }

    private fun parse(context: Context): Map<String, ModelMetadata> {
        val raw = try {
            context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "model_metadata.json missing or unreadable", e)
            return emptyMap()
        }
        return try {
            val type = object : TypeToken<List<Entry>>() {}.type
            val entries: List<Entry> = gson.fromJson(raw, type) ?: emptyList()
            entries.associate { entry ->
                entry.modelFilename to ModelMetadata(
                    modelFilename = entry.modelFilename,
                    trainingSeconds = entry.trainingSeconds,
                    testAccuracy = entry.testAccuracy
                )
            }
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "model_metadata.json is malformed", e)
            emptyMap()
        }
    }

    // JSON schema. Matches v2 spec exactly:
    //   { "model_filename": "...", "training_seconds": 10, "test_accuracy": 0.92 }
    private data class Entry(
        @SerializedName("model_filename") val modelFilename: String,
        @SerializedName("training_seconds") val trainingSeconds: Int,
        @SerializedName("test_accuracy") val testAccuracy: Double?
    )
}
