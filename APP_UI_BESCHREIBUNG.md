# 📱 App UI Beschreibung: Acoustic Scene Classifier

## Überblick
Diese Android-App klassifiziert akustische Umgebungen in Echtzeit mithilfe eines PyTorch Mobile Modells. Die App verwendet ein Deep-Learning-Modell, um Audio-Aufnahmen in eine von 8 Kategorien zu klassifizieren.

---

## 🎨 Design & Farbschema

### Dark Theme
- **Hintergrundfarbe**: Fast schwarz (#0D0D0D)
- **Karten/Hintergrund**: Dunkelgrau (#1A1A1A)
- **Akzentfarbe**: Dunkelgrün (#1B5E20) für Buttons und Highlights
- **Text Primär**: Weiß (#FFFFFF)
- **Text Sekundär**: Hellgrau (#B0B0B0)

### UI-Komponenten
- **Material Design 3** Komponenten
- **MaterialCardView** für alle Container
- **MaterialButton** für Interaktionen
- **Rounded Corners**: 12-20dp Radius
- **Scrollbare Ansicht** (NestedScrollView)

---

## 📐 Startseite Layout (activity_main.xml)

Die Startseite ist vertikal in mehrere Karten unterteilt, die von oben nach unten aufgelistet sind:

### 1. **App Header** (oben)
- **Links**: App-Name "Acoustic Scene by FZI" (20sp, fett, weiß)
- **Rechts**: Model Status Label (12sp, grau) - zeigt "Loading Model..." oder "Model Loaded"

---

### 2. **Circular Confidence Indicator Card** (großer zentraler Bereich)
- **Dunkelgraue Karte** mit 20dp abgerundeten Ecken
- **Inhalt**:
  - **ConfidenceCircleView**: Großer runder Kreis (200dp x 200dp) in der Mitte
    - Zeigt Konfidenz-Prozentzahl als animierten Kreis an
    - Zahl in der Mitte (z.B. "87%")
    - Prozentzeichen unterhalb der Zahl
  - **currentSceneLabel**: Erkannte Klasse darunter (24sp, fett, weiß)
    - Format: "🌲 Außen - naturbetont" (mit Emoji und vollständigem Namen)
    - Standardmäßig `visibility="gone"` (wird bei Ergebnis sichtbar)

---

### 3. **Info Card** (Details zu aktueller Vorhersage)
- **Standardmäßig verborgen** (`visibility="gone"`)
- **Wird bei erfolgreicher Klassifikation angezeigt**
- **Enthält 3 Zeilen**:
  - **Aufnahmedauer**: "Aufnahmedauer" (links, grau) | "10.0 s" (rechts, weiß, fett)
  - **Analyse-Zeit**: "Analyse-Zeit" (links, grau) | "0.34 s" (rechts, weiß, fett)
  - **Modell-Konfidenz**: "Modell-Konfidenz" (links, grau) | "87%" (rechts, grün, fett)

---

### 4. **Top 3 Predictions Card**
- **Standardmäßig verborgen** (`visibility="gone"`)
- **Titel**: "Top Predictions" (16sp, fett, weiß)
- **Dynamischer Container** (`predictionsContainer`):
  - Zeigt die Top 3 Vorhersagen als Liste an
  - Jede Vorhersage zeigt:
    - Klassen-Emoji
    - Klassen-Name (vollständig)
    - Konfidenz-Prozent

---

### 5. **Recording Mode Selection Card**
- **Titel**: "Aufnahme-Modus" (14sp, fett, weiß)
- **4 Buttons horizontal angeordnet** (alle gleich breit mit `layout_weight="1"`):
  1. **Fast (1s)** - Grauer Hintergrund, inaktiv
  2. **Medium (5s)** - Grauer Hintergrund, inaktiv
  3. **Standard (10s)** - Grauer Hintergrund, inaktiv
  4. **Long (30min)** - **Grün hervorgehoben** (aktiv), weißer Text
- **Aktiver Button** wird grün mit weißem Text markiert
- **Inaktive Buttons** sind grau mit grauem Text

---

### 6. **Main Control Button** (großer grüner Button)
- **Text**: "Start Recording" / "Stop Recording" (18sp)
- **Icon**: Mikrofon-Symbol links vom Text
- **Hintergrund**: Dunkelgrün (#1B5E20)
- **Textfarbe**: Weiß
- **Padding**: 24dp
- **Abgerundete Ecken**: 16dp

---

### 7. **Timer Progress Bar** (während Aufnahme)
- **Standardmäßig verborgen** (`visibility="gone"`)
- **LinearProgressIndicator** (grüner Balken)
- **Wird während Aufnahme sichtbar** und zeigt Fortschritt (0-100%)

---

### 8. **Timer Text** (Countdown)
- **Standardmäßig verborgen** (`visibility="gone"`)
- **Text**: "Timer: 5s" (18sp, fett, grün)
- **Wird während Aufnahme sichtbar** mit Countdown

---

### 9. **Status Card**
- **Kleine dunkelgraue Karte**
- **Status Label**: "Idle" / "Recording" / "Processing" (14sp, fett, grau)
- Zeigt aktuellen App-Status an

---

### 10. **Statistics Card** (Session-Statistiken)
- **Standardmäßig verborgen** (`visibility="gone"`)
- **Titel**: "Session Statistics" (16sp, fett, weiß)
- **Enthält 2 Zeilen**:
  - **Total Classifications**: "Total Classifications" (links, grau) | Anzahl (rechts, grün, fett)
  - **Avg. Inference Time**: "Avg. Inference Time" (links, grau) | "0.34 s" (rechts, grün, fett)

---

### 11. **History Card** (immer sichtbar, am Ende)
- **Standardmäßig sichtbar** (`visibility="visible"`)
- **Titel**: "History" (18sp, fett, weiß)
- **3 Buttons horizontal** (unter dem Titel):
  1. **"Verlauf speichern"** (grauer Button)
  2. **"📤 CSV Export"** (grauer Button)
  3. **"Statistiken"** (grauer Button)
- **No History Text**: "No classification history yet" (grau, sichtbar wenn leer)
- **NestedScrollView** (max. Höhe 400dp) für History-Liste:
  - **historyContainer**: Dynamischer Container für History-Einträge
  - Jeder Eintrag zeigt:
    - Zeitstempel (z.B. "14:25:45")
    - Erkannte Klasse mit Emoji
    - Konfidenz-Prozent
    - Als Karten mit abgerundeten Ecken

---

## 🎯 Benutzer-Fluss

### Initial State (App gestartet)
1. App zeigt Header mit "Acoustic Scene by FZI" und "Loading Model..."
2. Große Confidence Circle Card (leer/0%)
3. Recording Mode Buttons (Long ist standardmäßig aktiv)
4. Großer grüner "Start Recording" Button
5. Status Card mit "Idle"
6. History Card am Ende (leer oder mit vorherigen Einträgen)

### Während Recording
1. Button ändert sich zu "Stop Recording"
2. Timer Progress Bar wird sichtbar und füllt sich
3. Timer Text zeigt Countdown (z.B. "Timer: 8s")
4. Status ändert sich zu "Recording"
5. Confidence Circle kann sich währenddessen aktualisieren

### Nach Klassifikation
1. **Info Card** wird sichtbar mit Details
2. **Top 3 Predictions Card** wird sichtbar
3. **Confidence Circle** zeigt Konfidenz und Ergebnis
4. **currentSceneLabel** zeigt erkannte Klasse mit Emoji
5. Neuer Eintrag in **History Container** wird hinzugefügt
6. **Statistics Card** wird aktualisiert

---

## 🎨 Spezielle UI-Komponenten

### ConfidenceCircleView (Custom View)
- **Kreisförmige Anzeige** für Konfidenz
- **Größe**: 200dp x 200dp
- **Anzeige**:
  - Große Prozentzahl in der Mitte (z.B. "87")
  - Prozentzeichen darunter
  - Animierter Kreis-Border (optional)

### MaterialCardView
- **Hintergrund**: Dunkelgrau (#1A1A1A)
- **Corner Radius**: 12-20dp
- **Elevation**: 0dp (flach)
- **Padding**: 16-32dp je nach Karte

### MaterialButton
- **Inaktiv**: Grauer Hintergrund (#2A2A2A), grauer Text
- **Aktiv**: Grüner Hintergrund (#1B5E20), weißer Text
- **Text Size**: 11-18sp je nach Button
- **Corner Radius**: 12dp

---

## 📊 8 Acoustic Scene Klassen

Die App klassifiziert in folgende Kategorien:

1. **🚗 Transit - Fahrzeuge/draußen** (Transit_Vehicles)
2. **🏙️ Außen-urban & Transit-Gebäude/Wartezonen** (Urban_Waiting)
3. **🌲 Außen - naturbetont** (Nature)
4. **👥 Innen - Soziale Umgebung** (Social)
5. **💼 Innen - Arbeitsumgebung** (Work)
6. **🛒 Innen - Kommerzielle/belebte Umgebung** (Commercial)
7. **⚽ Innen - Freizeit/Sport** (Leisure_Sport)
8. **🎭 Innen - Kultur/Freizeit ruhig** (Culture_Quiet)

Jede Klasse hat:
- **Emoji** zur visuellen Darstellung
- **Vollständigen Namen** (für Anzeige)
- **Kurzen Namen** (für UI-Compact-Modus)
- **Eigene Farbe** (für farbige Hervorhebung)

---

## 🔄 Interaktive Elemente

### Buttons
- **Start/Stop Recording**: Startet/stoppt kontinuierliche Klassifikation
- **Mode Buttons**: Wechselt zwischen Fast (1s), Medium (5s), Standard (10s), Long (30min)
- **Export Button**: Exportiert alle Vorhersagen als CSV
- **Statistics Button**: Zeigt Dialog mit detaillierten Statistiken
- **Save History Button**: Speichert aktuellen Verlauf

### Status Updates
- **Model Status**: "Loading Model..." → "Model Loaded"
- **Recording Status**: "Idle" → "Recording" → "Processing" → "Idle"
- **Progress Bar**: Zeigt Fortschritt während Aufnahme
- **Timer**: Countdown während Aufnahme

---

## 📱 Scrollbares Layout

Die gesamte Startseite ist in einem **NestedScrollView** eingebettet:
- **Alle Karten** sind vertikal gestapelt
- **History Container** hat max. Höhe 400dp und ist scrollbar
- **Gesamte Seite** ist scrollbar, falls Content über Bildschirmhöhe geht

---

## 🎯 Wichtige Hinweise

1. **Alle Cards** beginnen als `gone` oder `visible` je nach State
2. **Dynamische Inhalte** werden programmatisch gefüllt (z.B. History, Top 3 Predictions)
3. **Dark Theme** durchgehend verwendet
4. **Grüne Akzentfarbe** für aktive Elemente und wichtige Informationen
5. **Material Design 3** Komponenten für moderne UI
6. **Responsive Layout** mit `layout_weight` für gleichmäßige Button-Verteilung

---

## 🔍 Technische Details

- **Layout-Datei**: `activity_main.xml`
- **Activity**: `MainActivity.kt`
- **ViewModel**: `MainViewModel.kt` (MVVM-Pattern)
- **State Management**: `UiState` und `AppState` sealed classes
- **Coroutines**: Für asynchrone Operationen (Recording, Inference)
- **LiveData/StateFlow**: Für UI-Updates

---

## 📝 Zusammenfassung

Die Startseite bietet eine **klare, hierarchische Struktur**:
1. **Oben**: App-Header mit Status
2. **Mitte**: Großer Confidence Indicator (Haupt-Fokus)
3. **Darunter**: Detaillierte Informationen (Info Card, Top 3 Predictions)
4. **Kontrolle**: Mode-Auswahl und Start/Stop Button
5. **Unten**: History und Statistiken

Das Design ist **modern, dunkel und benutzerfreundlich** mit klaren visuellen Hierarchien und konsistenter Farbgebung.
