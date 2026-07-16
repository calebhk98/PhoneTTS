import subprocess, wave, zipfile, io
import numpy as np
import onnxruntime as ort

PARA = ("The quick brown fox jumps over the lazy dog. "
        "Text to speech turns written words into natural sounding audio. "
        "This paragraph is long enough to produce several seconds of speech.")
SR = 24000

# StyleTTS2 / KittenTTS default symbol table (pad + punctuation + letters + IPA letters).
_pad = "$"
_punct = ';:,.!?¡¿—…"«»“” '
_letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
_ipa = ("ɑɐɒæɓʙβɔɕçɗɖðʤəɘɚɛɜɝɞɟʄɡɠɢʛɦɧħɥʜɨɪʝɭɬɫɮʟɱɯɰŋɳɲɴøɵɸθœɶʘɹɺɾɻʀʁɽʂʃʈʧʉʊ"
        "ʋⱱʌɣɤʍχʎʏʑʐʒʔʡʕʢǀǁǂǃˈˌːˑʼʴʰʱʲʷˠˤ˞↓↑→↗↘'̩'ᵻ")
symbols = [_pad] + list(_punct) + list(_letters) + list(_ipa)
vocab = {s: i for i, s in enumerate(symbols)}

def espeak_ipa(text):
    out = subprocess.run(["espeak-ng", "-q", "--ipa", "-v", "en-us", text],
                         capture_output=True, text=True).stdout
    return out.replace("\n", " ").strip()

phon = espeak_ipa(PARA)
tokens = [vocab[c] for c in phon if c in vocab]
input_ids = np.array([[0, *tokens, 0]], dtype=np.int64)

# voices.npz: 8 named (1,256) style embeddings; pick the first.
z = np.load("kitten_voices.npz")
style = z[z.files[0]].reshape(1, 256).astype(np.float32)
speed = np.array([1.0], dtype=np.float32)

sess = ort.InferenceSession("kitten.onnx", providers=["CPUExecutionProvider"])
audio = sess.run(None, {"input_ids": input_ids, "style": style, "speed": speed})[0]
audio = np.asarray(audio).reshape(-1).astype(np.float32)

pcm16 = (np.clip(audio, -1, 1) * 32767).astype(np.int16)
with wave.open("kitten_out.wav", "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes(pcm16.tobytes())

dur = len(audio) / SR
kb = (len(pcm16) * 2) / 1024
print(f"KITTEN: voice={z.files[0]} tokens={len(tokens)} samples={len(audio)} sr={SR} "
      f"duration={dur:.2f}s size={kb:.1f}KB peak={np.abs(audio).max():.3f} nan={np.isnan(audio).any()}")
print("PASS" if (kb > 1 and dur > 2 and not np.isnan(audio).any()) else "FAIL")
