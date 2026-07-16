import sys, wave
sys.path.append('third_party/Matcha-TTS')
import numpy as np
import torch
from cosyvoice.cli.cosyvoice import AutoModel

TTS_TEXT = "Text to speech turns written words into natural sounding audio."
PROMPT_TEXT = "希望你以后能够做的比我还好呦。"          # transcript of the bundled prompt wav
PROMPT_WAV = "./asset/zero_shot_prompt.wav"

print(">> loading CosyVoice2-0.5B (CPU, fp16 off)...", flush=True)
cosyvoice = AutoModel(model_dir='pretrained_models/CosyVoice2-0.5B',
                      load_jit=False, load_trt=False, load_vllm=False, fp16=False)
print(">> sample_rate", cosyvoice.sample_rate, flush=True)

chunks = []
for i, j in enumerate(cosyvoice.inference_zero_shot(TTS_TEXT, PROMPT_TEXT, PROMPT_WAV, stream=False)):
    chunks.append(j['tts_speech'])
    print(f">> got chunk {i}: {j['tts_speech'].shape}", flush=True)

audio = torch.cat(chunks, dim=1).squeeze().cpu().numpy().astype(np.float32)
sr = cosyvoice.sample_rate
pcm16 = (np.clip(audio, -1, 1) * 32767).astype(np.int16)
with wave.open("cosy_out.wav", "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes(pcm16.tobytes())

dur = len(audio) / sr
kb = (len(pcm16) * 2) / 1024
print(f"COSYVOICE2: samples={len(audio)} sr={sr} duration={dur:.2f}s size={kb:.1f}KB "
      f"peak={np.abs(audio).max():.3f} nan={np.isnan(audio).any()}", flush=True)
print("PASS" if (kb > 1 and dur > 2 and float(np.abs(audio).max()) > 0.01 and not np.isnan(audio).any()) else "FAIL", flush=True)
