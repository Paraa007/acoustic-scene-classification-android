# AVERAGE-Modus: Wie wird der Durchschnitt berechnet?

## Ziel

Statt eine einzelne 10-Sekunden-Aufnahme als Ganzes zu klassifizieren, wird die Aufnahme in **10 einzelne 1-Sekunden-Clips** zerlegt. Jeder Clip wird separat vom Modell bewertet. Am Ende werden alle Einzelergebnisse zu einem **gemittelten Gesamtergebnis** zusammengefuehrt.

---

## Ablauf Schritt fuer Schritt

### 1. Aufnahme (10 Sekunden)

Die App nimmt 10 Sekunden Audio auf (32.000 Samples/Sekunde = 320.000 Samples total).

### 2. Zerschneiden in 10 Clips

| Clip | Zeitbereich | Samples |
|------|-------------|---------|
| Clip 1 | 0s - 1s | 0 - 31.999 |
| Clip 2 | 1s - 2s | 32.000 - 63.999 |
| Clip 3 | 2s - 3s | 64.000 - 95.999 |
| ... | ... | ... |
| Clip 10 | 9s - 10s | 288.000 - 319.999 |

### 3. Einzelne Inferenz pro Clip

Jeder 1-Sekunden-Clip durchlaeuft die komplette ML-Pipeline:

**Audio** -> **Mel-Spektrogramm (FFT)** -> **PyTorch-Modell** -> **Wahrscheinlichkeitsverteilung (Softmax)**

Das Modell gibt fuer jeden Clip eine Wahrscheinlichkeit pro Klasse aus:

| Klasse | Clip 1 | Clip 2 | Clip 3 | ... | Clip 10 |
|--------|--------|--------|--------|-----|---------|
| Transit Vehicles | 0.05 | 0.10 | 0.08 | ... | 0.07 |
| Urban Waiting | 0.60 | 0.55 | 0.65 | ... | 0.58 |
| Nature | 0.02 | 0.03 | 0.01 | ... | 0.02 |
| Social | 0.15 | 0.12 | 0.10 | ... | 0.13 |
| ... | ... | ... | ... | ... | ... |

### 4. Durchschnittsberechnung

Fuer jede Klasse werden die Wahrscheinlichkeiten aller 10 Clips **addiert und durch 10 geteilt**:

```
Durchschnitt(Klasse) = (P_Clip1 + P_Clip2 + ... + P_Clip10) / 10
```

**Beispiel fuer "Urban Waiting":**

```
(0.60 + 0.55 + 0.65 + 0.58 + 0.62 + 0.57 + 0.61 + 0.59 + 0.63 + 0.58) / 10 = 0.598
```

### 5. Ergebnis

Die Klasse mit dem hoechsten Durchschnittswert wird als **Gesamtergebnis** angezeigt.

---

## Live-Visualisierung in der App

Waehrend der Berechnung zeigt die App **zwei Ebenen**:

- **Grosser Kreis (oben):** Zeigt den **laufenden Durchschnitt** -- aktualisiert sich nach jedem verarbeiteten Clip
- **10 kleine Kreise (unten):** Zeigen die **Einzelergebnisse** pro Sekunde -- fuellen sich nacheinander live

Der laufende Durchschnitt nach Clip N berechnet sich als:

```
Laufender Durchschnitt = Summe(Clip 1 bis N) / N
```

So sieht man in Echtzeit, wie sich die Vorhersage von Sekunde zu Sekunde entwickelt und stabilisiert.

---

## Warum dieser Ansatz?

| Vorteil | Erklaerung |
|---------|------------|
| **Robustheit** | Kurze Stoergeraeusche (z.B. Husten, Tuerklingel) beeinflussen nur 1 von 10 Clips statt die gesamte Aufnahme |
| **Transparenz** | Man sieht pro Sekunde, was das Modell "hoert" -- hilfreich fuer Analyse und Debugging |
| **Stabilere Vorhersage** | Der Durchschnitt glaettet Ausreisser und liefert ein zuverlaessigeres Ergebnis |

---

## Zusammenfassung als Formel

```
Ergebnis = argmax( (1/10) * SUM_{i=1}^{10} Softmax(Modell(MelSpec(Clip_i))) )
```

Sprich: Nimm den Softmax-Output jedes Clips, mittele die 10 Verteilungen, und waehle die Klasse mit der hoechsten gemittelten Wahrscheinlichkeit.

---

## Warum genau Probability Averaging? Vergleich der Methoden

Es gibt mehrere Moeglichkeiten, 10 Einzelvorhersagen zu einem Gesamtergebnis zu kombinieren. Hier die drei gaengigsten:

### 1. Probability Averaging (unsere Methode)

**Idee:** Die Softmax-Wahrscheinlichkeitsverteilungen aller Clips werden elementweise gemittelt.

```
P_avg(Klasse_k) = (1/N) * SUM P_i(Klasse_k)
Ergebnis = argmax(P_avg)
```

**Vorteile:**
- Beruecksichtigt die **gesamte Verteilung**, nicht nur die Top-Klasse
- Ein Clip mit 40% Natur und 35% Urban wird anders behandelt als einer mit 90% Natur
- Unsichere Vorhersagen bekommen weniger Gewicht als sichere
- Ergebnis ist selbst wieder eine gueltige Wahrscheinlichkeitsverteilung (summiert zu 1.0)

**Nachteil:**
- Ein einziger Clip mit extrem hoher Konfidenz (z.B. 99%) kann das Ergebnis dominieren

### 2. Majority Voting (Alternative)

**Idee:** Jeder Clip gibt eine Stimme fuer seine Top-Klasse ab. Die Klasse mit den meisten Stimmen gewinnt.

```
Ergebnis = mode(argmax(P_1), argmax(P_2), ..., argmax(P_10))
```

**Vorteile:**
- Einfach zu verstehen
- Robust gegen einzelne Ausreisser

**Nachteile:**
- Ignoriert die Konfidenz komplett (51% zaehlt gleich wie 99%)
- Verliert Information ueber die Verteilung
- Bei Gleichstand (z.B. 5x Natur, 5x Urban) keine klare Entscheidung

### 3. Gewichtetes Averaging (Alternative)

**Idee:** Clips mit hoeherer Konfidenz bekommen mehr Gewicht.

```
P_weighted(Klasse_k) = SUM(w_i * P_i(Klasse_k)) / SUM(w_i)
wobei w_i = max(P_i) (Konfidenz des Clips)
```

**Vorteile:**
- Sichere Clips zaehlen staerker als unsichere
- Theoretisch praeziser als einfacher Durchschnitt

**Nachteile:**
- Komplexer, schwerer erklaerbar
- Uebersichere Modelle (die immer >90% ausgeben) profitieren kaum

### Warum wir Probability Averaging gewaehlt haben

| Kriterium | Probability Avg | Majority Vote | Gewichtetes Avg |
|-----------|:-:|:-:|:-:|
| Einfach erklaerbar | Ja | Ja | Nein |
| Nutzt volle Verteilung | Ja | Nein | Ja |
| Ergebnis ist Wahrscheinlichkeit | Ja | Nein | Ja |
| Standard in der Literatur | Ja | Ja | Selten |
| Implementierungsaufwand | Gering | Gering | Mittel |

**Probability Averaging** ist der Standard-Ansatz in Ensemble-Methoden und DCASE-Challenges. Es ist einfach, behaelt die volle Verteilung und ist robust gegen einzelne Ausreisser. Die gemittelte Verteilung laesst sich direkt als Konfidenz anzeigen, was fuer die App-Visualisierung praktisch ist.
