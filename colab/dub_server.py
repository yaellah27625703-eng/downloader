"""
Arabic dubbing server for Google Colab (free GPU), driven from the Android app.

Pipeline per job:
  1. Demucs splits the audio into voices and background (music + effects).
  2. faster-whisper turns speech into text with timestamps.
  3. Each line's voice is fingerprinted with XTTS; similar voices are grouped into speakers.
  4. NLLB translates each line into Arabic.
  5. XTTS speaks the Arabic in each speaker's own voice, fitted into the original timing.
  6. The new Arabic voices are mixed back over the untouched background.

HTTP API (every call needs ?k=<token>):
  GET  /health                         -> {"ok": true, "gpu": "..."}
  POST /upload?job=ID&part=N           (raw bytes)  one chunk of the audio file
  POST /start?job=ID&parts=N&lang=xx&speakers=0
  GET  /status?job=ID                  -> {"stage", "progress", "message", "done", "error"}
  GET  /result?job=ID&file=audio|srt   (supports Range)
"""
import argparse
import json
import os
import queue
import re
import shutil
import subprocess
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

os.environ.setdefault("COQUI_TOS_AGREED", "1")

FAKE = os.environ.get("DUB_FAKE") == "1"          # local protocol test without GPU models
ROOT = os.environ.get("DUB_ROOT", "/content/dub_jobs")
os.makedirs(ROOT, exist_ok=True)

TOKEN = ""
JOBS = {}                 # job id -> status dict
WORK = queue.Queue()
LOCK = threading.Lock()

# whisper language code -> NLLB code
NLLB = {
    "en": "eng_Latn", "fr": "fra_Latn", "es": "spa_Latn", "tr": "tur_Latn", "hi": "hin_Deva",
    "ko": "kor_Hang", "ja": "jpn_Jpan", "zh": "zho_Hans", "de": "deu_Latn", "it": "ita_Latn",
    "pt": "por_Latn", "ru": "rus_Cyrl", "ur": "urd_Arab", "fa": "pes_Arab", "id": "ind_Latn",
    "nl": "nld_Latn", "pl": "pol_Latn", "uk": "ukr_Cyrl", "vi": "vie_Latn", "th": "tha_Thai",
    "ms": "zsm_Latn", "bn": "ben_Beng", "ta": "tam_Taml", "te": "tel_Telu", "sv": "swe_Latn",
    "ro": "ron_Latn", "el": "ell_Grek", "cs": "ces_Latn", "hu": "hun_Latn", "he": "heb_Hebr",
    "tl": "tgl_Latn", "sw": "swh_Latn", "pa": "pan_Guru", "mr": "mar_Deva", "gu": "guj_Gujr",
}


def set_status(job, **kw):
    with LOCK:
        JOBS.setdefault(job, {"stage": "queued", "progress": 0, "message": "في الانتظار", "done": False, "error": None})
        JOBS[job].update(kw)


def sh(cmd):
    r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if r.returncode != 0:
        raise RuntimeError(r.stdout[-1500:])
    return r.stdout


# ---------------------------------------------------------------------------- models (loaded once)

_models = {}
_mlock = threading.Lock()


def whisper_model():
    with _mlock:
        return _whisper()


def _whisper():
    if "whisper" not in _models:
        from faster_whisper import WhisperModel
        _models["whisper"] = WhisperModel("large-v3-turbo", device="cuda", compute_type="float16")
    return _models["whisper"]


def xtts_model():
    with _mlock:
        return _xtts()


def _xtts():
    if "xtts" not in _models:
        import torch
        from TTS.api import TTS
        tts = TTS("tts_models/multilingual/multi-dataset/xtts_v2").to("cuda" if torch.cuda.is_available() else "cpu")
        _models["xtts"] = tts.synthesizer.tts_model
    return _models["xtts"]


def nllb():
    with _mlock:
        return _nllb()


def _nllb():
    if "nllb" not in _models:
        import torch
        from transformers import AutoModelForSeq2SeqLM, AutoTokenizer
        name = "facebook/nllb-200-distilled-1.3B"
        tok = AutoTokenizer.from_pretrained(name)
        model = AutoModelForSeq2SeqLM.from_pretrained(name, torch_dtype=torch.float16).to("cuda").eval()
        _models["nllb"] = (tok, model)
    return _models["nllb"]


# ---------------------------------------------------------------------------- pipeline steps

def separate(job, wav, d):
    """Returns (vocals.wav, background.wav or None)."""
    set_status(job, stage="separate", progress=5, message="فصل أصوات المتحدثين عن الموسيقى والمؤثرات…")
    if FAKE:
        return wav, None
    try:
        sh(["python", "-m", "demucs", "--two-stems", "vocals", "-n", "htdemucs", "-d", "cuda",
            "-o", d, "--filename", "{stem}.{ext}", wav])
        base = os.path.join(d, "htdemucs")
        voc, bg = os.path.join(base, "vocals.wav"), os.path.join(base, "no_vocals.wav")
        if os.path.exists(voc) and os.path.exists(bg):
            return voc, bg
    except Exception:
        traceback.print_exc()
    return wav, None          # fallback: no separation, original speech will be lowered instead


def transcribe(job, vocals, lang):
    set_status(job, stage="transcribe", progress=20, message="تحويل الكلام إلى نص…")
    if FAKE:
        return [{"start": 1.0, "end": 3.0, "text": "Hello and welcome."},
                {"start": 4.0, "end": 6.5, "text": "This is a test of the dubbing server."}], lang or "en"
    model = whisper_model()
    segs, info = model.transcribe(vocals, language=lang or None, vad_filter=True, beam_size=5,
                                  vad_parameters={"min_silence_duration_ms": 400})
    total = max(info.duration, 1)
    out = []
    for s in segs:
        t = s.text.strip()
        if t and not t.startswith(("[", "(", "♪")):
            out.append({"start": float(s.start), "end": float(s.end), "text": t})
        set_status(job, progress=20 + 20 * min(1.0, s.end / total))
    return merge_sentences(out), info.language


def merge_sentences(segs):
    out, cur = [], None
    for s in segs:
        if cur is None:
            cur = dict(s)
            continue
        gap = s["start"] - cur["end"]
        ends = cur["text"].rstrip()[-1:] in ".!?؟。！？"
        if not ends and gap < 0.6 and len(cur["text"]) + len(s["text"]) < 200 and s["end"] - cur["start"] < 14:
            cur["end"] = s["end"]
            cur["text"] += " " + s["text"]
        else:
            out.append(cur)
            cur = dict(s)
    if cur:
        out.append(cur)
    return out


def write_wav(path, data, sr):
    try:
        import soundfile as sf
        sf.write(path, data, sr)
    except ImportError:
        import wave
        import numpy as np
        pcm = (np.clip(data, -1, 1) * 32767).astype("<i2")
        with wave.open(path, "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes(pcm.tobytes())


def load_mono(path, sr):
    import librosa
    y, _ = librosa.load(path, sr=sr, mono=True)
    return y


def speakers(job, vocals, segs, d, n_speakers):
    """Groups lines by voice and returns {speaker: (gpt_latent, embedding)}; sets seg['spk']."""
    set_status(job, stage="speakers", progress=42, message="تمييز أصوات الممثلين…")
    if FAKE:
        for s in segs:
            s["spk"] = 0
        return {0: None}
    import numpy as np
    model = xtts_model()
    sr = 22050
    y = load_mono(vocals, sr)
    embs, idx = [], []
    for i, s in enumerate(segs):
        a, b = int(s["start"] * sr), int(s["end"] * sr)
        if b - a < int(1.2 * sr):
            continue
        clip = os.path.join(d, "emb.wav")
        write_wav(clip, y[a:b], sr)
        _, e = model.get_conditioning_latents(audio_path=[clip])
        embs.append(e.squeeze().float().cpu().numpy())
        idx.append(i)
    labels = {}
    if len(embs) >= 2:
        from sklearn.cluster import AgglomerativeClustering
        X = np.stack(embs)
        X = X / (np.linalg.norm(X, axis=1, keepdims=True) + 1e-9)
        if n_speakers and n_speakers > 0:
            cl = AgglomerativeClustering(n_clusters=min(n_speakers, len(X)), metric="cosine", linkage="average")
        else:
            cl = AgglomerativeClustering(n_clusters=None, metric="cosine", linkage="average", distance_threshold=0.35)
        for i, lab in zip(idx, cl.fit_predict(X)):
            labels[i] = int(lab)
    elif idx:
        labels[idx[0]] = 0
    # short lines take the speaker of the nearest labelled line in time
    for i, s in enumerate(segs):
        if i in labels:
            s["spk"] = labels[i]
        elif labels:
            near = min(labels, key=lambda k: abs(segs[k]["start"] - s["start"]))
            s["spk"] = labels[near]
        else:
            s["spk"] = 0
    # one voice reference per speaker: their longest lines, up to ~20 s
    refs = {}
    for spk in sorted(set(s["spk"] for s in segs)):
        mine = sorted([s for s in segs if s["spk"] == spk], key=lambda s: s["end"] - s["start"], reverse=True)
        parts, total = [], 0.0
        for s in mine:
            a, b = int(s["start"] * sr), int(s["end"] * sr)
            parts.append(y[a:b])
            total += s["end"] - s["start"]
            if total >= 20:
                break
        if not parts:
            continue
        ref = os.path.join(d, f"ref_{spk}.wav")
        write_wav(ref, np.concatenate(parts), sr)
        refs[spk] = model.get_conditioning_latents(audio_path=[ref], gpt_cond_len=12, max_ref_length=20)
    return refs


def translate(job, segs, lang):
    set_status(job, stage="translate", progress=50, message="الترجمة إلى العربية…")
    if lang == "ar":
        raise RuntimeError("الفيديو بالعربية أصلًا")
    if FAKE:
        for s in segs:
            s["ar"] = "مرحبًا بكم في هذه التجربة"
        return
    import torch
    src = NLLB.get(lang)
    if not src:
        raise RuntimeError(f"لغة الفيديو ({lang}) غير مدعومة في الترجمة")
    tok, model = nllb()
    tok.src_lang = src
    tgt = tok.convert_tokens_to_ids("arb_Arab")
    B = 16
    for i in range(0, len(segs), B):
        batch = segs[i:i + B]
        enc = tok([s["text"] for s in batch], return_tensors="pt", padding=True, truncation=True, max_length=256).to("cuda")
        with torch.no_grad():
            out = model.generate(**enc, forced_bos_token_id=tgt, max_new_tokens=256, num_beams=4)
        for s, t in zip(batch, tok.batch_decode(out, skip_special_tokens=True)):
            s["ar"] = t.strip()
        set_status(job, progress=50 + 10 * min(1.0, (i + B) / max(1, len(segs))))


def voice(job, segs, refs, vocals, total_sec):
    """Arabic lines in each speaker's own voice, fitted to the original timing. Returns 24 kHz mono array."""
    set_status(job, stage="voice", progress=60, message="الدبلجة بأصوات الممثلين…")
    import numpy as np
    sr = 24000
    track = np.zeros(int((total_sec + 2) * sr), dtype=np.float32)
    if FAKE:
        for s in segs:
            a = int(s["start"] * sr)
            n = int((s["end"] - s["start"]) * sr)
            t = np.arange(n) / sr
            track[a:a + n] += 0.3 * np.sin(2 * np.pi * 440 * t).astype(np.float32)
        return track, sr
    import librosa
    model = xtts_model()
    orig = load_mono(vocals, sr)
    prev_end = 0.0
    for i, s in enumerate(segs):
        text = s.get("ar", "").strip()
        if not text:
            continue
        lat = refs.get(s["spk"]) or (next(iter(refs.values())) if refs else None)
        if lat is None:
            continue
        gpt, emb = lat
        nxt = segs[i + 1]["start"] if i + 1 < len(segs) else total_sec
        start = max(s["start"], prev_end + 0.05)
        allowed = max(0.6, max(s["end"], nxt - 0.1) - start)

        def speak(speed):
            out = model.inference(text, "ar", gpt, emb, temperature=0.65, speed=speed, enable_text_splitting=True)
            w = out["wav"]
            return np.asarray(w.cpu().numpy() if hasattr(w, "cpu") else w, dtype=np.float32).reshape(-1)

        try:
            w = speak(1.0)
            dur = len(w) / sr
            if dur > allowed * 1.03:
                w = speak(min(1.35, dur / allowed))
                dur = len(w) / sr
            if dur > allowed * 1.05:
                w = librosa.effects.time_stretch(w, rate=min(1.3, dur / allowed))
        except Exception:
            traceback.print_exc()
            continue
        # match the loudness of the original line (shouting stays loud, whispers stay soft)
        a, b = int(s["start"] * sr), int(s["end"] * sr)
        ref_rms = float(np.sqrt(np.mean(orig[a:b] ** 2))) if b > a else 0.05
        rms = float(np.sqrt(np.mean(w ** 2))) + 1e-6
        w = w * min(4.0, max(0.25, (ref_rms if ref_rms > 0.005 else 0.05) / rms))
        p = int(start * sr)
        end = min(len(track), p + len(w))
        track[p:end] += w[:end - p]
        prev_end = start + len(w) / sr
        set_status(job, progress=60 + 32 * (i + 1) / len(segs))
    return np.clip(track, -1, 1), sr


def mix(job, d, wav_in, background, track, sr):
    set_status(job, stage="mix", progress=93, message="دمج الأصوات العربية مع الموسيقى والمؤثرات…")
    dub = os.path.join(d, "dub_voices.wav")
    write_wav(dub, track, sr)
    out = os.path.join(d, "dubbed.m4a")
    if background:
        filt = ("[0:a]aresample=44100,aformat=channel_layouts=stereo[bg];"
                "[1:a]aresample=44100,aformat=channel_layouts=stereo[v];"
                "[bg][v]amix=inputs=2:duration=first:normalize=0,alimiter=limit=0.95[out]")
        src = background
    else:
        # no separation available: lower the original speech under the Arabic voice
        filt = ("[1:a]aresample=44100,aformat=channel_layouts=stereo,asplit=2[vk][vm];"
                "[0:a]aresample=44100,aformat=channel_layouts=stereo[o];"
                "[o][vk]sidechaincompress=threshold=0.04:ratio=4:attack=20:release=500[duck];"
                "[duck][vm]amix=inputs=2:duration=first:normalize=0,alimiter=limit=0.95[out]")
        src = wav_in
    sh(["ffmpeg", "-y", "-loglevel", "error", "-i", src, "-i", dub, "-filter_complex", filt,
        "-map", "[out]", "-c:a", "aac", "-b:a", "192k", out])
    return out


def srt(d, segs):
    def ts(x):
        ms = int(x * 1000)
        return "%02d:%02d:%02d,%03d" % (ms // 3600000, ms // 60000 % 60, ms // 1000 % 60, ms % 1000)
    path = os.path.join(d, "ar.srt")
    with open(path, "w", encoding="utf-8") as f:
        n = 1
        for s in segs:
            if s.get("ar"):
                f.write(f"{n}\n{ts(s['start'])} --> {ts(s['end'])}\n{s['ar']}\n\n")
                n += 1
    return path


def process(job, opts):
    d = os.path.join(ROOT, job)
    try:
        src = os.path.join(d, "input")
        with open(src, "wb") as out:
            for i in range(opts["parts"]):
                with open(os.path.join(d, f"part_{i}"), "rb") as p:
                    shutil.copyfileobj(p, out)
        set_status(job, stage="prepare", progress=2, message="تجهيز الصوت…")
        wav = os.path.join(d, "input.wav")
        sh(["ffmpeg", "-y", "-loglevel", "error", "-i", src, "-ac", "2", "-ar", "44100", wav])
        total = float(sh(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", wav]).strip() or 0)
        vocals, background = separate(job, wav, d)
        segs, lang = transcribe(job, vocals, opts.get("lang") or None)
        if not segs:
            raise RuntimeError("لم يُعثر على كلام في هذا الفيديو")
        refs = speakers(job, vocals, segs, d, opts.get("speakers", 0))
        translate(job, segs, lang)
        track, sr = voice(job, segs, refs, vocals, total)
        audio = mix(job, d, wav, background, track, sr)
        sub = srt(d, segs)
        n_spk = len(set(s["spk"] for s in segs))
        set_status(job, stage="done", progress=100, done=True, audio=audio, srt=sub, lang=lang,
                   message=f"اكتملت الدبلجة • {len(segs)} جملة • {n_spk} صوت")
    except Exception as e:
        traceback.print_exc()
        set_status(job, stage="failed", error=str(e)[:500], message="فشلت الدبلجة")


def worker():
    while True:
        job, opts = WORK.get()
        process(job, opts)


# ---------------------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _json(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _auth(self):
        q = parse_qs(urlparse(self.path).query)
        if q.get("k", [""])[0] != TOKEN and self.headers.get("X-Key") != TOKEN:
            self._json(403, {"error": "bad key"})
            return None
        return {k: v[0] for k, v in q.items()}

    def _job(self, q):
        job = re.sub(r"[^A-Za-z0-9_-]", "", q.get("job", ""))[:64]
        if not job:
            self._json(400, {"error": "missing job"})
            return None
        return job

    def do_GET(self):
        path = urlparse(self.path).path
        q = self._auth()
        if q is None:
            return
        if path == "/health":
            gpu = "CPU"
            try:
                import torch
                if torch.cuda.is_available():
                    gpu = torch.cuda.get_device_name(0)
            except Exception:
                pass
            return self._json(200, {"ok": True, "gpu": gpu})
        job = self._job(q)
        if job is None:
            return
        if path == "/status":
            with LOCK:
                st = dict(JOBS.get(job) or {"stage": "unknown", "error": "لا توجد مهمة بهذا الرقم", "done": False})
            st.pop("audio", None); st.pop("srt", None)
            return self._json(200, st)
        if path == "/result":
            with LOCK:
                st = JOBS.get(job) or {}
            f = st.get("audio") if q.get("file", "audio") == "audio" else st.get("srt")
            if not f or not os.path.exists(f):
                return self._json(404, {"error": "not ready"})
            size = os.path.getsize(f)
            start, end = 0, size - 1
            rng = self.headers.get("Range")
            if rng:
                m = re.match(r"bytes=(\d+)-(\d*)", rng)
                if m:
                    start = int(m.group(1))
                    end = int(m.group(2)) if m.group(2) else size - 1
                self.send_response(206)
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            else:
                self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(end - start + 1))
            self.end_headers()
            with open(f, "rb") as fh:
                fh.seek(start)
                left = end - start + 1
                while left > 0:
                    b = fh.read(min(1 << 20, left))
                    if not b:
                        break
                    self.wfile.write(b)
                    left -= len(b)
            return
        self._json(404, {"error": "unknown"})

    def do_POST(self):
        path = urlparse(self.path).path
        q = self._auth()
        if q is None:
            return
        job = self._job(q)
        if job is None:
            return
        d = os.path.join(ROOT, job)
        os.makedirs(d, exist_ok=True)
        if path == "/upload":
            part = int(q.get("part", "0"))
            n = int(self.headers.get("Content-Length", "0"))
            tmp = os.path.join(d, f"part_{part}.tmp")
            with open(tmp, "wb") as f:
                left = n
                while left > 0:
                    b = self.rfile.read(min(1 << 20, left))
                    if not b:
                        break
                    f.write(b)
                    left -= len(b)
            if left > 0:
                return self._json(400, {"error": "incomplete upload"})
            os.replace(tmp, os.path.join(d, f"part_{part}"))
            return self._json(200, {"ok": True, "part": part, "bytes": n})
        if path == "/start":
            parts = int(q.get("parts", "1"))
            for i in range(parts):
                if not os.path.exists(os.path.join(d, f"part_{i}")):
                    return self._json(400, {"error": f"missing part {i}"})
            with LOCK:
                running = JOBS.get(job, {}).get("stage") not in (None, "failed", "done", "unknown")
            if not running:
                set_status(job, stage="queued", progress=0, message="في الانتظار", done=False, error=None)
                WORK.put((job, {"parts": parts, "lang": q.get("lang", ""), "speakers": int(q.get("speakers", "0") or 0)}))
            return self._json(200, {"ok": True})
        self._json(404, {"error": "unknown"})


def main():
    global TOKEN
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--token", required=True)
    a = ap.parse_args()
    TOKEN = a.token
    threading.Thread(target=worker, daemon=True).start()
    if not FAKE:
        # warm up the models in the background so the first dub starts faster
        def warm():
            for f in (whisper_model, xtts_model, nllb):
                try:
                    f()
                except Exception:
                    traceback.print_exc()
        threading.Thread(target=warm, daemon=True).start()
    print(f"server on :{a.port}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
