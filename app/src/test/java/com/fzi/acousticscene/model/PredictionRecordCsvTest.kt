package com.fzi.acousticscene.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the device-metrics CSV columns (`battery_temp_c`, `cpu_usage_percent`)
 * added after `volume_peak`: value present, null (legacy/unavailable) and
 * synthetic PAUSE records. Also pins the backward-compat contract for
 * persistence: stored JSON without the new keys must deserialize with null.
 *
 * The helper records below deliberately avoid commas in every cell so the
 * naive `split(",")` lines the row up with the header 1:1.
 */
class PredictionRecordCsvTest {

    private fun realRecord(
        batteryTempC: Float? = null,
        cpuUsagePercent: Float? = null,
        ratingPercent: Int? = null
    ) = PredictionRecord(
        id = 1L,
        timestamp = 1_700_000_000_000L,
        sessionStartTime = 1_700_000_000_000L,
        sceneClass = SceneClass.NATURE,
        confidence = 0.8f,
        allProbabilities = FloatArray(9),
        topPredictions = listOf(
            SceneClass.NATURE to 0.8f,
            SceneClass.WORK to 0.1f,
            SceneClass.SOCIAL to 0.05f
        ),
        inferenceTimeMs = 120L,
        recordingMode = RecordingMode.STANDARD,
        batteryLevel = 80,
        modelName = "model1.pt",
        volumeMean = 0.1f,
        volumePeak = 0.2f,
        perSecondVolumes = FloatArray(10),
        batteryTempC = batteryTempC,
        cpuUsagePercent = cpuUsagePercent,
        ratingPercent = ratingPercent
    )

    private fun pauseRecord() = PredictionRecord(
        id = 2L,
        timestamp = 1_700_000_000_000L,
        sessionStartTime = 1_700_000_000_000L,
        sceneClass = SceneClass.TRANSIT_VEHICLES,
        confidence = 0f,
        allProbabilities = FloatArray(9),
        topPredictions = emptyList(),
        inferenceTimeMs = 0L,
        recordingMode = RecordingMode.STANDARD,
        batteryLevel = -1,
        modelName = "model1.pt",
        isPause = true,
        pauseDurationSec = 30L,
        volumeMean = 0f,
        volumePeak = 0f,
        perSecondVolumes = FloatArray(10)
    )

    private val header = PredictionRecord.getCsvHeader().split(",")
    private val tempIdx = header.indexOf("battery_temp_c")
    private val cpuIdx = header.indexOf("cpu_usage_percent")

    @Test
    fun header_placesDeviceMetricsBetweenVolumePeakAndVolumeS1() {
        assertEquals(header.indexOf("volume_peak") + 1, tempIdx)
        assertEquals(tempIdx + 1, cpuIdx)
        assertEquals(cpuIdx + 1, header.indexOf("volume_s1"))
    }

    @Test
    fun toCsvRow_writesDeviceMetricsWithOneDecimal() {
        val row = realRecord(batteryTempC = 36.54f, cpuUsagePercent = 123.46f)
            .toCsvRow().split(",")
        assertEquals(header.size, row.size)
        assertEquals("36.5", row[tempIdx])
        assertEquals("123.5", row[cpuIdx])
    }

    @Test
    fun toCsvRow_emitsEmptyCellsWhenMetricsAreNull() {
        val row = realRecord().toCsvRow().split(",")
        assertEquals(header.size, row.size)
        assertEquals("", row[tempIdx])
        assertEquals("", row[cpuIdx])
    }

    @Test
    fun toCsvRow_pauseRecordKeepsDeviceMetricsEmptyNotZero() {
        val row = pauseRecord().toCsvRow().split(",")
        assertEquals(header.size, row.size)
        // Volume keeps its 0-convention on PAUSE rows …
        assertEquals("0.000", row[header.indexOf("volume_mean")])
        assertEquals("0.000", row[header.indexOf("volume_peak")])
        // … the device metrics deliberately do not: 0 °C would be a fake value.
        assertEquals("", row[tempIdx])
        assertEquals("", row[cpuIdx])
        assertEquals("30", row[header.indexOf("pause_duration_sec")])
    }

    @Test
    fun header_placesRatingPercentAfterPauseAutoResumeMin() {
        val pauseAutoResumeIdx = header.indexOf("pause_auto_resume_min")
        assertEquals(pauseAutoResumeIdx + 1, header.indexOf("rating_percent"))
    }

    @Test
    fun toCsvRow_writesRatingPercentWhenSet() {
        val row = realRecord(ratingPercent = 30).toCsvRow().split(",")
        assertEquals(header.size, row.size)
        assertEquals("30", row[header.indexOf("rating_percent")])
    }

    @Test
    fun toCsvRow_emitsEmptyRatingPercentForLegacyRecords() {
        val row = realRecord().toCsvRow().split(",")
        assertEquals(header.size, row.size)
        assertEquals("", row[header.indexOf("rating_percent")])
    }

    @Test
    fun toCsvRow_pauseRecordRowStillMatchesHeaderWidth() {
        val row = pauseRecord().toCsvRow().split(",")
        assertEquals(header.size, row.size)
        assertEquals("", row[header.indexOf("rating_percent")])
    }

    @Test
    fun gson_deserializesLegacyJsonWithoutRatingPercentToNull() {
        val legacyJson = """
            {"id":1,"timestamp":2,"sessionStartTime":3,"sceneClass":"NATURE",
             "confidence":0.5,"inferenceTimeMs":10,"recordingMode":"STANDARD",
             "batteryLevel":50,"modelName":"model1.pt","isPause":false}
        """.trimIndent()
        val record = Gson().fromJson(legacyJson, PredictionRecord::class.java)
        assertNull(record.ratingPercent)
    }

    @Test
    fun gson_deserializesLegacyJsonWithoutDeviceMetricsToNull() {
        // Simulates a record persisted before the device-metrics fields landed
        // (PredictionRepository stores records as Gson JSON in SharedPreferences).
        val legacyJson = """
            {"id":1,"timestamp":2,"sessionStartTime":3,"sceneClass":"NATURE",
             "confidence":0.5,"inferenceTimeMs":10,"recordingMode":"STANDARD",
             "batteryLevel":50,"modelName":"model1.pt","isPause":false}
        """.trimIndent()
        val record = Gson().fromJson(legacyJson, PredictionRecord::class.java)
        assertEquals(SceneClass.NATURE, record.sceneClass)
        assertNull(record.batteryTempC)
        assertNull(record.cpuUsagePercent)
    }

    @Test
    fun gson_roundTripsDeviceMetrics() {
        val gson = Gson()
        val restored = gson.fromJson(
            gson.toJson(realRecord(batteryTempC = 31.2f, cpuUsagePercent = 45.0f)),
            PredictionRecord::class.java
        )
        assertEquals(31.2f, restored.batteryTempC!!, 0.001f)
        assertEquals(45.0f, restored.cpuUsagePercent!!, 0.001f)
    }
}
