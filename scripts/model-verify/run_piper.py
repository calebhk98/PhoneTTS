import json, subprocess, sys, wave
import numpy as np
import onnxruntime as ort

PARA = ("The quick brown fox jumps over the lazy dog. "
        "Text to speech turns written words into natural sounding audio. "
        "This paragraph is long enough to produce several seconds of speech.")

cfg = json.load(open("piper.onnx.json"))
idmap = cfg["phoneme_id_map"]
sr = cfg["audio"]["sample_rate"]
BOS, EOS, PAD = "^", "$", "_"

def espeak_ipa(text):
    out = subprocess.run(["espeak-ng", "-q", "--ipa", "-v", "en-us", text],
                         capture_output=True, text=True).stdout
    return out.replace("\n", " ").strip()

phon = espeak_ipa(PARA)
ids = list(idmap[BOS])
for ch in phon:
    if ch in idmap:
        ids += idmap[ch]
        ids += idmap[PAD]
ids += idmap[EOS]

sess = ort.InferenceSession("piper.onnx", providers=["CPUExecutionProvider"])
inp = np.array([ids], dtype=np.int64)
lengths = np.array([inp.shape[1]], dtype=np.int64)
scales = np.array([0.667, 1.0, 0.8], dtype=np.float32)  # noise, length(speed), noise_w
audio = sess.run(None, {"input": inp, "input_lengths": lengths, "scales": scales})[0]
audio = np.asarray(audio).reshape(-1).astype(np.float32)

pcm = np.clip(audio, -1, 1)
pcm16 = (pcm * 32767).astype(np.int16)
with wave.open("piper_out.wav", "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
    w.writeframes(pcm16.tobytes())

dur = len(audio) / sr
kb = (len(pcm16) * 2) / 1024
print(f"PIPER: tokens={len(ids)} samples={len(audio)} sr={sr} "
      f"duration={dur:.2f}s size={kb:.1f}KB "
      f"peak={np.abs(audio).max():.3f} nan={np.isnan(audio).any()}")
print("PASS" if (kb > 1 and dur > 2 and not np.isnan(audio).any()) else "FAIL")
