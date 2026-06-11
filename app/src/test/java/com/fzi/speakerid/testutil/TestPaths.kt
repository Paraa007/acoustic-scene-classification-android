package com.fzi.speakerid.testutil

import java.io.File
import org.junit.Assume.assumeTrue

/**
 * Zentrale Pfade fuer die Host-Unit-Tests des siqas-Ports.
 *
 * ACHTUNG: Golden-Daten und Test-WAVs liegen ABSICHTLICH als absolute Pfade
 * ausserhalb des Repos (Maschine paraaafifi). Tests, die sie brauchen, rufen
 * [assumeHostData] auf und werden auf anderen Maschinen uebersprungen statt
 * zu scheitern. Die ONNX-Modelle dagegen liegen im Repo und werden
 * repo-relativ aufgeloest.
 */
object TestPaths {

    /** Golden-Referenzdaten (generate_golden.py-Output). */
    val goldenDir = File("/Users/paraaafifi/Developer/work/arbeit_fzi/code/the_App_T/golden")

    /** siqas-Test-WAVs (Session-WAVs 16 kHz mono, test_stereo_* 44,1 kHz stereo). */
    val audioDir = File("/Users/paraaafifi/Developer/work/arbeit_fzi/code/the_App_T/siqas/assets/audio")

    /**
     * ONNX-Modelle repo-relativ: Gradle-Unit-Tests starten mit user.dir = app-Modul
     * (oder Repo-Root, je nach Aufruf) — wir laufen vom Arbeitsverzeichnis nach
     * oben, bis `app/src/main/assets/speakerid/models` bzw.
     * `src/main/assets/speakerid/models` existiert.
     */
    val modelsDir: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val d = dir ?: return@repeat
            val direct = File(d, "src/main/assets/speakerid/models")
            if (direct.isDirectory) return@lazy direct
            val viaApp = File(d, "app/src/main/assets/speakerid/models")
            if (viaApp.isDirectory) return@lazy viaApp
            dir = d.parentFile
        }
        error("speakerid/models nicht gefunden ausgehend von ${System.getProperty("user.dir")}")
    }

    fun golden(name: String): File = File(goldenDir, name)

    fun audio(name: String): File = File(audioDir, name)

    /** Skip (JUnit-Assume) falls die maschinenlokalen Referenzdaten fehlen. */
    fun assumeHostData() {
        assumeTrue(
            "Golden-/Audio-Referenzdaten nicht vorhanden (nur auf der Entwickler-Maschine)",
            goldenDir.isDirectory && audioDir.isDirectory,
        )
    }
}
