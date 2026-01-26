# Acoustic Scene Classification App

Eine Android-App zur Echtzeit-Klassifikation akustischer Szenen mit PyTorch Mobile.

## Das WakeLock-Problem und seine Lösung

### Was war das Problem?

Die App hatte **Datenlücken** - zwischen den Messungen vergingen manchmal Minuten statt der eingestellten Sekunden.

### Warum passierte das?

Android ist sehr sparsam mit der Batterie. Wenn der Bildschirm ausgeht, denkt Android:
> "Niemand schaut auf das Handy, also kann ich die CPU schlafen legen."

**Das Problem:** Unsere App braucht die CPU für Aufnahmen - auch wenn der Bildschirm aus ist!

```
VORHER (ohne WakeLock):
┌─────────────────────────────────────────────────────────┐
│  Aufnahme läuft...  │  Bildschirm aus  │  CPU schläft  │
│        ✓            │       😴         │      ❌       │
│                     │                  │               │
│  10s    20s    30s    ???    ???    180s (3 min später!)
│   ↑      ↑      ↑                      ↑
│   OK     OK     OK     LÜCKE!         Wieder wach
└─────────────────────────────────────────────────────────┘

NACHHER (mit WakeLock):
┌─────────────────────────────────────────────────────────┐
│  Aufnahme läuft...  │  Bildschirm aus  │  CPU aktiv!   │
│        ✓            │       🔒         │      ✓        │
│                     │                  │               │
│  10s    20s    30s    40s    50s    60s    70s ...
│   ↑      ↑      ↑      ↑      ↑      ↑      ↑
│   OK     OK     OK     OK     OK     OK     OK
└─────────────────────────────────────────────────────────┘
```

### Was ist ein WakeLock?

Ein **WakeLock** ist wie ein "Bitte nicht stören"-Schild für die CPU:

```kotlin
// So sagt man Android: "Lass die CPU wach!"
val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
wakeLock = powerManager.newWakeLock(
    PowerManager.PARTIAL_WAKE_LOCK,  // Nur CPU, nicht Bildschirm
    "MeineApp::MeinWakeLock"
)
wakeLock.acquire()  // WakeLock aktivieren
```

#### Arten von WakeLocks:

| WakeLock-Typ | CPU | Bildschirm | Tastatur |
|--------------|-----|------------|----------|
| `PARTIAL_WAKE_LOCK` | ✓ An | Aus | Aus |
| `SCREEN_DIM_WAKE_LOCK` | ✓ An | Dim | Aus |
| `SCREEN_BRIGHT_WAKE_LOCK` | ✓ An | Hell | Aus |
| `FULL_WAKE_LOCK` | ✓ An | Hell | ✓ An |

**Wir nutzen `PARTIAL_WAKE_LOCK`** - das spart am meisten Batterie, hält aber die CPU wach.

### Wo wurde der Fix implementiert?

**Datei:** `app/src/main/java/com/fzi/acousticscene/service/ClassificationService.kt`

```kotlin
// 1. Variable für WakeLock
private var wakeLock: PowerManager.WakeLock? = null

// 2. WakeLock erwerben beim Start
private fun acquireWakeLock() {
    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "AcousticScene::ClassificationWakeLock"
    ).apply {
        acquire(4 * 60 * 60 * 1000L)  // Max 4 Stunden (Sicherheit)
    }
}

// 3. WakeLock freigeben beim Stop
private fun releaseWakeLock() {
    wakeLock?.let { lock ->
        if (lock.isHeld) {
            lock.release()
        }
    }
    wakeLock = null
}
```

### Wichtig für Anfänger: Die 3 Schritte

1. **Permission im Manifest** (war schon da):
```xml
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

2. **WakeLock erwerben** wenn du ihn brauchst:
```kotlin
wakeLock.acquire()
```

3. **WakeLock freigeben** wenn du fertig bist (SEHR WICHTIG!):
```kotlin
wakeLock.release()
```

> ⚠️ **Warnung:** Vergiss nie, den WakeLock freizugeben! Sonst bleibt die CPU immer aktiv und die Batterie ist schnell leer.

### Timeout als Sicherheitsnetz

Wir nutzen einen 4-Stunden-Timeout:
```kotlin
wakeLock.acquire(4 * 60 * 60 * 1000L)  // 4 Stunden in Millisekunden
```

Falls die App abstürzt und `release()` nie aufgerufen wird, gibt Android den WakeLock nach 4 Stunden automatisch frei.

---

## Aufnahme-Modi

| Modus | Aufnahme-Dauer | Pause | Anwendungsfall |
|-------|---------------|-------|----------------|
| SCHNELL | 1 Sekunde | Keine | Echtzeit-Monitoring |
| MITTEL | 5 Sekunden | Keine | Ausgewogen |
| STANDARD | 10 Sekunden | Keine | Normale Nutzung |
| LANG | 10 Sekunden | 30 Minuten | Langzeit-Monitoring (Batterie sparen) |

---

## Weitere Android-Konzepte für Anfänger

### Foreground Service

Ein **Foreground Service** ist ein Service, der dem Nutzer angezeigt wird (via Notification). Android killt ihn nicht so schnell wie normale Services.

```kotlin
// Service als Foreground starten
startForeground(NOTIFICATION_ID, notification)
```

### Doze Mode (Android 6+) - KRITISCH!

Android versetzt das Gerät in einen **Doze-Modus** wenn:
- Bildschirm aus
- Gerät liegt still (keine Bewegung)
- Nicht am Ladekabel

Im Doze-Modus:
- Netzwerk-Zugriff eingeschränkt
- Jobs/Alarms verzögert
- **WakeLocks werden ignoriert!** (auch bei Foreground Services!)

**Das Problem:** Selbst mit WakeLock und Foreground Service kann Android nach ca. 2-3 Stunden
die App einschränken → Datenlücken entstehen.

**Unsere mehrstufige Lösung:**

1. **WakeLock** - Hält CPU wach (Basis-Schutz)
2. **Foreground Service mit HIGH Priority** - Signalisiert Wichtigkeit
3. **AlarmManager mit setExactAndAllowWhileIdle()** - Periodische Aufweckung (alle 30s)
4. **Batterie-Optimierung deaktivieren** - **WICHTIGSTE MASSNAHME!**

### Batterie-Optimierung deaktivieren (PFLICHT!)

Ohne diese Einstellung werden die anderen Maßnahmen vom System ignoriert!

**Beim ersten Start** der App erscheint ein Dialog. Bitte "Ja, deaktivieren" wählen!

**Manuell prüfen/ändern:**

**Stock Android:**
```
Einstellungen → Apps → [App-Name] → Akku → Nicht optimieren
```

**Samsung:**
```
Einstellungen → Apps → [App-Name] → Akku → Akkunutzung optimieren → Alle → [App-Name] → Nicht optimieren
```

**Xiaomi/MIUI:**
```
Einstellungen → Apps → App verwalten → [App-Name] → Autostart aktivieren
Sicherheit → Akku-Verbrauch → [App-Name] → Keine Einschränkungen
```

**Huawei/EMUI:**
```
Einstellungen → Apps → [App-Name] → Akku → Start → Manuell verwalten → Alles aktivieren
Telefonmanager → Geschützte Apps → [App-Name] aktivieren
```

**OnePlus/OxygenOS:**
```
Einstellungen → Akku → Akku-Optimierung → [App-Name] → Nicht optimieren
```

### App Standby

Wenn der Nutzer eine App lange nicht verwendet, wird sie in den **App Standby** versetzt. Die App kann dann nur selten im Hintergrund arbeiten.

**Tipp:** Der Nutzer kann die App von der Batterieoptimierung ausnehmen:
> Einstellungen → Apps → [App] → Akku → Nicht optimieren

---

## Projektstruktur

```
app/src/main/java/com/fzi/acousticscene/
├── MainActivity.kt              # Haupt-UI
├── WelcomeActivity.kt           # Willkommens-Screen
├── HistoryActivity.kt           # Verlauf anzeigen
├── service/
│   └── ClassificationService.kt # Hintergrund-Service (WakeLock hier!)
├── audio/
│   ├── AudioRecorder.kt         # Audio-Aufnahme
│   └── MelSpectrogramProcessor.kt
├── ui/
│   ├── MainViewModel.kt         # Aufnahme-Logik
│   ├── ConfidenceCircleView.kt  # Kreis-Animation
│   └── RipplePulseView.kt       # Lautstärke-Visualisierung
├── ml/
│   └── ModelInference.kt        # PyTorch Inferenz
├── data/
│   └── PredictionRepository.kt  # Daten-Speicherung
└── model/
    ├── RecordingMode.kt         # Aufnahme-Modi
    ├── SceneClass.kt            # 8 Szenen-Klassen
    └── PredictionRecord.kt      # CSV-Datenformat
```

---

## Szenen-Klassen (DCASE 2025)

| Klasse | Beschreibung | Emoji |
|--------|--------------|-------|
| TRANSIT_VEHICLES | Fahrzeuge, Transport | 🚗 |
| URBAN_WAITING | Urbanes Warten (Haltestelle) | 🚏 |
| NATURE | Natur, Outdoor | 🌳 |
| SOCIAL | Soziale Umgebungen | 👥 |
| WORK | Arbeitsumgebung | 💼 |
| COMMERCIAL | Geschäfte, Einkaufen | 🛒 |
| LEISURE_SPORT | Freizeit, Sport | ⚽ |
| CULTURE_QUIET | Kultur, ruhige Umgebung | 🎭 |

---

## Troubleshooting

### Immer noch Datenlücken?

1. **Batterieoptimierung deaktivieren:**
   - Einstellungen → Apps → Acoustic Scene → Akku → Nicht optimieren

2. **Autostart erlauben** (bei manchen Herstellern):
   - Xiaomi: Sicherheit → Autostart
   - Huawei: Telefonmanager → Autostart
   - Samsung: Einstellungen → Akku → App-Optimierung

3. **Logcat prüfen:**
   ```
   adb logcat | grep "ClassificationService"
   ```
   Du solltest sehen:
   ```
   D/ClassificationService: WakeLock acquired successfully - CPU will stay active!
   ```

### App stürzt ab?

Prüfe Logcat auf Fehler:
```
adb logcat *:E | grep -i "acoustic\|fatal\|exception"
```

---

## Lizenz

Dieses Projekt wurde im Rahmen von DCASE 2025 (Detection and Classification of Acoustic Scenes and Events) entwickelt.
