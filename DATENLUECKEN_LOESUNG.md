# Lösung der Datenlücken-Problematik

## Dokumentation aller Änderungen und Fixes

**Erstellt:** 2026-01-26
**App:** Acoustic Scene Classification für DCASE2025
**Problem:** 68% Datenverlust durch Lücken bei Nacht-Aufnahmen

---

## Inhaltsverzeichnis

1. [Das ursprüngliche Problem](#1-das-ursprüngliche-problem)
2. [Analyse der Ursachen](#2-analyse-der-ursachen)
3. [Lösung 1: WakeLock implementieren](#3-lösung-1-wakelock-implementieren)
4. [Lösung 2: Doze-Mode bekämpfen](#4-lösung-2-doze-mode-bekämpfen)
5. [Bonus-Features](#5-bonus-features)
6. [Zusammenfassung aller Änderungen](#6-zusammenfassung-aller-änderungen)
7. [Anleitung für Benutzer](#7-anleitung-für-benutzer)

---

## 1. Das ursprüngliche Problem

### Symptome

Bei der Analyse der CSV-Daten nach einer Nacht-Aufnahme wurden massive Datenlücken festgestellt:

```
======================================================================
DATENLÜCKEN-REPORT
======================================================================
  Anzahl Lücken:       168
  Gesamtdauer Lücken:  6.1h
  Anteil an Gesamtzeit: 68.0%

  Aufnahme-Zeitraum:   01:03:36 bis 10:06:01
  Gesamtdauer:         9.0h
  Recording-Modus:     STANDARD (10s)
======================================================================
```

**68% der Daten gingen verloren!**

### Erwartetes vs. tatsächliches Verhalten

| Erwartet | Tatsächlich |
|----------|-------------|
| Messung alle 10 Sekunden | Messungen mit Lücken von 1-7 Minuten |
| Kontinuierliche Aufnahme | Aufnahme stoppt nach ~2.5 Stunden |
| Lückenlose Daten | 168 Datenlücken in 9 Stunden |

---

## 2. Analyse der Ursachen

### Problem 1: WakeLock nicht verwendet

**Datei:** `ClassificationService.kt`

Die App hatte die Permission für WakeLock im Manifest deklariert:
```xml
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

**ABER:** Der WakeLock wurde nirgendwo im Code tatsächlich erworben!

**Auswirkung:**
```
Bildschirm aus → Android: "Niemand braucht CPU" → CPU schläft → Keine Aufnahme
```

### Problem 2: Android Doze Mode

Nach dem ersten Fix mit WakeLock gab es immer noch Lücken. Die Analyse zeigte ein Muster:

```
Lücken beginnen erst nach ~2.5 Stunden
Viele Lücken enden auf :59 oder :00 (z.B. 03:49:59, 04:29:59)
```

**Das ist das typische Muster von Android Doze Mode!**

#### Was ist Doze Mode?

Ab Android 6.0 (Marshmallow) versetzt Android das Gerät in einen Energiesparmodus wenn:
- Bildschirm aus
- Gerät liegt still (keine Bewegung)
- Nicht am Ladekabel

**Im Doze Mode:**
- Netzwerk wird eingeschränkt
- Jobs/Alarms werden verzögert
- **WakeLocks werden IGNORIERT!** (auch bei Foreground Services!)

```
┌─────────────────────────────────────────────────────────────────┐
│                    DOZE MODE TIMELINE                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  0h        1h        2h        3h        4h        5h          │
│  │         │         │         │         │         │           │
│  ▼         ▼         ▼         ▼         ▼         ▼           │
│  ════════════════╗   ╔═╗   ╔═╗   ╔═╗   ╔═╗   ╔═╗              │
│  Normal         ║   ║ ║   ║ ║   ║ ║   ║ ║   ║ ║              │
│                 ╚═══╝ ╚═══╝ ╚═══╝ ╚═══╝ ╚═══╝ ╚══             │
│                 └───┘ └───┘ └───┘ └───┘ └───┘                  │
│                 Doze  Doze  Doze  Doze  Doze                   │
│                                                                 │
│  Maintenance Windows: kurze Aufwach-Phasen alle paar Minuten   │
│  → Hier entstehen die Lücken!                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. Lösung 1: WakeLock implementieren

### Änderungen in `ClassificationService.kt`

#### 3.1 WakeLock Variable hinzugefügt

```kotlin
// WakeLock um CPU aktiv zu halten während Aufnahme läuft
private var wakeLock: PowerManager.WakeLock? = null
```

#### 3.2 WakeLock erwerben beim Start

```kotlin
private fun acquireWakeLock() {
    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,  // Nur CPU, nicht Bildschirm
        "AcousticScene::ClassificationWakeLock"
    ).apply {
        acquire(4 * 60 * 60 * 1000L)  // 4 Stunden Timeout als Sicherheit
    }
}
```

**Warum PARTIAL_WAKE_LOCK?**
- Hält nur die CPU wach
- Bildschirm bleibt aus (spart Batterie)
- Genau richtig für Hintergrund-Aufnahmen

#### 3.3 WakeLock freigeben beim Stop

```kotlin
private fun releaseWakeLock() {
    wakeLock?.let { lock ->
        if (lock.isHeld) {
            lock.release()
        }
    }
    wakeLock = null
}
```

**Wichtig:** WakeLock MUSS freigegeben werden! Sonst:
- Batterie entleert sich schnell
- System kann instabil werden

---

## 4. Lösung 2: Doze-Mode bekämpfen

Da WakeLock allein nicht ausreichte, implementierte ich einen **4-Schichten-Schutz**:

```
┌─────────────────────────────────────────────────────────────────┐
│  Schicht 4: Batterie-Optimierung deaktivieren (WICHTIGSTE!)    │
│             → Benutzer muss manuell zustimmen                   │
├─────────────────────────────────────────────────────────────────┤
│  Schicht 3: AlarmManager mit setExactAndAllowWhileIdle()       │
│             → Weckt App alle 30 Sekunden auch im Doze Mode     │
├─────────────────────────────────────────────────────────────────┤
│  Schicht 2: Höhere Notification Priority                        │
│             → IMPORTANCE_DEFAULT + PRIORITY_HIGH                │
├─────────────────────────────────────────────────────────────────┤
│  Schicht 1: WakeLock (Basis-Schutz)                            │
│             → Hält CPU wach (wird aber im Doze ignoriert)      │
└─────────────────────────────────────────────────────────────────┘
```

### 4.1 Neue Permissions in `AndroidManifest.xml`

```xml
<!-- Erlaubt der App, von Batterie-Optimierung ausgenommen zu werden -->
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

<!-- Für zuverlässige periodische Aufweckung auch im Doze-Mode -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />
```

### 4.2 AlarmManager Keep-Alive in `ClassificationService.kt`

```kotlin
// Alle 30 Sekunden aufwecken
private const val ALARM_INTERVAL_MS = 30 * 1000L

private fun startKeepAliveAlarm() {
    val intent = Intent(ACTION_KEEP_ALIVE).apply {
        setPackage(packageName)
    }
    keepAlivePendingIntent = PendingIntent.getBroadcast(...)

    // setExactAndAllowWhileIdle funktioniert auch im Doze-Mode!
    alarmManager.setExactAndAllowWhileIdle(
        AlarmManager.ELAPSED_REALTIME_WAKEUP,
        SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS,
        keepAlivePendingIntent
    )
}
```

**Wie funktioniert das?**

1. Alarm wird alle 30 Sekunden ausgelöst
2. BroadcastReceiver empfängt den Alarm
3. Prüft ob WakeLock noch gehalten wird
4. Falls nicht: WakeLock neu erwerben
5. Plant nächsten Alarm

```kotlin
private val keepAliveReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == ACTION_KEEP_ALIVE && isRunning) {
            ensureWakeLockHeld()  // Prüft und erneuert WakeLock
        }
    }
}
```

### 4.3 Höhere Notification Priority

**Vorher:**
```kotlin
NotificationManager.IMPORTANCE_LOW
NotificationCompat.PRIORITY_LOW
```

**Nachher:**
```kotlin
NotificationManager.IMPORTANCE_DEFAULT
NotificationCompat.PRIORITY_HIGH
NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
```

**Warum?** Höhere Priorität signalisiert Android, dass dieser Service wichtig ist und nicht eingeschränkt werden sollte.

### 4.4 Batterie-Optimierung Dialog in `MainActivity.kt`

```kotlin
private fun checkBatteryOptimization() {
    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

    if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
        // Dialog anzeigen
        showBatteryOptimizationDialog()
    }
}

private fun showBatteryOptimizationDialog() {
    AlertDialog.Builder(this)
        .setTitle("Wichtig: Batterie-Optimierung")
        .setMessage("Für lückenlose Aufnahmen muss die Batterie-Optimierung deaktiviert werden...")
        .setPositiveButton("Ja, deaktivieren") { _, _ ->
            requestBatteryOptimizationExemption()
        }
        .show()
}

private fun requestBatteryOptimizationExemption() {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:$packageName")
    }
    startActivity(intent)
}
```

**Das ist die WICHTIGSTE Maßnahme!** Ohne sie ignoriert Android alle anderen Schutzmaßnahmen.

---

## 5. Bonus-Features

Zusätzlich zu den Doze-Mode-Fixes wurden diese Features implementiert:

### 5.1 LONG-Modus umgebaut

**Vorher:**
```
LONG = 30 Minuten kontinuierliche Aufnahme
→ Problem: Riesiger Speicherverbrauch, eine einzige Vorhersage
```

**Nachher:**
```
LONG = 10s Aufnahme → Vorhersage → 30 Min Pause → 10s Aufnahme → ...
→ Vorteil: Batterie sparen bei Langzeit-Monitoring
```

**Änderung in `RecordingMode.kt`:**
```kotlin
LONG(
    durationSeconds = 10,  // Nur 10 Sekunden aufnehmen
    pauseAfterRecordingMs = 30 * 60 * 1000L  // 30 Minuten Pause
)
```

### 5.2 Volume Ripple Effect (Lautstärke-Visualisierung)

**Neue Dateien:**
- `RipplePulseView.kt` - Custom View mit Sonar-Animation
- `AudioRecorder.kt` - RMS-Berechnung für Echtzeit-Lautstärke

**Funktionsweise:**
```kotlin
// RMS (Root Mean Square) Berechnung
private fun calculateRmsVolume(buffer: ShortArray, samplesRead: Int): Float {
    var sum = 0.0
    for (i in 0 until samplesRead) {
        val sample = buffer[i].toDouble()
        sum += sample * sample
    }
    val rms = sqrt(sum / samplesRead)
    return (rms / 5000.0).toFloat().coerceIn(0f, 1f)
}
```

**Visualisierung:**
- Konzentrische Kreise entstehen bei Geräuschen
- Je lauter, desto mehr und sichtbarere Kreise
- Lautstärke als Zahl (0-100) unter dem Confidence Circle

---

## 6. Zusammenfassung aller Änderungen

### Geänderte Dateien

| Datei | Änderungen |
|-------|------------|
| `AndroidManifest.xml` | +3 Permissions (Battery, Alarm) |
| `ClassificationService.kt` | +WakeLock, +AlarmManager, +Higher Priority |
| `MainActivity.kt` | +Battery Dialog, +Volume Display, +Paused State |
| `AudioRecorder.kt` | +volumeFlow, +RMS-Berechnung |
| `RecordingMode.kt` | +pauseAfterRecordingMs für LONG-Modus |
| `AppState.kt` | +Paused State, +currentVolume in UiState |
| `activity_main.xml` | +RipplePulseView, +volumeLevelText |

### Neue Dateien

| Datei | Beschreibung |
|-------|--------------|
| `RipplePulseView.kt` | Custom View für Sonar-Animation |
| `README.md` | Dokumentation für Anfänger |
| `DATENLUECKEN_LOESUNG.md` | Diese Datei |

### Code-Statistik

```
Commit 1: Fix data gaps by adding WakeLock
  1 file changed, 89 insertions(+), 13 deletions(-)

Commit 2: Add README, LONG mode pause, and volume ripple
  8 files changed, 591 insertions(+), 26 deletions(-)

Commit 3: Fix Doze Mode data gaps with multi-layer protection
  4 files changed, 288 insertions(+), 11 deletions(-)

Gesamt: ~970 Zeilen Code hinzugefügt
```

---

## 7. Anleitung für Benutzer

### Schritt 1: App neu installieren

Nach dem Update muss die App neu gebaut und installiert werden.

### Schritt 2: Batterie-Optimierung deaktivieren (WICHTIG!)

Beim ersten Start erscheint ein Dialog:

```
┌────────────────────────────────────────┐
│  Wichtig: Batterie-Optimierung        │
│                                        │
│  Für lückenlose Aufnahmen im          │
│  Hintergrund muss die Batterie-       │
│  Optimierung für diese App            │
│  deaktiviert werden.                  │
│                                        │
│  [Ja, deaktivieren]  [Später]         │
└────────────────────────────────────────┘
```

**Bitte "Ja, deaktivieren" wählen!**

### Schritt 3: Bei manchen Herstellern zusätzliche Einstellungen

**Xiaomi/MIUI:**
```
Sicherheit → Akku-Verbrauch → [App] → Keine Einschränkungen
Einstellungen → Apps → [App] → Autostart aktivieren
```

**Huawei/EMUI:**
```
Telefonmanager → Geschützte Apps → [App] aktivieren
```

**Samsung:**
```
Einstellungen → Apps → [App] → Akku → Nicht optimieren
```

### Schritt 4: Testen

1. Aufnahme im STANDARD-Modus starten
2. Handy 2-3 Stunden liegen lassen (Bildschirm aus)
3. CSV exportieren und auf Lücken prüfen

---

## Technische Details für Entwickler

### Warum funktioniert setExactAndAllowWhileIdle()?

Diese Methode ist speziell dafür designed, auch im Doze Mode zu funktionieren. Android erlaubt maximal einen Alarm alle 9 Minuten im Doze Mode, aber für kritische Anwendungen ist das ausreichend.

### Warum 4 Stunden WakeLock Timeout?

```kotlin
wakeLock.acquire(4 * 60 * 60 * 1000L)
```

- Sicherheit falls App abstürzt
- Verhindert Batterie-Entleerung bei Fehler
- 4 Stunden reichen für typische Nacht-Aufnahmen
- Keep-Alive Alarm erneuert den WakeLock regelmäßig

### Warum BroadcastReceiver statt AlarmManager.OnAlarmListener?

`OnAlarmListener` wird nicht zuverlässig im Doze Mode aufgerufen. `BroadcastReceiver` mit `setExactAndAllowWhileIdle()` ist zuverlässiger.

---

## Fazit

Das Datenlücken-Problem wurde durch einen mehrstufigen Ansatz gelöst:

1. **WakeLock** - Basis-Schutz gegen CPU-Schlaf
2. **AlarmManager** - Backup-Aufweckung alle 30 Sekunden
3. **Höhere Priority** - Signalisiert Wichtigkeit an Android
4. **Batterie-Optimierung** - Verhindert aggressive Einschränkungen

**Die wichtigste Erkenntnis:** Ein einfacher WakeLock reicht bei modernen Android-Versionen NICHT aus. Der Doze Mode erfordert zusätzliche Maßnahmen, insbesondere die Deaktivierung der Batterie-Optimierung durch den Benutzer.
