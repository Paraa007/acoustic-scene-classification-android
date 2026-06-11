package com.fzi.speakerid.ui.targetrecorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.fzi.acousticscene.databinding.FragmentSpeakeridTargetRecorderBinding
import com.fzi.speakerid.audio.SpeakerAudioRecorder
import com.fzi.speakerid.library.pipeline.OnnxSessionProvider
import com.fzi.speakerid.ui.AssetModelInstaller
import com.fzi.speakerid.ui.SpeakerIdDataManager
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.min

/**
 * Port von siqas `gui/screens/target_recorder/target_recorder.py` (+ .kv):
 * Live-Aufnahme der eigenen Stimme als Target-Referenz.
 *
 * Verhalten 1:1 wie das Original:
 *  - `on_pre_enter` (Reset von Status/Timer/Frames) -> [onViewCreated]
 *    (Navigation Component baut die View bei jedem Eintritt neu auf).
 *  - 0,5-s-Audio-Bloecke @16 kHz werden in `frames` gesammelt
 *    ([SpeakerAudioRecorder] mit `chunkDurationS = 0.5`, /32767-Skalierung
 *    wie `_recording_loop_android`).
 *  - Timer-Label alle 0,1 s (`Clock.schedule_interval`) im MM:SS-Format.
 *  - Stop: WAV (PCM-16 mono) nach `filesDir/target_recordings/
 *    live_target_<epoch>.wav` schreiben, [TargetCentroid] berechnen,
 *    Cluster "1"-Centroid setzen, `save_target_cache`, `target_audio_path`,
 *    Cluster-Dispatch, gruene Erfolgsmeldung, nach 1 s zurueck navigieren.
 *  - Statusfarben wie die Kivy-Markups: [color=CC0000] Aufnahme,
 *    [color=00AA00] Erfolg, sonst text_secondary.
 *  - Responsive Schriftklammern `min(sp(x), root.width * f)` und
 *    Button-/Logo-Breiten werden bei Layoutaenderung nachgezogen.
 *
 * Android-Zusatz (nicht in Kivy): RECORD_AUDIO-Laufzeit-Permission wird vor
 * dem Aufnahmestart angefragt; Ablehnung zeigt den "Mikrofon-Fehler:"-Status.
 */
class TargetRecorderFragment : Fragment() {

    private var _binding: FragmentSpeakeridTargetRecorderBinding? = null
    private val binding get() = _binding!!

    private lateinit var dm: SpeakerIdDataManager

    /** `self.sample_rate = 16000` */
    private val sampleRate = 16000

    /** `self.frames` — Zugriff synchronisiert (Callback laeuft auf Worker-Thread). */
    private val frames = ArrayList<FloatArray>()

    /**
     * `self.start_time` — als monotone [SystemClock.elapsedRealtime]-Marke
     * statt Wanduhr: NTP-/manuelle Uhr-Korrekturen waehrend einer Aufnahme
     * duerfen die Timer-Anzeige nicht verzerren.
     */
    private var startTime = 0L

    private var recorder: SpeakerAudioRecorder? = null

    /** Kivy `is_recording`-Property. */
    private var isRecording = false

    private var sessionProvider: OnnxSessionProvider? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastLayoutWidth = 0

    /** `Clock.schedule_interval(self._update_timer, 0.1)` */
    private val timerTick = object : Runnable {
        override fun run() {
            val b = _binding ?: return
            if (!isRecording) return

            // Python setzt bei einem Fehler im Recording-Loop status_text +
            // is_recording=False aus dem Thread; hier wird der gestorbene
            // Recorder im Timer-Takt erkannt (SpeakerAudioRecorder loggt nur).
            val rec = recorder
            if (rec != null && !rec.isRecording) {
                isRecording = false
                recorder = null
                setStatus(
                    getString(
                        R.string.speakerid_target_recorder_mic_error,
                        getString(R.string.speakerid_target_recorder_record_failed),
                    ),
                    neutralStatusColor(),
                )
                updateRecordingUi()
                return
            }

            val elapsed = (SystemClock.elapsedRealtime() - startTime) / 1000.0
            b.labelTimer.text = formatTime(elapsed)
            b.root.postDelayed(this, 100L)
        }
    }

    /** `Clock.schedule_once(lambda dt: self._navigate_back(), 1.0)` */
    private val navigateBackRunnable = Runnable {
        if (_binding != null) navigateBack()
    }

    /**
     * RECORD_AUDIO-Laufzeit-Permission (Android-Pflicht vor AudioRecord).
     * Dem gelieferten Boolean wird nicht vertraut — entscheidend ist der
     * echte Permission-Stand nach der Dialog-Rueckkehr; [startRecording]
     * prueft ihn zusaetzlich selbst (Defense in depth).
     */
    private val recordPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        if (hasRecordPermission()) {
            startRecording()
        } else {
            showPermissionDeniedStatus()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSpeakeridTargetRecorderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dm = SpeakerIdDataManager.getInstance(requireContext())

        // ── on_pre_enter: Reset ─────────────────────────────────────────────
        setStatus(getString(R.string.speakerid_target_recorder_status_ready), neutralStatusColor())
        binding.labelTimer.text = getString(R.string.speakerid_target_recorder_timer_zero)
        synchronized(frames) { frames.clear() }
        isRecording = false
        updateRecordingUi()

        binding.btnBack.setOnClickListener { navigateBack() }
        binding.btnRecord.setOnClickListener { toggleRecording() }

        // Responsive Klammern aus dem .kv: min(sp(x), root.width * f)
        binding.root.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && width != lastLayoutWidth) {
                lastLayoutWidth = width
                applyResponsiveSizes(width)
            }
        }
    }

    // ── Aufnahme (toggle_recording / start_recording / stop_and_save) ───────

    private fun toggleRecording() {
        if (isRecording) {
            stopAndSave()
        } else {
            startRecordingWithPermission()
        }
    }

    private fun startRecordingWithPermission() {
        if (hasRecordPermission()) {
            startRecording()
        } else {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    /** Status "Mikrofon-Fehler: Mikrofon-Berechtigung verweigert". */
    private fun showPermissionDeniedStatus() {
        setStatus(
            getString(
                R.string.speakerid_target_recorder_mic_error,
                getString(R.string.speakerid_target_recorder_permission_denied),
            ),
            neutralStatusColor(),
        )
    }

    /** Port von `start_recording`. */
    private fun startRecording() {
        // Harte Sperre: Ohne erteilte RECORD_AUDIO-Permission startet weder
        // der Recorder noch der Timer — egal, ueber welchen Pfad der Aufruf
        // kommt (Button, Permission-Callback).
        if (!hasRecordPermission()) {
            showPermissionDeniedStatus()
            return
        }

        isRecording = true
        synchronized(frames) { frames.clear() }
        startTime = SystemClock.elapsedRealtime()
        // "[color=CC0000]Aufnahme läuft...[/color]"
        setStatus(
            getString(R.string.speakerid_target_recorder_status_recording),
            Color.parseColor("#CC0000"),
        )
        updateRecordingUi()

        // 0,5-s-Bloecke wie `read_samples = sample_rate // 2` im Original.
        val rec = SpeakerAudioRecorder(
            sampleRate = sampleRate,
            chunkDurationS = 0.5,
            chunkOverlapS = 0.0,
        ) { _, samples ->
            synchronized(frames) { frames.add(samples) }
        }
        recorder = rec
        try {
            rec.start()
        } catch (e: SecurityException) {
            isRecording = false
            recorder = null
            setStatus(
                getString(R.string.speakerid_target_recorder_mic_error, e.message ?: e.toString()),
                neutralStatusColor(),
            )
            updateRecordingUi()
            return
        }

        binding.root.removeCallbacks(timerTick)
        binding.root.postDelayed(timerTick, 100L)
    }

    /** Port von `stop_and_save`. */
    private fun stopAndSave() {
        if (!isRecording) return
        isRecording = false
        recorder?.stop()
        recorder = null
        binding.root.removeCallbacks(timerTick)
        setStatus(getString(R.string.speakerid_target_recorder_status_saving), neutralStatusColor())
        updateRecordingUi()

        val appContext = requireContext().applicationContext
        Thread({ processRecordedAudio(appContext) }, "TargetRecorderSave").apply {
            isDaemon = true
            start()
        }
    }

    /** Port von `_process_recorded_audio` (laeuft auf Worker-Thread). */
    private fun processRecordedAudio(appContext: Context) {
        val collected = synchronized(frames) { frames.toList() }
        if (collected.isEmpty()) {
            postStatus(getString(R.string.speakerid_target_recorder_status_no_audio))
            return
        }

        try {
            val total = collected.sumOf { it.size }
            val audioData = FloatArray(total)
            var offset = 0
            for (block in collected) {
                System.arraycopy(block, 0, audioData, offset, block.size)
                offset += block.size
            }

            // TARGET_RECORDINGS_DIR (Android: app_storage_path()/target_recordings)
            val dir = File(appContext.filesDir, "target_recordings")
            dir.mkdirs()
            val filepath = File(dir, "live_target_${System.currentTimeMillis() / 1000}.wav")
            writeWavPcm16(filepath, audioData, sampleRate)

            val provider = obtainProvider(appContext)
            val targetVector = TargetCentroid.generateFromFiles(
                listOf(filepath.absolutePath), provider
            )

            mainHandler.post { onSuccess(targetVector, filepath.absolutePath) }
        } catch (e: Exception) {
            postStatus(
                getString(
                    R.string.speakerid_target_recorder_status_error,
                    e.message ?: e.toString(),
                )
            )
        }
    }

    /** Port von `_on_success`: Profil speichern und zurueckspringen. */
    private fun onSuccess(targetVector: DoubleArray, filepath: String) {
        // if "1" in self.dm.clusters: centroid setzen (+ is_target ist in
        // Kotlin ein abgeleitetes val), Target-Cache speichern.
        dm.clusters["1"]?.let { cluster ->
            cluster.centroid = targetVector
            dm.saveTargetCache()
        }
        dm.targetAudioPath.value = filepath
        dm.dispatchClusters()

        val b = _binding ?: return
        // "[color=00AA00]Erfolgreich! Kehre zurück...[/color]"
        setStatus(
            getString(R.string.speakerid_target_recorder_status_success),
            Color.parseColor("#00AA00"),
        )
        b.root.removeCallbacks(navigateBackRunnable)
        b.root.postDelayed(navigateBackRunnable, 1000L)
    }

    /** `self.manager.current = 'target_setup'` -> Standard-Back. */
    private fun navigateBack() {
        findNavController().popBackStack()
    }

    // ── UI-Helfer ────────────────────────────────────────────────────────────

    /** Bindings aus dem .kv, die an `root.is_recording` haengen. */
    private fun updateRecordingUi() {
        val b = _binding ?: return
        // "← Zurück": disabled waehrend der Aufnahme
        b.btnBack.isEnabled = !isRecording
        // Button-Text/-Farbe: error_color/"Beenden & Speichern" vs. primary/"Mikrofon starten"
        b.btnRecord.setText(
            if (isRecording) R.string.speakerid_target_recorder_btn_stop
            else R.string.speakerid_target_recorder_btn_start
        )
        b.btnRecord.setBackgroundResource(
            if (isRecording) R.drawable.speakerid_bg_recorder_btn_stop
            else R.drawable.speakerid_bg_recorder_btn_start
        )
        // Timer: error_color wenn Aufnahme laeuft, sonst text_primary
        b.labelTimer.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (isRecording) R.color.speakerid_error else R.color.speakerid_text_primary,
            )
        )
    }

    private fun setStatus(text: String, color: Int) {
        val b = _binding ?: return
        b.labelStatus.text = text
        b.labelStatus.setTextColor(color)
    }

    /** `Clock.schedule_once(lambda dt: setattr(self, 'status_text', ...), 0)` */
    private fun postStatus(text: String) {
        mainHandler.post {
            if (_binding != null) setStatus(text, neutralStatusColor())
        }
    }

    private fun neutralStatusColor(): Int =
        ContextCompat.getColor(requireContext(), R.color.speakerid_text_secondary)

    /**
     * Responsive Groessen aus dem .kv (Kivy `root.width` = Screen-Breite in px):
     *  - Titel    min(sp(20), w*0.055)   - Untertitel min(sp(12), w*0.032)
     *  - Timer    min(sp(72), w*0.18)    - Status     min(sp(13), w*0.035)
     *  - Button   min(sp(15), w*0.04), Breite min(dp(320), w*0.85)
     *  - LogoBar  logo_width min(dp(90), w*0.2)
     */
    private fun applyResponsiveSizes(widthPx: Int) {
        val b = _binding ?: return
        val w = widthPx.toFloat()

        b.labelTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, min(spPx(20f), w * 0.055f))
        b.labelSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, min(spPx(12f), w * 0.032f))
        b.labelTimer.setTextSize(TypedValue.COMPLEX_UNIT_PX, min(spPx(72f), w * 0.18f))
        b.labelStatus.setTextSize(TypedValue.COMPLEX_UNIT_PX, min(spPx(13f), w * 0.035f))
        b.btnRecord.setTextSize(TypedValue.COMPLEX_UNIT_PX, min(spPx(15f), w * 0.04f))

        val buttonWidth = min(dpPx(320f), w * 0.85f).toInt()
        if (b.btnRecord.layoutParams.width != buttonWidth) {
            b.btnRecord.layoutParams = b.btnRecord.layoutParams.also { it.width = buttonWidth }
        }

        val logoWidth = min(dpPx(90f), w * 0.2f).toInt()
        if (b.logoBar.logoWidth != logoWidth) {
            b.logoBar.logoWidth = logoWidth
        }
    }

    private fun spPx(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun dpPx(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    /** Port von `library/calculations/time_helpers.py::format_time` (MM:SS). */
    private fun formatTime(seconds: Double): String {
        val m = (seconds / 60).toInt()
        val s = (seconds % 60).toInt()
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    // ── Audio-/Modell-Infrastruktur ─────────────────────────────────────────

    /**
     * WAV-Schreiben wie `wave` im Original: mono, 16-bit PCM,
     * `(audio_data * 32767).astype(np.int16)` (Truncation Richtung 0).
     */
    private fun writeWavPcm16(file: File, samples: FloatArray, sampleRate: Int) {
        val dataSize = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)                       // fmt-Chunk-Groesse
        buf.putShort(1.toShort())            // PCM
        buf.putShort(1.toShort())            // mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)           // Byte-Rate (mono, 16 bit)
        buf.putShort(2.toShort())            // Block-Align
        buf.putShort(16.toShort())           // Bits pro Sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (s in samples) {
            buf.putShort((s * 32767f).toInt().toShort())
        }
        file.writeBytes(buf.array())
    }

    /** ONNX-Sessions lazy aus dem App-Modellverzeichnis (Worker-Thread). */
    @Synchronized
    private fun obtainProvider(appContext: Context): OnnxSessionProvider =
        sessionProvider ?: OnnxSessionProvider(AssetModelInstaller.install(appContext))
            .also { sessionProvider = it }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onDestroyView() {
        // Anders als der ewig lebende Kivy-Screen muss die Aufnahme beim
        // Zerstoeren der View beendet werden (ohne Speichern).
        recorder?.stop()
        recorder = null
        isRecording = false
        binding.root.removeCallbacks(timerTick)
        binding.root.removeCallbacks(navigateBackRunnable)
        lastLayoutWidth = 0
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        sessionProvider?.close()
        sessionProvider = null
        super.onDestroy()
    }
}
