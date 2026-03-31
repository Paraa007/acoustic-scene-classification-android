# Model Integration Specification — Acoustic Scene Classification App

Dieses Dokument beschreibt **exakt**, wie die Android-App Audio aufnimmt, das
Mel-Spectrogram berechnet, den Tensor baut und das PyTorch-Modell aufruft.
Alle Informationen stammen direkt aus dem Quellcode.

Ziel: Damit man ein Modell in Python/PyTorch so trainieren und exportieren kann,
dass es 1:1 zur App-Pipeline passt.

---

## 1. Audio-Aufnahme (`AudioRecorder.kt`)

| Parameter          | Wert                    |
|--------------------|-------------------------|
| Sample Rate        | **32 000 Hz**           |
| Channels           | **Mono**                |
| Format             | **PCM 16-bit signed**   |
| Output             | `FloatArray`, normalisiert auf **[-1.0, 1.0]** |
| Normalisierung     | `sample / 32768.0f`     |

### Aufnahmedauer pro Modus

| Modus      | Dauer (Sekunden) | Samples (bei 32 kHz) |
|------------|------------------|-----------------------|
| FAST       | 1                | 32 000                |
| MEDIUM     | 5                | 160 000               |
| STANDARD   | 10               | 320 000               |
| LONG       | 10               | 320 000               |
| AVERAGE    | 10               | 320 000 (10 × 32 000) |

Falls die tatsaechliche Sample-Anzahl von der erwarteten abweicht, wird
**zero-padded** (zu kurz) oder **abgeschnitten** (zu lang):

```kotlin
// ModelInference.kt, Zeile 170
val expectedSize = mode.durationSeconds * 32000
if (audioData.size < expectedSize)
    audioData + FloatArray(expectedSize - audioData.size)  // Zero-Pad
else
    audioData.take(expectedSize).toFloatArray()             // Truncate
```

---

## 2. Mel-Spectrogram Berechnung (`MelSpectrogramProcessor.kt`)

### 2.1 FFT-Parameter pro Modus

| Modus     | nFft  | winLength | hopLength | nMels | fMin | fMax    |
|-----------|-------|-----------|-----------|-------|------|---------|
| FAST      | 1024  | 768       | 256       | 64    | 0 Hz | 16000 Hz|
| MEDIUM    | 2048  | 1536      | 400       | 128   | 0 Hz | 16000 Hz|
| STANDARD  | 4096  | 3072      | 500       | 256   | 0 Hz | 16000 Hz|
| LONG      | 4096  | 3072      | 500       | 256   | 0 Hz | 16000 Hz|
| AVERAGE   | 1024  | 768       | 256       | 64    | 0 Hz | 16000 Hz|

> **Wichtig**: `fMin=0` und `fMax=16000` (= sampleRate/2) sind **hart kodiert**
> im `MelSpectrogramProcessor` Konstruktor (Zeile 42-43).

### 2.2 Resultierende Tensor-Shapes

Formel fuer `nFrames`:
```
nFrames = 1 + (nSamples - winLength) / hopLength
```

| Modus     | Samples   | nMels | nFrames | Tensor Shape              |
|-----------|-----------|-------|---------|---------------------------|
| FAST      | 32 000    | 64    | 122     | `[1, 1, 64, 122]`        |
| MEDIUM    | 160 000   | 128   | 397     | `[1, 1, 128, 397]`       |
| STANDARD  | 320 000   | 256   | 635     | `[1, 1, 256, 635]`       |
| LONG      | 320 000   | 256   | 635     | `[1, 1, 256, 635]`       |
| AVERAGE*  | 32 000    | 64    | 122     | `[1, 1, 64, 122]` (×10)  |

> \* AVERAGE-Modus fuehrt 10 separate Inferenzen durch (10 × 1s-Clip), jeweils
> mit FAST-Parametern. Die 10 Probability-Vektoren werden anschliessend gemittelt.

**Achtung**: Die Kommentare im Code nennen `[1, 1, 256, 641]` — das stimmt nur
wenn `nSamples = 320000` und man die Formel exakt mit den STANDARD-Parametern
rechnet: `1 + (320000 - 3072) / 500 = 635.856 → 635` (abgerundet via Integer-
Division). Die `641` im Kommentar scheint ein aelterer Wert zu sein.
**Das Modell muss dynamische Input-Groessen akzeptieren oder zur passenden Shape
passen.**

---

## 3. Pipeline Schritt-fuer-Schritt

### Schritt 1: Window Function — Hann Window

```kotlin
// MelSpectrogramProcessor.kt, Zeile 296-299
fun createHannWindow(size: Int): FloatArray {
    return FloatArray(size) { i ->
        (0.5f * (1 - cos(2.0 * PI * i / (size - 1)))).toFloat()
    }
}
```

- **Typ**: Hann (nicht Hamming!)
- **Laenge**: `winLength` (z.B. 3072 fuer STANDARD)
- **Formel**: `w[i] = 0.5 * (1 - cos(2*PI*i / (winLength-1)))`
- **Periodisch vs. Symmetrisch**: Dies ist die **symmetrische** Variante
  (`size-1` im Nenner). Dies entspricht `torch.hann_window(winLength, periodic=False)`.

> **PyTorch-Vergleich**: `torch.stft()` verwendet standardmaessig
> `periodic=True` (Nenner ist `size` statt `size-1`). Um kompatibel zu sein,
> muss man in PyTorch explizit `torch.hann_window(win_length, periodic=False)`
> oder die aequivalente Formel verwenden.

### Schritt 2: STFT (Short-Time Fourier Transform)

```
Fuer jeden Frame i (i = 0, 1, 2, ...):
  1. Start-Sample = i * hopLength
  2. Extrahiere winLength Samples ab Start-Sample
  3. Multipliziere Element-weise mit Hann-Window
  4. Zero-Padde auf nFft Laenge (Rest = 0)
  5. FFT (via TarsosDSP)
  6. Power Spectrum = Re² + Im²
```

**Details**:
- **Zero-Padding**: Die `winLength` Samples werden in ein Array der Groesse
  `nFft` geschrieben. Der Rest (von Index `winLength` bis `nFft-1`) bleibt 0.
  Bei STANDARD: 3072 Samples + 1024 Nullen = 4096.
- **Kein center-padding**: Die Frames starten bei Sample 0, nicht bei
  `-winLength/2`. Das heisst, es gibt **kein** `center=True`-Padding wie in
  `torch.stft(center=True)`.

> **PyTorch-Vergleich**: `torch.stft()` mit `center=True` (Standard!) padded
> das Signal mit `nFft//2` Nullen links und rechts. Die App tut das **nicht**.
> Um kompatibel zu sein: **`center=False`** in PyTorch verwenden.

**FFT-Implementierung**: TarsosDSP (`be.tarsos.dsp.util.fft.FFT`).
Output-Format nach `forwardTransform()`:
```
[DC, Nyquist, Re1, Im1, Re2, Im2, ..., Re(N/2-1), Im(N/2-1)]
```
- Index 0: DC-Komponente (nur Real)
- Index 1: Nyquist-Komponente (nur Real)
- Index 2k, 2k+1: Bin k (Real, Imaginaer), fuer k=1..N/2-1

**Power Spectrum Berechnung**:
```kotlin
// Bin 0 (DC):      power[0] = fft[0]²
// Bin N/2 (Nyq):   power[N/2] = fft[1]²
// Bin k (1..N/2-1): power[k] = fft[2k]² + fft[2k+1]²
```
Resultat: `nFft/2 + 1` Bins (z.B. 2049 fuer nFft=4096).

> **Wichtig**: Es wird das **Power Spectrum** berechnet (Re² + Im²),
> **nicht** das Magnitude Spectrum (sqrt(Re² + Im²)) und **nicht** das
> Power Spectrum mit Normalisierung. Es ist einfach `|X|²` ohne Division
> durch `nFft` oder aehnliches.

### Schritt 3: Mel-Filterbank

**Mel-Skala Konvertierung**:
```kotlin
// Hz → Mel (HTK-Formel)
fun hzToMel(hz: Float): Float = 2595f * log10(1f + hz / 700f)

// Mel → Hz
fun melToHz(mel: Float): Float = 700f * (10^(mel / 2595f) - 1f)
```

> Dies ist die **HTK-Formel** (nicht Slaney). Entspricht
> `librosa.hz_to_mel(hz, htk=True)` bzw. `torchaudio` mit `mel_scale="htk"`.

**Filterbank-Konstruktion**:
1. `nMels + 2` Punkte gleichmaessig auf der Mel-Skala verteilt
   zwischen `hzToMel(fMin)` und `hzToMel(fMax)`
2. Zurueck zu Hz konvertiert
3. Zu FFT-Bin-Indizes umgerechnet:
   ```
   bin = floor((nFft + 1) * hz / sampleRate)
   ```
4. Dreieckige Filter: Aufsteigende Flanke von `startBin` zu `centerBin`,
   absteigende Flanke von `centerBin` zu `endBin`.

**Filter-Normalisierung**: **KEINE!** Die Dreiecksfilter sind **nicht**
auf Flaeche 1 normalisiert. Das Maximum jedes Filters ist 1.0 (am Center-Bin).

> **PyTorch/librosa-Vergleich**:
> - `librosa.filters.mel(norm=None)` → entspricht der App (kein Norm)
> - `librosa.filters.mel(norm='slaney')` → NICHT kompatibel
> - `torchaudio.transforms.MelSpectrogram(norm=None)` → kompatibel
> - `torchaudio` Standard ist `norm=None` → kompatibel

**Anwendung**: Fuer jeden Frame:
```
mel_energy[m] = sum(power_spectrum[k] * filter[m][k])  fuer alle k
```
(Matrix-Multiplikation: `mel_filterbank @ power_spectrum`)

### Schritt 4: Log-Transformation

```kotlin
// MelSpectrogramProcessor.kt, Zeile 288
ln(melSpectrogram[mel][frame] + 1e-5f)
```

- **Funktion**: Natuerlicher Logarithmus (`ln`, Basis e)
- **Offset**: `1e-5` (um log(0) zu vermeiden)
- **NICHT** `log10`!
- **NICHT** Power-to-dB (wie `librosa.power_to_db`)!

> **PyTorch-Aequivalent**:
> ```python
> torch.log(mel_spectrogram + 1e-5)
> # ODER
> import math
> math.log(x)  # Natuerlicher Logarithmus
> ```
> **NICHT** `10 * torch.log10(...)` — das waere dB-Skala.

### Schritt 5: Tensor-Format

```kotlin
// MelSpectrogramProcessor.kt, Zeile 110-124
// Flatten: Row-Major Order [mel0_frame0, mel0_frame1, ..., mel1_frame0, ...]
for (mel in 0 until nMels) {
    for (frame in 0 until nFrames) {
        tensorData[mel * nFrames + frame] = logMel[mel][frame]
    }
}
```

- **Layout**: `[1, 1, nMels, nFrames]` — Batch=1, Channel=1
- **Speicher-Ordnung**: Row-Major (C-Ordnung) — Standard in PyTorch
- **Achsen**: `dim0=batch`, `dim1=channel`, `dim2=mel_bins`, `dim3=time_frames`

> **PyTorch-Aequivalent**:
> ```python
> tensor = mel_spec.unsqueeze(0).unsqueeze(0)  # [1, 1, nMels, nFrames]
> ```

### Schritt 6: PyTorch Mobile Inferenz

```kotlin
// ModelInference.kt, Zeile 208-226
val inputTensor = Tensor.fromBlob(
    flattenedMel,
    longArrayOf(1, 1, nMels.toLong(), nTimeFrames.toLong())
)
val output = module!!.forward(IValue.from(inputTensor))
val outputData = output.toTensor().dataAsFloatArray
```

- **PyTorch Mobile Version**: 1.13.1 (LTS)
- **Input**: `Tensor [1, 1, nMels, nFrames]` als `float32`
- **Output**: `Tensor` — wird als 1D `FloatArray` extrahiert (**Logits**)
- **Softmax wird in der App gemacht**, nicht im Modell!

### Schritt 7: Softmax (in der App)

```kotlin
// ModelInference.kt, Zeile 270-274
fun softmax(logits: FloatArray): FloatArray {
    val max = logits.maxOrNull() ?: 0f
    val expValues = logits.map { exp(it - max) }  // Numerisch stabil
    val sum = expValues.sum()
    return expValues.map { (it / sum).toFloat() }.toFloatArray()
}
```

- **Das Modell muss Logits ausgeben (NICHT Probabilities!)**
- Die App wendet Softmax selbst an
- Numerisch stabile Variante (Subtraktion des Max-Wertes)

### Schritt 8: Klassifikation

```kotlin
val maxIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
val sceneClass = SceneClass.fromIndex(maxIndex)
```

**Index → Klasse Mapping**:

| Index | SceneClass         | Label (Deutsch)                                  |
|-------|--------------------|--------------------------------------------------|
| 0     | TRANSIT_VEHICLES   | Transit - Fahrzeuge/draussen                     |
| 1     | URBAN_WAITING      | Aussen - urban & Transit-Gebaeude/Wartezonen     |
| 2     | NATURE             | Aussen - naturbetont                             |
| 3     | SOCIAL             | Innen - Soziale Umgebung                         |
| 4     | WORK               | Innen - Arbeitsumgebung                          |
| 5     | COMMERCIAL         | Innen - Kommerzielle/belebte Umgebung            |
| 6     | LEISURE_SPORT      | Innen - Freizeit/Sport                           |
| 7     | CULTURE_QUIET      | Innen - Kultur/Freizeit ruhig                    |
| 8     | LIVING_ROOM        | Innen - Wohnbereich                              |

---

## 4. Was das Modell liefern muss

### Input

| Eigenschaft | Wert                                         |
|-------------|----------------------------------------------|
| Typ         | `float32` Tensor                             |
| Shape       | `[1, 1, nMels, nFrames]` (dynamisch!)        |
| Inhalt      | Log-Mel-Spectrogram (`ln(mel + 1e-5)`)       |
| Wertebereich| Typisch ca. `-11.5` bis `+5.0`              |

**Achtung**: Das Modell muss mehrere Input-Shapes akzeptieren koennen
(oder fuer eine feste Shape trainiert sein):

| Wenn trainiert fuer | Erwartete Shape            |
|---------------------|----------------------------|
| STANDARD/LONG (10s) | `[1, 1, 256, 635]`         |
| FAST/AVERAGE (1s)   | `[1, 1, 64, 122]`          |
| MEDIUM (5s)          | `[1, 1, 128, 397]`         |

### Output

| Eigenschaft | Wert                                         |
|-------------|----------------------------------------------|
| Typ         | `float32` Tensor                             |
| Shape       | `[1, numClasses]` oder `[numClasses]`        |
| Inhalt      | **Logits** (KEIN Softmax im Modell!)         |
| numClasses  | 9 (Standard)                                 |

### Export-Format

- **PyTorch Mobile** (TorchScript) via `torch.jit.trace()` oder `torch.jit.script()`
- Dateiendung: `.pt`
- PyTorch Version: **1.13.1** (Kompatibilitaet mit PyTorch Mobile LTS)

---

## 5. PyTorch-Referenzimplementierung (kompatibel zur App)

```python
import torch
import torchaudio
import numpy as np
import math

# =============================================
# Parameter (STANDARD Mode)
# =============================================
SAMPLE_RATE = 32000
N_FFT = 4096
WIN_LENGTH = 3072
HOP_LENGTH = 500
N_MELS = 256
F_MIN = 0.0
F_MAX = 16000.0
LOG_OFFSET = 1e-5

# =============================================
# Methode 1: Manuelle Nachbildung (exakt wie App)
# =============================================

def app_compatible_mel_spectrogram(audio: torch.Tensor) -> torch.Tensor:
    """
    Berechnet Mel-Spectrogram exakt wie die Android-App.

    Args:
        audio: Tensor [num_samples], float32, normalisiert auf [-1, 1]
    Returns:
        Tensor [1, 1, n_mels, n_frames], float32
    """
    # 1. Hann Window (symmetrisch, NICHT periodic!)
    window = torch.hann_window(WIN_LENGTH, periodic=False)

    # 2. STFT (center=False! Die App padded NICHT!)
    stft = torch.stft(
        audio,
        n_fft=N_FFT,
        hop_length=HOP_LENGTH,
        win_length=WIN_LENGTH,
        window=window,
        center=False,          # <-- KRITISCH: App macht kein Center-Padding
        pad_mode='reflect',    # irrelevant bei center=False
        normalized=False,      # <-- KRITISCH: Keine FFT-Normalisierung
        onesided=True,
        return_complex=True
    )

    # 3. Power Spectrum (|X|^2, NICHT |X|)
    power_spec = stft.abs().pow(2)  # [n_freq_bins, n_frames]

    # 4. Mel-Filterbank (HTK-Skala, KEINE Normalisierung)
    mel_filterbank = torchaudio.functional.melscale_fbanks(
        n_freqs=N_FFT // 2 + 1,
        f_min=F_MIN,
        f_max=F_MAX,
        n_mels=N_MELS,
        sample_rate=SAMPLE_RATE,
        norm=None,             # <-- KRITISCH: Keine Flaechennormalisierung
        mel_scale="htk"        # <-- KRITISCH: HTK-Formel (nicht Slaney)
    )  # Shape: [n_freq_bins, n_mels]

    # 5. Anwenden: mel = filterbank^T @ power_spec
    mel_spec = torch.matmul(mel_filterbank.T, power_spec)  # [n_mels, n_frames]

    # 6. Log-Transformation (natuerlicher Logarithmus!)
    log_mel = torch.log(mel_spec + LOG_OFFSET)  # <-- ln(), NICHT log10()!

    # 7. Tensor-Format [1, 1, n_mels, n_frames]
    return log_mel.unsqueeze(0).unsqueeze(0)


# =============================================
# Methode 2: torchaudio (muss konfiguriert werden!)
# =============================================

def app_compatible_mel_via_torchaudio(audio: torch.Tensor) -> torch.Tensor:
    """
    Verwendet torchaudio, aber mit App-kompatiblen Parametern.

    ACHTUNG: torchaudio.transforms.MelSpectrogram hat andere Defaults!
    """
    mel_transform = torchaudio.transforms.MelSpectrogram(
        sample_rate=SAMPLE_RATE,
        n_fft=N_FFT,
        win_length=WIN_LENGTH,
        hop_length=HOP_LENGTH,
        f_min=F_MIN,
        f_max=F_MAX,
        n_mels=N_MELS,
        window_fn=lambda size: torch.hann_window(size, periodic=False),  # symmetrisch!
        center=False,          # <-- KRITISCH
        norm=None,             # <-- KRITISCH: keine Mel-Norm
        mel_scale="htk",       # <-- KRITISCH: HTK-Formel
        power=2.0,             # Power Spectrum (|X|^2)
        normalized=False       # <-- KRITISCH: keine FFT-Norm
    )

    mel_spec = mel_transform(audio)          # [1, n_mels, n_frames] oder [n_mels, n_frames]
    log_mel = torch.log(mel_spec + LOG_OFFSET)

    # Sicherstellen: [1, 1, n_mels, n_frames]
    if log_mel.dim() == 2:
        log_mel = log_mel.unsqueeze(0).unsqueeze(0)
    elif log_mel.dim() == 3:
        log_mel = log_mel.unsqueeze(0)

    return log_mel


# =============================================
# Modell-Export fuer die App
# =============================================

def export_model_for_app(model, example_audio_length=320000):
    """
    Exportiert ein PyTorch-Modell fuer die Android-App.

    Das Modell muss:
    - Input: [1, 1, n_mels, n_frames] (float32)
    - Output: [1, num_classes] Logits (KEIN Softmax!)
    """
    model.eval()

    # Beispiel-Input generieren (STANDARD Mode)
    audio = torch.randn(example_audio_length)
    example_input = app_compatible_mel_spectrogram(audio)

    # TorchScript Export
    traced = torch.jit.trace(model, example_input)
    traced.save("model.pt")

    print(f"Exported model.pt")
    print(f"  Input shape:  {example_input.shape}")
    print(f"  Output shape: {traced(example_input).shape}")
    print(f"  Output type:  Logits (Softmax wird in der App berechnet)")
```

---

## 6. Haeufige Fehlerquellen / Checkliste

| #  | Pruefpunkt                        | App-Wert                    | Typischer PyTorch-Default           | Problem wenn falsch |
|----|-----------------------------------|-----------------------------|-------------------------------------|---------------------|
| 1  | `center` bei STFT                 | **`False`**                 | `True`                              | Andere nFrames, verschobene Features |
| 2  | Window-Typ                        | **Hann, symmetrisch**       | Hann, periodic                      | Leicht andere Gewichtung |
| 3  | Mel-Skala                         | **HTK** (`2595*log10(...)`) | Slaney (linear unter 1kHz)          | Komplett andere Filterbank |
| 4  | Mel-Normalisierung                | **None**                    | None (ok) oder `slaney`             | Andere Amplituden |
| 5  | Log-Funktion                      | **ln (Basis e)**            | `log10` oder `power_to_db`          | Andere Werteverteilung |
| 6  | Log-Offset                        | **1e-5**                    | variiert                            | NaN/Inf bei kleinen Werten |
| 7  | Power vs. Magnitude               | **Power (|X|²)**            | manchmal Magnitude                  | Quadratischer Unterschied |
| 8  | FFT-Normalisierung                | **Keine**                   | manchmal `normalized=True`          | Skalierungsfaktor |
| 9  | Output                            | **Logits**                  | manchmal Softmax im Modell          | Doppelter Softmax |
| 10 | Tensor-Layout                     | **[1,1,mels,frames]**       | ok (Standard in PyTorch)            | — |
| 11 | Bin-Berechnung Filterbank         | `floor((nFft+1)*hz/sr)`    | librosa: `floor(1+nFft*hz/sr/2)`   | Leicht verschobene Filter |

---

## 7. Dateien-Referenz

| Datei | Beschreibung |
|-------|-------------|
| `audio/AudioRecorder.kt` | Aufnahme: 32kHz, Mono, PCM16 → Float [-1,1] |
| `audio/MelSpectrogramProcessor.kt` | STFT, Mel-Filterbank, Log-Transformation |
| `ml/ModelInference.kt` | Tensor-Erstellung, PyTorch Mobile Inferenz, Softmax |
| `ml/ComputationDispatcher.kt` | Thread-Pool (2 Threads) fuer Mel+Inferenz |
| `model/RecordingMode.kt` | FFT-Parameter pro Modus |
| `model/ClassificationResult.kt` | Ergebnis-Datenklasse (sceneClass, confidence, allProbabilities) |
| `model/SceneClass.kt` | 9 Klassen-Enum mit Index-Mapping |
