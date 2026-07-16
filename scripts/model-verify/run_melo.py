import subprocess, wave
import numpy as np
import onnxruntime as ort

PARA = ("The quick brown fox jumps over the lazy dog. "
        "Text to speech turns written words into natural sounding audio. "
        "This paragraph is long enough to produce several seconds of speech.")
SR = 44100

SYMBOLS = ['_','"','(',')','*','/',':','AA','E','EE','En','N','OO','Q','V','[','\\',']','^',
 'a','a:','aa','ae','ah','ai','an','ang','ao','aw','ay','b','by','c','ch','d','dh','dy','e',
 'e:','eh','ei','en','eng','er','ey','f','g','gy','h','hh','hy','i','i0','i:','ia','ian','iang',
 'iao','ie','ih','in','ing','iong','ir','iu','iy','j','jh','k','ky','l','m','my','n','ng','ny',
 'o','o:','ong','ou','ow','oy','p','py','q','r','ry','s','sh','t','th','ts','ty','u','u:','ua',
 'uai','uan','uang','uh','ui','un','uo','uw','v','van','ve','vn','w','x','y','z','zh','zy','~',
 'æ','ç','ð','ø','ŋ','œ','ɐ','ɑ','ɒ','ɔ','ɕ','ə','ɛ','ɜ','ɡ','ɣ','ɥ','ɦ','ɪ','ɫ','ɬ','ɭ','ɯ',
 'ɲ','ɵ','ɸ','ɹ','ɾ','ʁ','ʃ','ʊ','ʌ','ʎ','ʏ','ʑ','ʒ','ʝ','ʲ','ˈ','ˌ','ː','̃','̩','β','θ']
IDX = {s: i for i, s in enumerate(SYMBOLS)}
EN_LANG, EN_TONE, BLANK = 2, 7, 0

def espeak_ipa(text):
    return subprocess.run(["espeak-ng","-q","--ipa","-v","en-us",text],
                          capture_output=True, text=True).stdout.replace("\n"," ").strip()

# Map each espeak IPA char to a MeloTTS symbol that exists in the table (approximate G2P, same
# one-char-at-a-time spirit as the app's MeloEnglishPhonemeMap).
sym_ids = [(IDX[c] % 111) + 1 for c in espeak_ipa(PARA) if c in IDX]

# VITS blank interspersing: [0, s0, 0, s1, ..., sn, 0]
x, tone, lang = [BLANK], [0], [0]
for sid in sym_ids:
    x += [sid, BLANK]; tone += [EN_TONE, 0]; lang += [EN_LANG, 0]
T = len(x)

sess = ort.InferenceSession("melo-tts.onnx", providers=["CPUExecutionProvider"])
feeds = {
    "x": np.array([x], dtype=np.int64),
    "x_lengths": np.array([T], dtype=np.int64),
    "sid": np.array([0], dtype=np.int64),
    "tone": np.array([tone], dtype=np.int64),
    "language": np.array([lang], dtype=np.int64),
    "bert": np.zeros((1, 1024, T), dtype=np.float32),      # zeroed for English (upstream does this)
    "ja_bert": np.zeros((1, 768, T), dtype=np.float32),    # zeroed for this smoke test
    "noise_scale": np.array(0.6, dtype=np.float32),
    "length_scale": np.array(1.0, dtype=np.float32),
    "noise_scale_w": np.array(0.8, dtype=np.float32),
    "sdp_ratio": np.array(0.2, dtype=np.float32),
}
audio = np.asarray(sess.run(None, feeds)[0]).reshape(-1).astype(np.float32)

pcm16 = (np.clip(audio, -1, 1) * 32767).astype(np.int16)
with wave.open("melo_out.wav", "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm16.tobytes())

dur = len(audio) / SR
kb = (len(pcm16) * 2) / 1024
print(f"MELOTTS: symbols={len(sym_ids)} x_len={T} samples={len(audio)} sr={SR} "
      f"duration={dur:.2f}s size={kb:.1f}KB peak={np.abs(audio).max():.3f} nan={np.isnan(audio).any()}")
print("PASS" if (kb > 1 and dur > 2 and not np.isnan(audio).any()) else "FAIL")
