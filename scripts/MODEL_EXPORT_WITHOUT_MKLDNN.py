"""
Script zum Exportieren des PyTorch Modells OHNE MKLDNN-Optimierungen
für Android PyTorch Mobile.

WICHTIG: Dieses Script muss verwendet werden, um das Modell zu exportieren,
da MKLDNN-Ops in PyTorch Mobile nicht unterstützt werden.
"""

import torch

# Deaktiviere MKLDNN vor dem Laden des Modells
torch.backends.mkldnn.enabled = False

# Lade dein trainiertes Modell
# ANPASSEN: Importiere dein Modell hier
from your_model import YourModel  # ← Hier dein Modell importieren

model = YourModel()
model.load_state_dict(torch.load('path/to/your/trained_model.pth'))
model.eval()

# Stelle sicher, dass MKLDNN deaktiviert ist
torch.backends.mkldnn.enabled = False

# Erstelle Beispiel-Input (Shape: [1, 1, 320000])
example_input = torch.randn(1, 1, 320000)

# Trace das Modell OHNE MKLDNN
print("Tracing model without MKLDNN optimizations...")
traced_model = torch.jit.trace(model, example_input)

# Optimiere für Mobile (optional, aber empfohlen)
# WICHTIG: Diese Optimierung sollte KEINE MKLDNN-Ops einführen
print("Optimizing for mobile...")
try:
    from torch.utils.mobile_optimizer import optimize_for_mobile
    optimized_model = optimize_for_mobile(traced_model)
    print("Mobile optimization successful")
except Exception as e:
    print(f"Mobile optimization failed: {e}")
    print("Using non-optimized model...")
    optimized_model = traced_model

# Speichere das Modell
output_path = 'model1.pt'
optimized_model.save(output_path)
print(f"Model saved to {output_path}")

# Teste das Modell
print("\nTesting exported model...")
test_input = torch.randn(1, 1, 320000)
with torch.no_grad():
    output = optimized_model(test_input)
print(f"Output shape: {output.shape}")
print(f"Output sample: {output[0, :5]}")  # Erste 5 Werte
print("\n✅ Model export successful!")
