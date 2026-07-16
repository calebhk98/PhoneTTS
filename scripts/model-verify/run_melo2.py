import re, wave
import numpy as np
import onnxruntime as ort

PARA = ("The quick brown fox jumps over the lazy dog. "
        "Text to speech turns written words into natural sounding audio. "
        "This paragraph is long enough to produce several seconds of speech.")
SR = 44100

# tokens.txt: "<symbol> <id>" per line -> symbol->id
sym2id = {}
for line in open("melo2_tokens.txt", encoding="utf-8"):
    parts = line.rstrip("\n").split(" ")
    if len(parts) == 2:
        sym2id[parts[0]] = int(parts[1])

# lexicon.txt: "<word> p1 p2 ... t1 t2 ..." (equal counts) -> word -> (phonemes, tones)
lexicon = {}
for line in open("melo2_lexicon.txt", encoding="utf-8"):
    parts = line.rstrip("\n").split()
    if len(parts) < 3:
        continue
    word = parts[0].lower()
    rest = parts[1:]
    half = len(rest) // 2
    phones, tones = rest[:half], rest[half:]
    if len(phones) == len(tones):
        lexicon[word] = (phones, [int(t) for t in tones])

def g2p(text):
    phones, tones = [], []
    for tok in re.findall(r"[a-zA-Z']+|[.,!?;:]", text.lower()):
        if tok in lexicon:
            p, t = lexicon[tok]
            phones += p; tones += t
        elif tok in sym2id:            # punctuation symbol, tone 0
            phones.append(tok); tones.append(0)
    return phones, tones

def intersperse_blank(seq):            # add_blank=1: [0, s0, 0, s1, ..., 0]
    out = [0] * (len(seq) * 2 + 1)
    out[1::2] = seq
    return out

phones, tones = g2p(PARA)
sym_ids = [sym2id.get(p, sym2id.get("UNK", 0)) for p in phones]
x = intersperse_blank(sym_ids)
tone_ids = intersperse_blank(tones)
L = len(x)

sess = ort.InferenceSession("melo2.onnx", providers=["CPUExecutionProvider"])
feeds = {
    "x": np.array([x], dtype=np.int64),
    "x_lengths": np.array([L], dtype=np.int64),
    "tones": np.array([tone_ids], dtype=np.int64),
    "sid": np.array([0], dtype=np.int64),
    "noise_scale": np.array([0.6], dtype=np.float32),
    "length_scale": np.array([1.0], dtype=np.float32),
    "noise_scale_w": np.array([0.8], dtype=np.float32),
}
audio = np.asarray(sess.run(None, feeds)[0]).reshape(-1).astype(np.float32)

pcm16 = (np.clip(audio, -1, 1) * 32767).astype(np.int16)
with wave.open("melo2_out.wav", "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm16.tobytes())

dur = len(audio) / SR
kb = (len(pcm16) * 2) / 1024
print(f"MELOTTS(MiaoMint en_v2): phones={len(phones)} x_len={L} samples={len(audio)} sr={SR} "
      f"duration={dur:.2f}s size={kb:.1f}KB peak={np.abs(audio).max():.3f} nan={np.isnan(audio).any()}")
print("PASS" if (kb > 1 and dur > 2 and np.abs(audio).max() > 0.01 and not np.isnan(audio).any()) else "FAIL")
