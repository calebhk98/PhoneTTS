# Hugging Face Integration: Model Discovery and Download

**Date:** July 16, 2026  
**Status:** Research (not implementation)  
**Scope:** Feasibility and effort estimate for an in-app "model download" page that browses Hugging Face TTS models and integrates with PhoneTTS's existing pipeline.

---

## Summary

Adding an in-app HF model browser is **straightforward in principle** because HF's HTTP API is simple and public, and the app already has all the plumbing (manifest parsing, SHA-256 verification, auto-load → `inspect()` → `resolve()` pipeline). The hard part is **mapping arbitrary HF repos to the app's 5 engine families** and handling the cases where a repo is ambiguous or missing critical companion files.

**Recommended first step:** Start with a **curated in-app list** (20-50 popular HF TTS models, pre-mapped to engines) backed by HF HTTP calls. This gives users immediate value, requires minimal mapping logic, and avoids the "did I identify this model correctly?" problem. Phased rollout to full browsing comes later.

---

## 1. Hugging Face Hub HTTP API - Listing Models

### `/api/models` - Browse by pipeline tag + filters

```
GET /api/models
  ?pipeline_tag=text-to-speech
  &library=onnx          # (optional) filter by runtime library
  &tags=multilingual     # (optional) can chain multiple times
  &sort=downloads        # (optional) downloads, likes, created_at, etc.
  &direction=-1          # -1 = descending
  &limit=50              # per_page; max is likely 50-100
  &offset=0              # for pagination
```

**No auth required** for this endpoint (public models). Rate limit: **3000 resolver-class API calls per 300 seconds** (when unauthenticated). With a `HF_TOKEN`, limits are higher and more generous.

**Response (per model):**
```json
{
  "id": "hexgrad/Kokoro-82M",
  "modelId": "hexgrad/Kokoro-82M",
  "private": false,
  "gated": false,
  "disabled": false,
  "pipeline_tag": "text-to-speech",
  "library_name": "transformers",
  "downloads": 10853337,
  "likes": 6531,
  "tags": [
    "text-to-speech",
    "en",
    "arxiv:2306.07691",
    "license:apache-2.0",
    "region:us"
  ],
  "createdAt": "2024-12-26T00:20:08.000Z",
  "_id": "676ca1388118866906abbd7c"
}
```

**Key fields:**
- **`id`** (e.g., `hexgrad/Kokoro-82M`): Used in URLs. Format: `{owner}/{repo_name}`.
- **`gated`**: Boolean. If `true`, the repo has access restrictions; user must accept a form or license. (Downloading gated models requires authentication.)
- **`disabled`**: Boolean. If `true`, the repo is archived/disabled; cannot download.
- **`tags`**: Free-form list. Includes language codes (e.g., `"en"`, `"multilingual"`), framework tags (`"transformers"`, `"onnx"`), and license tags (`"license:apache-2.0"`).
- **`downloads`**: All-time download count (useful for ranking).
- **`library_name`**: Upstream framework (e.g., `"transformers"`, `"safetensors"`, `"onnx"`).

**Searching & Filtering:**
- `?pipeline_tag=text-to-speech` is your primary filter; this is well-maintained by HF's community.
- Language filtering is indirect via tags (e.g., `&tags=en` to include English-tagged models).
- No "corpus" or "training data" filters; you have to scan `tags` or README.
- `sort=downloads` is reliable for popularity; `sort=likes` is more subjective.

**Pagination:**
- Default `limit=50`; HF usually caps this at 50-100 per page.
- Use `offset` to iterate (`offset=0`, `offset=50`, `offset=100`, etc.).
- `createdAt` allows sorting by age for discovering new models.

---

## 2. File Listing & Download URL Construction

### A. `/api/models/{id}/tree/{revision}` - Browse repo structure

```
GET /api/models/hexgrad/Kokoro-82M/tree/main?recursive=false
```

**Response (sample):**
```json
[
  {
    "type": "directory",
    "oid": "b2e65df8cbc39e5fa11ee92a313f03f432cf7576",
    "size": 0,
    "path": "voices"
  },
  {
    "type": "file",
    "oid": "5e51cb80d60d400f8a6930b96feffb99f97fa5d5",
    "size": 1913,
    "path": ".gitattributes"
  },
  {
    "type": "file",
    "oid": "cd202d28b065e6d9eb396ca0c307d01520e1e296",
    "size": 2562,
    "path": "README.md"
  },
  {
    "type": "file",
    "oid": "5e51cb80d60d400f8a6930b96feffb99f97fa5d5",
    "size": 327212226,
    "path": "kokoro-v1_0.pth"
  }
]
```

**Useful for:**
- Discovering what companion files exist (`config.json`, `tokenizer.json`, voice tables, phoneme maps).
- Getting file sizes upfront (before downloading).
- Verifying a repo has the structure your `inspect()` logic expects.

**Recursive listing:** Add `?recursive=true` to recurse into subdirectories (slower, but useful to inventory all voice files).

### B. Individual model info - `/api/models/{id}` (alternative)

```
GET /api/models/hexgrad/Kokoro-82M
```

**Includes a `siblings` array** (flat list of all files):
```json
{
  "id": "hexgrad/Kokoro-82M",
  "siblings": [
    { "rfilename": "config.json" },
    { "rfilename": "kokoro-v1_0.pth" },
    { "rfilename": "voices/af_heart.pt" },
    ...
  ],
  ...
}
```

**Faster than the tree endpoint** (no size info, but simpler for checking "does this file exist?").

### C. Constructing download URLs

**Pattern:**
```
https://huggingface.co/{owner}/{repo}/resolve/{revision}/{path}
```

**Example:**
```
https://huggingface.co/hexgrad/Kokoro-82M/resolve/main/kokoro-v1_0.pth
```

This URL **resolves to a CDN link** (via HTTP 302 redirect); the actual file is hosted on HF's XetHub infrastructure.

**Important:** The `/resolve/` endpoint does **not** require authentication for public models, but it issues **HTTP 302 redirects**. Clients must follow redirects; many download libraries do this by default.

### D. File metadata: size and hashes

**From `/api/models/{id}/tree/{revision}`:**
- `size`: File size in bytes (populated for regular files).

**From HEAD request to the resolve URL:**
```
curl -sI https://huggingface.co/hexgrad/Kokoro-82M/resolve/main/kokoro-v1_0.pth
```

**Response headers:**
```
x-linked-size: 327212226
x-linked-etag: "496dba118d1a58f5f3db2efc88dbdc216e0483fc89fe6e47ee1f2c53f18ad1e4"
ETag: "0c0ac263f5ae91312df578d1b6adfb4c6dfda401cc3696ba97042f835619c52f"
```

**ETag behavior:**
- HF's `ETag` header is **not guaranteed to be a SHA-256 hash** of the file; it is HF's internal cache tag.
- The `x-linked-etag` header is **more likely to be stable** (XetHub's tree hash), but **not documented as official**.
- **Recommendation:** Do **not** rely on ETags for manifest SHA-256 verification. Instead, **always download the file and compute SHA-256 yourself** (as the app already does via `Sha256Verifier`). The ETag is useful only for cache validation (e.g., "has this file changed since last check?").

**Best practice for verification:**
1. List files via `/api/models/{id}/tree/main`.
2. For critical files (weights, config, voice tables), download and **always recompute SHA-256**.
3. Store the SHA-256 in the manifest (as the app already does).
4. Use ETags only for "is this file unchanged?" checks (optional optimization).

---

## 3. Authentication & Rate Limits

### Anonymous access (no token)
- **Limit:** ~3000 API calls per 300 seconds (across the entire `resolver` class, which includes `/api/models`, `/tree`, and `/resolve` calls).
- **Works for:** All public models and files.
- **Gated models:** Cannot download gated models without auth, even if listed.

### Authenticated (with `HF_TOKEN`)
- **Limit:** Higher and model-specific; documented at https://huggingface.co/docs/hub/security-tokens (typically 30k-50k calls per hour for pro users).
- **Works for:** Gated models, after the user accepts any required agreements.
- **How to provide:** Pass header `Authorization: Bearer {HF_TOKEN}` with each request.

### In-app strategy
- **For MVP (curated list):** Anonymous access is fine; most popular models are ungated.
- **For full browsing:** Consider allowing the user to paste a token in settings (optional, for gated models). Store it securely (Android `EncryptedSharedPreferences`).
- **Rate limit buffer:** If you're fetching model lists on every app launch or frequently, cache them locally and refresh periodically (e.g., daily) to avoid rate-limit surprises.

---

## 4. Mapping HF Repos to Engine Families

The app's 5 built-in models set expectations for each engine:

| Engine | Repo | Expected Structure | Companion Files | Identifier Pattern |
|--------|------|-------------------|-----------------|-------------------|
| **Kokoro** | `hexgrad/Kokoro-82M` | Single `.pth` file + voice embeddings | `config.json`, `VOICES.md` | `config.json` + multispeaker field |
| **Piper** | `rhasspy/piper-voices` (collection) | Per-voice `.onnx` files | `piper.json` or README with voice list | `.onnx` + espeak config |
| **KittenTTS** | `KittenML/KittenTTS` | Single model variant (nano/micro/mini) | `config.json`, `model.onnx` | `config.json` with model type |
| **MeloTTS** | `myshell-ai/MeloTTS-*` | `.pth` main model + BERT | `config.json`, model checkpoints | `config.json` with speaker table |
| **CosyVoice2** | `FunAudioLLM/CosyVoice2-*` | Multi-component (LLM `.safetensors` + flow decoder) | `config.json`, tokenizer, voice adapter weights | `config.json` with LLM type |

### The inspect() problem

**What makes inspect() hard:**
1. **Not all HF repos follow a standard structure.** A repo uploaded by a researcher may have:
   - Weights in root; config in subdirectory.
   - Multiple model variants (e.g., `model_v1.onnx`, `model_v2.onnx`).
   - No `config.json` (weights + side files only).
   - Missing phoneme/voice tables.

2. **Similar-looking repos can be different families.** For example:
   - A VITS model could be Piper, MeloTTS, or a one-off variant.
   - An ONNX model could be KittenTTS or an unrelated network.

3. **Fail-closed is non-negotiable** (CLAUDE.md §6.1, SPEC.md §6.2). If `inspect()` is unsure, it **must return `null`** and drop to user-pick.

### Phased approach to mapping

**Phase 1 (MVP: Curated List)**
- Hardcode ~20-50 well-known HF models (Kokoro, Piper variants, popular MeloTTS forks, etc.).
- Map each repo `id` → engine `id` in the manifest.
- The manifest acts like:
  ```json
  {
    "models": [
      {
        "modelId": "hexgrad/Kokoro-82M",
        "displayName": "Kokoro-82M (HF)",
        "engineId": "kokoro",   // ← explicit hint
        "files": [
          {
            "name": "kokoro-v1_0.pth",
            "url": "https://huggingface.co/hexgrad/Kokoro-82M/resolve/main/kokoro-v1_0.pth",
            "sha256": "...",
            "sizeBytes": 327212226
          },
          ...
        ]
      }
    ]
  }
```
- When the user downloads, the app fetches the manifest, verifies SHA-256 as usual, and `resolve()` is guided by the explicit `engineId` hint (no risky auto-detect).

**Phase 2 (Live Search: Allow-List)**
- Build a **fixed internal list** of ~200 known-good repos (manually curated or pulled from a GitHub gist/doc).
- Query HF's `/api/models` and filter to repos in that list.
- For each match, look up the engine mapping in a local table.
- Still no risky auto-detection; just convenience of browsing a known-safe subset.

**Phase 3 (Full Browsing)**
- Expose all HF TTS models via `/api/models?pipeline_tag=text-to-speech`.
- For each repo the user clicks, **run `inspect()` in the background** to guess the engine.
- If `inspect()` succeeds, show a confirmation ("Detected as Kokoro-family. Download?").
- If `inspect()` fails, offer the user-pick fallback ("Which engine?").
- This is technically feasible but requires robust `inspect()` implementations for each engine.

**Honest assessment of Phase 3:**
- Will work well for the 5 "official" families (they have stable fingerprints).
- Will work for **close derivatives** of those families (a Piper fork with the same config structure).
- Will fail for isolated experiments, niche models, and mixed-architecture repos.
- **User-pick fallback is essential** - the app never guesses; it asks.

---

## 5. Effort Estimate & Phased Plan

### Phase 1: Curated in-app list (1-2 weeks)

**Goal:** 20-50 popular HF TTS models discoverable via tap, download, auto-load.

**Tasks:**
1. **Curate the list.** Identify ~40 popular/stable repos (Kokoro variants, Piper forks, MeloTTS fine-tunes, etc.).
   - Spot-check each one: download, unzip, verify `config.json` and model files exist.
   - Document engine family and file sizes.
   - ~2-3 days of research + spot checks.

2. **Build the manifest offline.** Compute SHA-256 for each file (or extract from HF if available; see caveat in §2.D).
   - Write a Python script to crawl the curated list and generate a manifest JSON.
   - Store in `app/src/main/assets/models_curated.json` or similar.
   - ~2-3 hours.

3. **Add a "Browse Curated Models" screen** (Compose UI).
   - Fetch the manifest at app startup or on-demand.
   - Display a list: model name, engine family, file size, download button.
   - Reuse existing download + SHA-256 + auto-load pipeline.
   - ~3-4 days (UI + download state management).

4. **Test end-to-end.** Download a Kokoro variant, MeloTTS fork, and Piper variant; confirm they auto-load.
   - ~1-2 days.

**Result:** Users can tap a curated list and download trusted models. No guessing; engine is pre-mapped. Minimal risk.

**Dependencies:** Existing manifest parser, downloader, SHA-256 verifier, auto-load pipeline (all in `:core`).

---

### Phase 2: Live search on allow-list (2-3 weeks)

**Goal:** Browse a fixed whitelist of HF repos, filtered by HF's API.

**Tasks:**
1. **Build the allow-list.** Expand Phase 1's curated set to ~200 repos.
   - Use tags (`"onnx"`, `"multilingual"`, etc.) to find good candidates.
   - Organize into a lookup table: `repo_id` → `engine_id`.
   - Store in `:core/src/main/assets/` or a downloaded gist.
   - ~3-5 days.

2. **Add a "Search" screen** (Compose).
   - Search bar: user types (e.g., "japanese piper").
   - Backend: filter the allow-list by repo name and tags; query HF's API for metadata (downloads, likes, description snippet).
   - Display results with engine, file size estimate, and download button.
   - ~4-5 days (UI + filtering logic).

3. **Dynamic manifest generation.** When the user picks a model:
   - Query `/api/models/{id}` to get the `siblings` list.
   - Query `/api/models/{id}/tree/main` to get file sizes.
   - Construct manifest entries on the fly (or generate and cache).
   - ~2-3 days.

4. **Test.** Download models across different engine families; verify SHA-256 and auto-load.
   - ~1-2 days.

**Result:** Users can search a large, curated subset of HF. Fast, safe, and familiar.

**Risk:** Maintaining the allow-list as new repos appear (mitigation: periodic updates or community contributions).

---

### Phase 3: Full browsing (4-6 weeks)

**Goal:** Browse all HF TTS models; auto-detect engine family.

**Tasks:**
1. **Strengthen `inspect()` implementations.**
   - Each engine's `inspect()` must confidently fingerprint its family (e.g., Kokoro recognizes specific config keys, Piper recognizes ONNX + espeak structure).
   - Ensure every engine fails closed: return `null` on ambiguity.
   - Add comprehensive tests for each `inspect()` (e.g., "Kokoro identifies its own files; rejects ONNX-only; rejects MeloTTS configs").
   - ~2-3 weeks (per engine; Kokoro, Piper, KittenTTS, MeloTTS, CosyVoice2).

2. **Add a "Browse All" screen.**
   - Query `/api/models?pipeline_tag=text-to-speech&sort=downloads&limit=50` (paginated).
   - For each result, fetch `/api/models/{id}/tree/main` to get file structure.
   - Show user a preview: engine (if detected), file size, language tags, downloads count.
   - Download button: runs inspection in background; shows result or fallback user-pick.
   - ~5-7 days (UI + inspection loop).

3. **Handle ambiguous cases.**
   - If `inspect()` fails, offer the user-pick dialog: "Which engine handles this model?"
   - Save the choice (as the app already does).
   - Acknowledge that some niche repos won't auto-detect, and **that's okay** (fail-closed is the goal).
   - ~2-3 days.

4. **Comprehensive testing & edge cases.**
   - Test with models from each family and their variants.
   - Test with ambiguous/broken repos (missing files, unusual structures).
   - Verify user-pick fallback works smoothly.
   - ~2-3 weeks.

**Result:** Full HF TTS browsing with automatic family detection. Powerful but requires strong `inspect()` implementations.

**Risk:** Maintenance burden (new model structures, false positives/negatives in `inspect()`). Mitigation: telemetry (log failed inspections) and feedback channel for users to report misdetections.

---

## 6. Android-Specific Considerations

### A. Background downloads (WorkManager)

**Current state:** Likely the `:app` module has a downloader (not fully visible in `:core`). Assume it's synchronous or coroutine-based.

**For large model files (100MB-500MB+):**
- Use **WorkManager** (Android's recommended way to schedule and resume long-running work across app restarts).
- Queue a download: user taps "Download," WorkManager task is enqueued.
- Task runs in background; UI shows progress (or just a notification).
- On network interruption, WorkManager automatically retries (configurable).
- On completion, trigger auto-load pipeline.

**Implementation sketch:**
```kotlin
class ModelDownloadWorker : CoroutineWorker(...) {
    override suspend fun doWork(): Result {
        val (modelId, fileUrls) = inputData // from WorkManager
        return try {
            for ((name, url) in fileUrls) {
                download(url, destination, progressCallback)
                if (!Sha256Verifier.verify(...)) return Result.retry()
            }
            autoLoad(destination) // ModelImporter.import(...)
            Result.success()
        } catch (e: Exception) {
            Result.retry() // WorkManager will backoff and retry
        }
    }
}
```

**Advantages:**
- Transparent to user; download continues if app is closed.
- Built-in retry + backoff.
- Status visible in notifications.
- Free of coroutine scope leaks.

### B. Storage & permissions

**Path:** `context.getExternalFilesDir(null)` (app-private storage, no permissions needed on API 30+) or `context.cacheDir` (temporary).

**Caveat:** On some devices, `getExternalFilesDir()` points to internal storage (if no SD card). Budget accordingly (4GB phone has ~1-2GB free).

**Cleanup:** Delete old/unused models manually via settings (or auto-delete if storage is low).

**Recommendation:**
- Store downloads in `getExternalFilesDir()/models/{owner}/{repo}/`.
- Let the user manage deletions (show size, allow removal per model).

### C. Resumable downloads

**HTTP support:**
- Use `Range` header (`Range: bytes=0-1023`) to request byte ranges.
- HF's CDN (XetHub) supports this; check `Accept-Ranges: bytes` in response headers.
- On network resumption, seek to last byte and continue.

**Library:**
- Kotlin coroutines + OkHttp can do this, but it's manual.
- Consider a library like **okio** (already likely a transitive dep) for easier byte-range management.
- Or simply re-download from the start (simpler, but wasteful for huge files).

**MVP recommendation:** Re-download on failure (simpler) or cache partially downloaded files and skip already-downloaded bytes. Proper resumption is nice but not critical for MVP.

### D. Network & proxy awareness

**Proxy:** The app may run behind a corporate proxy (per environment setup).
- `System.getProperty("http.proxyHost")` and `.proxyPort` should be respected by OkHttp by default.
- Test with a proxy to confirm.

**Offline:** If HF is unreachable, gracefully disable the "Browse" feature (show a message, allow manual sideload as fallback).

**User-Agent:** Some CDNs (including HF's) may rate-limit aggressively on suspicious user-agents. Set a reasonable one:
```kotlin
client.newBuilder().addInterceptor { chain ->
    val originalRequest = chain.request()
    val requestWithUserAgent = originalRequest.newBuilder()
        .header("User-Agent", "PhoneTTS/1.0 (Android TTS)")
        .build()
    chain.proceed(requestWithUserAgent)
}.build()
```

---

## 7. Concrete API Examples

### Example 1: List popular multilingual TTS models
```bash
curl "https://huggingface.co/api/models?pipeline_tag=text-to-speech&tags=multilingual&sort=downloads&direction=-1&limit=10" \
  -H "Authorization: Bearer $HF_TOKEN" | jq '.[] | {id, downloads, tags}'
```

### Example 2: Get file list and sizes for a model
```bash
curl "https://huggingface.co/api/models/hexgrad/Kokoro-82M/tree/main?recursive=false" | jq '.[] | select(.type=="file") | {path, size}'
```

### Example 3: Download a file with SHA-256 verification
```bash
# Fetch file
curl -L "https://huggingface.co/hexgrad/Kokoro-82M/resolve/main/kokoro-v1_0.pth" \
  -o kokoro-v1_0.pth

# Verify (app does this via Sha256Verifier)
sha256sum kokoro-v1_0.pth
```

### Example 4: Check if a model is gated
```bash
curl "https://huggingface.co/api/models/hexgrad/Kokoro-82M" | jq '{id, gated, disabled}'
```

---

## 8. Known Unknowns & Gotchas

1. **No official HF "best models" list.** The `/api/models` endpoint returns all models; there's no built-in scoring or curation. You have to curate or rely on downloads/likes.

2. **File hash stability.** HF's ETag is not a stable content hash; always recompute SHA-256 yourself (the app already does).

3. **Gated model rate limits.** Downloading gated models may have per-user rate limits even with a token. Not documented; test empirically.

4. **Model deprecation.** Repos can be deleted or archived anytime. The app's manifest must be refreshed periodically; old URLs will 404.

5. **Revision handling.** The app assumes the latest `main` revision. HF also supports other branches/tags; handling all of them adds complexity (out of scope for MVP).

6. **Model validity.** Just because a file downloads and matches SHA-256 doesn't mean it's a valid model for the engine. `inspect()` + trial load is the real verification.

7. **Bandwidth & data plan.** Some models are 500MB-1GB. The app should warn users; consider metered connection checks (Android's ConnectivityManager).

---

## 9. Recommended First Step

### Start with Phase 1: Curated list (1-2 weeks)

1. **Manually identify ~40 high-quality HF repos** across the 5 engine families.
   - Examples:
     - **Kokoro:** `hexgrad/Kokoro-82M`, community fine-tunes.
     - **Piper:** Official voice collection, maybe 2-3 community variants.
     - **KittenTTS:** Official repo + popular variants.
     - **MeloTTS:** Official + fine-tunes for other languages.
     - **CosyVoice2:** Official + community variants.

2. **Spot-check each repo:** Download, unzip, verify structure and files.

3. **Compute SHA-256 hashes** for all files (or trust HF's metadata if it's stable; test empirically).

4. **Generate a manifest JSON** (checked into the repo or fetched from a gist/release).

5. **Build a simple UI** (RecyclerView or LazyColumn of models) with download buttons.

6. **Reuse existing infrastructure:** The downloader, SHA-256 verifier, and auto-load pipeline already handle the rest.

**Why Phase 1 first?**
- Immediate user value (browse & download popular models).
- No risky auto-detection; engine is pre-mapped.
- Easy to expand to Phase 2 (add more repos, add search filtering).
- Proves the end-to-end pipeline works on real HF models.
- Lowers risk before tackling full browsing (Phase 3).

**Next steps (not MVP):**
- Phase 2 (search on allow-list) comes naturally after Phase 1 proves the pipeline.
- Phase 3 (full browsing with auto-detect) requires stronger `inspect()` implementations and more thorough testing.

---

## 10. API Endpoints Summary Table

| Endpoint | Method | Auth? | Rate Limit | Use Case |
|----------|--------|-------|-----------|----------|
| `/api/models?pipeline_tag=text-to-speech` | GET | Optional | 3000/300s | List/search TTS models |
| `/api/models/{id}` | GET | Optional | 3000/300s | Get model info + siblings |
| `/api/models/{id}/tree/{rev}` | GET | Optional | 3000/300s | List files + sizes |
| `/{owner}/{repo}/resolve/{rev}/{path}` | GET | Optional (auth for gated) | Varies | Download file (redirects) |
| `/{owner}/{repo}/raw/{rev}/{path}` | GET | Optional | Varies | Read text file (no redirect) |

All endpoints return JSON or redirect to S3/CDN.

---

## Conclusion

**An in-app HF model browser is achievable.** The API is stable, well-documented, and public. The app already has all the download, verification, and auto-load plumbing. The main unknowns are around mapping arbitrary repos to your 5 engine families and handling ambiguous cases.

**Recommended approach:**
1. **Start small:** Curated list of 40-50 vetted repos (Phase 1, 1-2 weeks).
2. **Validate the pipeline:** Confirm downloads, SHA-256, and auto-load work end-to-end on real HF models.
3. **Iterate:** Expand to search (Phase 2) and full browsing (Phase 3) as confidence grows and `inspect()` implementations strengthen.

The bottleneck is **not the API, but the robustness of family detection** (`inspect()` implementations). Focus there, and the rest follows naturally.
