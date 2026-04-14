"""
Referenzwerte für Mel-Spektrogramm Vergleich App vs Python.
Generiert ein 440Hz Sinussignal und gibt die Log-Mel Werte aus.
"""
import torch
import torchaudio

sr = 32000
t = torch.linspace(0, 1, sr)
waveform = torch.sin(2 * torch.pi * 440 * t).unsqueeze(0)  # [1, 32000]

mel_transform = torchaudio.transforms.MelSpectrogram(
    sample_rate=32000,
    n_fft=4096,
    win_length=3072,
    hop_length=500,
    n_mels=256,
    f_min=0,
    f_max=None,
)

mel = mel_transform(waveform)
log_mel = (mel + 1e-5).log()

print(f"Shape: {log_mel.shape}")
print(f"Mel-Bin 0:   {log_mel[0, 0, :5].tolist()}")
print(f"Mel-Bin 128: {log_mel[0, 128, :5].tolist()}")
print(f"Mel-Bin 255: {log_mel[0, 255, :5].tolist()}")
print(f"min={log_mel.min():.4f}, max={log_mel.max():.4f}, mean={log_mel.mean():.4f}")
