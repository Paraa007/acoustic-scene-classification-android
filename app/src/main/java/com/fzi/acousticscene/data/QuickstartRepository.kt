package com.fzi.acousticscene.data

import android.content.Context
import com.fzi.acousticscene.model.LongInterval
import com.fzi.acousticscene.model.ModelTrainingDuration
import com.fzi.acousticscene.model.QuickstartSlot
import com.fzi.acousticscene.model.RecordingCategory
import com.fzi.acousticscene.model.SessionConfig
import com.fzi.acousticscene.model.SessionDuration
import com.fzi.acousticscene.model.SessionMode
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken

/**
 * Persists quickstart slots — the saved [SessionConfig]s a tester can launch
 * with one tap from the Test Welcome screen. Capped at [MAX_SLOTS]; the save
 * flow rejects extra writes (UI shows a "pick one to overwrite" picker first).
 *
 * Slots are stored as JSON in SharedPreferences (`quickstart_slots_v2`), keyed
 * by their 1-based index. Methods on the config are derived at read time from
 * each model's training duration — they aren't part of the persisted payload.
 *
 * Singleton via [getInstance] for the same reason as [PredictionRepository]:
 * separate instances would race each other on writes.
 */
class QuickstartRepository private constructor(context: Context) {
    companion object {
        const val MAX_SLOTS = 5
        private const val PREFS = "quickstart_slots_v2"
        private const val KEY = "slots"

        @Volatile private var instance: QuickstartRepository? = null

        fun getInstance(context: Context): QuickstartRepository {
            return instance ?: synchronized(this) {
                instance ?: QuickstartRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    // In-memory cache. Always reflects what's on disk.
    private val slots: MutableList<QuickstartSlot> = mutableListOf()

    init { loadFromPrefs() }

    /** Read snapshot. Sorted by slot index ascending. */
    @Synchronized
    fun getAll(): List<QuickstartSlot> = slots.sortedBy { it.index }.toList()

    @Synchronized
    fun getCount(): Int = slots.size

    /**
     * Lowest 1-based slot index not yet in use, or null when all [MAX_SLOTS]
     * are taken.
     */
    @Synchronized
    fun lowestFreeIndex(): Int? {
        val taken = slots.map { it.index }.toSet()
        for (i in 1..MAX_SLOTS) if (i !in taken) return i
        return null
    }

    @Synchronized
    fun get(index: Int): QuickstartSlot? = slots.firstOrNull { it.index == index }

    /**
     * Writes a slot at [index], overwriting whatever's there. The config's
     * `mode` field is normalised to [SessionMode.TEST] because that's what the
     * slot is built for.
     *
     * Caller must guarantee [index] is in 1..[MAX_SLOTS] — out-of-range writes
     * are silently dropped.
     */
    @Synchronized
    fun saveSlot(index: Int, config: SessionConfig) {
        if (index !in 1..MAX_SLOTS) return
        val withMode = config.copy(mode = SessionMode.TEST)
        val pos = slots.indexOfFirst { it.index == index }
        val slot = QuickstartSlot(index = index, config = withMode)
        if (pos >= 0) slots[pos] = slot else slots.add(slot)
        saveToPrefs()
    }

    @Synchronized
    fun deleteSlot(index: Int) {
        val removed = slots.removeAll { it.index == index }
        if (removed) saveToPrefs()
    }

    @Synchronized
    fun clearAll() {
        slots.clear()
        saveToPrefs()
    }

    // ---- persistence ----

    private data class Persisted(
        val index: Int,
        val modelNames: List<String>,
        val category: String,
        val intervalPause: String?,
        val sessionDuration: String,
        val mode: String?
    )

    private fun loadFromPrefs() {
        val raw = prefs.getString(KEY, null) ?: return
        try {
            val type = object : TypeToken<List<Persisted>>() {}.type
            val parsed: List<Persisted> = gson.fromJson(raw, type) ?: emptyList()
            slots.clear()
            for (p in parsed) {
                if (p.index !in 1..MAX_SLOTS) continue
                val methods = p.modelNames.associateWith {
                    ModelTrainingDuration.requiredMethodsForModel(it)
                }
                val config = SessionConfig(
                    modelNames = p.modelNames,
                    category = runCatching { RecordingCategory.valueOf(p.category) }
                        .getOrDefault(RecordingCategory.CONTINUOUS),
                    continuousMethodsByModel = methods,
                    intervalPause = p.intervalPause?.let {
                        runCatching { LongInterval.valueOf(it) }.getOrNull()
                    },
                    intervalMethodsByModel = methods,
                    sessionDuration = runCatching { SessionDuration.valueOf(p.sessionDuration) }
                        .getOrDefault(SessionDuration.DEFAULT),
                    mode = p.mode?.let { runCatching { SessionMode.valueOf(it) }.getOrNull() }
                        ?: SessionMode.TEST
                )
                slots.add(QuickstartSlot(index = p.index, config = config))
            }
        } catch (_: JsonSyntaxException) {
            // Bad payload — start clean. We don't try to repair partial reads.
            slots.clear()
        }
    }

    private fun saveToPrefs() {
        val payload = slots.map { slot ->
            Persisted(
                index = slot.index,
                modelNames = slot.config.modelNames,
                category = slot.config.category.name,
                intervalPause = slot.config.intervalPause?.name,
                sessionDuration = slot.config.sessionDuration.name,
                mode = slot.config.mode.name
            )
        }
        prefs.edit().putString(KEY, gson.toJson(payload)).apply()
    }
}
