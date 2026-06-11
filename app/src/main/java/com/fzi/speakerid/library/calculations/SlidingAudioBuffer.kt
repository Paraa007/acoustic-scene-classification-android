package com.fzi.speakerid.library.calculations

/**
 * Port von siqas `src/library/calculations/audio_buffer.py`.
 *
 * Kapselt einen rollenden Audio-Puffer fester Groesse zur Wahrung eines
 * Mindestkontexts (z. B. 10-s-Fenster fuer den Pyannote-Overlap-Cleaner).
 * Verhalten exakt wie das numpy-Original:
 *  - `extend` schiebt den Inhalt um `n` Samples nach links (np.roll(-n) mit
 *    anschliessendem Ueberschreiben des Endes) und haengt die neuen Daten an;
 *  - ist der neue Block >= Kapazitaet, ueberleben nur seine letzten Samples;
 *  - der Puffer startet (und [reset]et) auf Nullen.
 */
class SlidingAudioBuffer(val capacity: Int) {

    private val buffer = FloatArray(capacity)

    /** Anzahl bereits mit echten Daten gefuellter Samples (max. [capacity]). */
    var filled: Int = 0
        private set

    /** Fuegt neue Audiodaten hinzu und rollt das Fenster entsprechend. */
    fun extend(newData: FloatArray) {
        val nNew = newData.size
        if (nNew == 0) return
        if (nNew >= capacity) {
            System.arraycopy(newData, nNew - capacity, buffer, 0, capacity)
            filled = capacity
        } else {
            System.arraycopy(buffer, nNew, buffer, 0, capacity - nNew)
            System.arraycopy(newData, 0, buffer, capacity - nNew, nNew)
            filled = minOf(filled + nNew, capacity)
        }
    }

    /** Aktuelle Ansicht auf den Puffer (KEINE Kopie — nicht mutieren). */
    fun getView(): FloatArray = buffer

    /** Setzt den Puffer auf Nullwerte zurueck. */
    fun reset() {
        buffer.fill(0f)
        filled = 0
    }
}
