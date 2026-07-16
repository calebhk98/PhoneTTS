// Minimal driver proving CrispASR's native ggml CosyVoice3 pipeline end-to-end.
// Loads the 4-GGUF stack, runs text -> 24 kHz PCM via the public C ABI, writes a WAV.
#include "cosyvoice3_tts.h"
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <string>
#include <vector>
#include <cmath>

static void write_wav(const char* path, const float* pcm, int n, int sr) {
    FILE* f = fopen(path, "wb");
    if (!f) { fprintf(stderr, "cannot open %s\n", path); return; }
    auto w32 = [&](uint32_t v){ fwrite(&v, 4, 1, f); };
    auto w16 = [&](uint16_t v){ fwrite(&v, 2, 1, f); };
    uint32_t data_bytes = (uint32_t)n * 2; // int16 mono
    fwrite("RIFF", 1, 4, f); w32(36 + data_bytes); fwrite("WAVE", 1, 4, f);
    fwrite("fmt ", 1, 4, f); w32(16); w16(1); w16(1);
    w32((uint32_t)sr); w32((uint32_t)sr * 2); w16(2); w16(16);
    fwrite("data", 1, 4, f); w32(data_bytes);
    for (int i = 0; i < n; i++) {
        float s = pcm[i]; if (s > 1.f) s = 1.f; if (s < -1.f) s = -1.f;
        int16_t v = (int16_t)lrintf(s * 32767.f); fwrite(&v, 2, 1, f);
    }
    fclose(f);
}

int main(int argc, char** argv) {
    const char* dir   = argc > 1 ? argv[1] : ".";
    const char* text  = argc > 2 ? argv[2] : "Hello, this is a test of on-device text to speech.";
    const char* voice = argc > 3 ? argv[3] : "fleurs-en";
    const char* out   = argc > 4 ? argv[4] : "cv3_out.wav";

    std::string llm  = std::string(dir) + "/cosyvoice3-llm-q4_k.gguf";
    std::string flow = std::string(dir) + "/cosyvoice3-flow-q8_0.gguf";
    std::string hift = std::string(dir) + "/cosyvoice3-hift-f16.gguf";
    std::string vox  = std::string(dir) + "/cosyvoice3-voices.gguf";

    auto cp = cosyvoice3_tts_context_default_params();
    cp.verbosity = 1;
    cp.n_threads = 4;
    // CV3 greedy decode falls into a documented "silent_tokens" loop; the RAS sampler needs a
    // positive temperature to engage (CrispASR's backend forces 0 -> 0.8 for the same reason).
    cp.temperature = 0.8f;
    cp.seed = 42;

    fprintf(stderr, "[driver] init LLM %s\n", llm.c_str());
    auto* ctx = cosyvoice3_tts_init_from_file(llm.c_str(), cp);
    if (!ctx) { fprintf(stderr, "LLM init failed\n"); return 1; }

    fprintf(stderr, "[driver] init flow\n");
    if (cosyvoice3_tts_init_flow_from_file(ctx, flow.c_str()) != 0) { fprintf(stderr, "flow init failed\n"); return 1; }
    fprintf(stderr, "[driver] init hift\n");
    if (cosyvoice3_tts_init_hift_from_file(ctx, hift.c_str()) != 0) { fprintf(stderr, "hift init failed\n"); return 1; }
    fprintf(stderr, "[driver] init voices\n");
    if (cosyvoice3_tts_init_voices_from_file(ctx, vox.c_str()) != 0) { fprintf(stderr, "voices init failed\n"); return 1; }

    int nv = cosyvoice3_tts_n_voices(ctx);
    fprintf(stderr, "[driver] %d voices:", nv);
    for (int i = 0; i < nv; i++) fprintf(stderr, " %s", cosyvoice3_tts_voice_name(ctx, i));
    fprintf(stderr, "\n");

    fprintf(stderr, "[driver] synth voice=%s text=\"%s\"\n", voice, text);
    int n = 0;
    float* pcm = cosyvoice3_tts_synth(ctx, text, voice, &n);
    if (!pcm || n <= 0) { fprintf(stderr, "synth failed (n=%d)\n", n); return 1; }

    // Report basic audio stats so a caller can confirm it's real signal, not silence.
    double sum2 = 0; float peak = 0;
    for (int i = 0; i < n; i++) { float a = fabsf(pcm[i]); if (a > peak) peak = a; sum2 += (double)pcm[i]*pcm[i]; }
    double rms = sqrt(sum2 / n);
    fprintf(stderr, "[driver] samples=%d dur=%.2fs peak=%.4f rms=%.4f\n", n, n / 24000.0, peak, rms);

    write_wav(out, pcm, n, 24000);
    fprintf(stderr, "[driver] wrote %s\n", out);
    free(pcm);
    cosyvoice3_tts_free(ctx);
    return 0;
}
