#!/usr/bin/env python3
"""
Test-Script um zu prüfen, ob ein PyTorch-Modell MKLDNN-Operationen enthält.
Führe dieses Script aus, um das Modell zu testen, bevor du es in Android verwendest.
"""

import torch
import sys

def check_model_for_mkldnn(model_path: str):
    """
    Prüft, ob ein PyTorch-Modell MKLDNN-Operationen enthält.
    
    Args:
        model_path: Pfad zur .pt Modell-Datei
        
    Returns:
        True wenn MKLDNN-Ops gefunden, False sonst
    """
    print(f"📦 Lade Modell: {model_path}")
    print("=" * 80)
    
    try:
        # Lade Modell
        model = torch.jit.load(model_path)
        print("✅ Modell erfolgreich geladen")
        print()
        
        # Hole Code und Graph
        try:
            code = str(model.code)
        except:
            code = "N/A (code nicht verfügbar)"
        
        try:
            graph = str(model.graph)
        except:
            graph = "N/A (graph nicht verfügbar)"
        
        # Prüfe auf MKLDNN-Ops
        mkldnn_in_code = 'mkldnn' in code.lower()
        mkldnn_in_graph = 'mkldnn' in graph.lower()
        
        print("🔍 Prüfe auf MKLDNN-Operationen...")
        print()
        
        if mkldnn_in_code or mkldnn_in_graph:
            print("❌ FEHLER: Modell enthält MKLDNN-Operationen!")
            print()
            print("Gefundene MKLDNN-Operationen:")
            print("-" * 80)
            
            # Zeige problematische Zeilen
            if mkldnn_in_code:
                print("\n📄 Im Code gefunden:")
                lines = code.split('\n')
                for i, line in enumerate(lines, 1):
                    if 'mkldnn' in line.lower():
                        print(f"  Zeile {i}: {line.strip()}")
            
            if mkldnn_in_graph:
                print("\n📊 Im Graph gefunden:")
                lines = graph.split('\n')
                for i, line in enumerate(lines, 1):
                    if 'mkldnn' in line.lower():
                        print(f"  Zeile {i}: {line.strip()}")
            
            print()
            print("⚠️  DIESES MODELL KANN NICHT IN ANDROID VERWENDET WERDEN!")
            print("    Das Modell muss OHNE MKLDNN-Ops neu exportiert werden.")
            print()
            return True
        else:
            print("✅ OK: Modell enthält KEINE MKLDNN-Operationen")
            print()
            print("✅ Das Modell kann in Android verwendet werden!")
            print()
            return False
            
    except Exception as e:
        print(f"❌ Fehler beim Laden des Modells: {e}")
        print()
        import traceback
        traceback.print_exc()
        return None

def main():
    """Hauptfunktion"""
    
    if len(sys.argv) < 2:
        print("Usage: python3 TEST_MODEL_MKLDNN.py <path_to_model.pt>")
        print()
        print("Beispiele:")
        print("  python3 TEST_MODEL_MKLDNN.py model1.pt")
        print("  python3 TEST_MODEL_MKLDNN.py app/src/main/assets/model1.pt")
        sys.exit(1)
    
    model_path = sys.argv[1]
    
    print("=" * 80)
    print("PYTORCH MODELL MKLDNN-CHECK")
    print("=" * 80)
    print()
    
    result = check_model_for_mkldnn(model_path)
    
    print("=" * 80)
    
    if result is True:
        print("❌ ERGEBNIS: Modell enthält MKLDNN-Ops - NICHT für Android geeignet!")
        sys.exit(1)
    elif result is False:
        print("✅ ERGEBNIS: Modell ist OK - kann in Android verwendet werden!")
        sys.exit(0)
    else:
        print("❌ ERGEBNIS: Fehler beim Testen")
        sys.exit(1)

if __name__ == "__main__":
    main()
