import json, subprocess, wave
import numpy as np
import onnxruntime as ort

PARA = ("The quick brown fox jumps over the lazy dog. "
        "Text to speech turns written words into natural sounding audio. "
        "This paragraph is long enough to produce several seconds of speech.")
SR = 24000

vocab = json.load(open("kokoro_tokenizer.json"))["model"]["vocab"]

def espeak_ipa(text):
    out = subprocess.run(["espeak-ng", "-q", "--ipa", "-v", "en-us", text],
                         capture_output=True, text=True).stdout
    return out.replace("\n", " ").strip()

phon = espeak_ipa(PARA)
tokens = [vocab[c] for c in phon if c in vocab]
tokens = tokens[:508]  # model max context is 512 incl the pad wraps
input_ids = np.array([[0, *tokens, 0]], dtype=np.int64)

style = np.fromfile("af_heart.bin", dtype=np.float32).reshape(510, 256)
ref = style[min(len(tokens), 509)].reshape(1, 256).astype(np.float32)
speed = np.array([1.0], dtype=np.float32)

sess = ort.InferenceSession("kokoro_fp32.onnx", providers=["CPUExecutionProvider"])
audio = sess.run(None, {"input_ids": input_ids, "style": ref, "speed": speed})[0]
audio = np.asarray(audio).reshape(-1).astype(np.float32)

pcm16 = (np.clip(audio, -1, 1) * 32767).astype(np.int16)
with wave.open("kokoro_out.wav", "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes(pcm16.tobytes())

dur = len(audio) / SR
kb = (len(pcm16) * 2) / 1024
print(f"KOKORO: tokens={len(tokens)} samples={len(audio)} sr={SR} "
      f"duration={dur:.2f}s size={kb:.1f}KB peak={np.abs(audio).max():.3f} nan={np.isnan(audio).any()}")
print("PASS" if (kb > 1 and dur > 2 and not np.isnan(audio).any()) else "FAIL")
