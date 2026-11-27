"""
Arabic dubbing server for Google Colab (free GPU), driven from the Android app.  — v2 "film dubbing"

Pipeline per job (every stage has a fallback, so one weak link never sinks the whole job):
  1. Separation   BS-Roformer (audio-separator): the actors' voices / the music + effects.
                  fallback: a second Roformer model → no separation (the original speech is ducked instead).
  2. Speech→text  Whisper large-v3 (faster-whisper, batched), word timestamps; lines are split at pauses
                  and at sentence ends, which is where the speaker usually changes.
                  fallback: sequential faster-whisper → Whisper large-v3 through transformers.
  3. Speakers     ECAPA voice fingerprints (SpeechBrain) + clustering.  fallback: MFCC fingerprints.
  4. Translation  Hunyuan-MT-7B (4-bit), the WMT25 winner; lines that come out wrong are redone with NLLB-200.
                  fallback: NLLB-200 1.3B for everything.
  5. Voices       OmniVoice zero-shot cloning. Every line gets its own voice prompt: the actor's clean
                  reference clips plus the line's own original audio, so the Arabic keeps the actor's timbre
                  and the line's emotion (shouting, whispering, crying…). Each line is generated with the
                  duration that fits its original time slot, then matched to the original line's loudness.
  6. Mix          Arabic voices + untouched music/effects + the original non-dialogue vocal sounds
                  (laughs, screams, breaths, songs), limiter, AAC 192k + Arabic SRT.

HTTP API (every call needs ?k=<token> or the X-Key header):
  GET  /health                         -> {"ok": true, "gpu": "...", "version": 2}
  POST /upload?job=ID&part=N           (raw bytes)  one chunk of the audio file
  POST /start?job=ID&parts=N&lang=xx&speakers=0
  GET  /status?job=ID                  -> {"stage", "progress", "message", "done", "error", ...}
  GET  /result?job=ID&file=audio|srt   (supports Range)
"""
import argparse
import gc
import json
import math
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

VERSION = 2
FAKE = os.environ.get("DUB_FAKE") == "1"          # protocol test without GPU models
ROOT = os.environ.get("DUB_ROOT", "/content/dub_jobs")
CACHE = os.environ.get("DUB_CACHE", "/content/dub_models")
os.makedirs(ROOT, exist_ok=True)
os.makedirs(CACHE, exist_ok=True)

TOKEN = ""
JOBS = {}
WORK = queue.Queue()
LOCK = threading.Lock()

SEP_MODELS = ["model_bs_roformer_ep_317_sdr_12.9755.ckpt", "model_bs_roformer_ep_368_sdr_12.9628.ckpt"]
WHISPER = "large-v3"
HUNYUAN = "tencent/Hunyuan-MT-7B"
NLLB_MODEL = "facebook/nllb-200-distilled-1.3B"
OMNI = "k2-fsa/OmniVoice"
ECAPA = "speechbrain/spkrec-ecapa-voxceleb"
HY_PROMPT = "Translate the following segment into Arabic, without additional explanation.\n\n{}"

# languages Hunyuan-MT-7B was trained on (Whisper codes)
HUNYUAN_LANGS = {
    "en", "fr", "pt", "es", "ja", "tr", "ru", "ko", "th", "it", "de", "vi", "ms", "id", "tl", "hi", "zh", "pl",
    "cs", "nl", "km", "my", "fa", "gu", "ur", "te", "mr", "he", "bn", "ta", "uk", "kk", "mn", "ug", "bo", "yue",
}
NLLB = {
    "en": "eng_Latn", "fr": "fra_Latn", "es": "spa_Latn", "tr": "tur_Latn", "hi": "hin_Deva", "ko": "kor_Hang",
    "ja": "jpn_Jpan", "zh": "zho_Hans", "de": "deu_Latn", "it": "ita_Latn", "pt": "por_Latn", "ru": "rus_Cyrl",
    "ur": "urd_Arab", "fa": "pes_Arab", "id": "ind_Latn", "nl": "nld_Latn", "pl": "pol_Latn", "uk": "ukr_Cyrl",
    "vi": "vie_Latn", "th": "tha_Thai", "ms": "zsm_Latn", "bn": "ben_Beng", "ta": "tam_Taml", "te": "tel_Telu",
    "sv": "swe_Latn", "ro": "ron_Latn", "el": "ell_Grek", "cs": "ces_Latn", "hu": "hun_Latn", "he": "heb_Hebr",
    "tl": "tgl_Latn", "sw": "swh_Latn", "pa": "pan_Guru", "mr": "mar_Deva", "gu": "guj_Gujr", "fi": "fin_Latn",
    "da": "dan_Latn", "no": "nob_Latn", "sr": "srp_Cyrl", "hr": "hrv_Latn", "bg": "bul_Cyrl", "az": "azj_Latn",
    "kk": "kaz_Cyrl", "uz": "uzn_Latn", "ps": "pbt_Arab", "so": "som_Latn", "am": "amh_Ethi", "ha": "hau_Latn",
}


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def set_status(job, **kw):
    with LOCK:
        JOBS.setdefault(job, {"stage": "queued", "progress": 0, "message": "في الانتظار", "done": False, "error": None})
        JOBS[job].update(kw)
        if "message" in kw:
            log(f"[{job}] {JOBS[job].get('progress', 0):.0f}% {kw['message']}")


def sh(cmd):
    r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if r.returncode != 0:
        raise RuntimeError(r.stdout[-1500:])
    return r.stdout


class Ticker:
    """Moves the progress bar smoothly through a stage whose library gives no progress of its own."""

    def __init__(self, job, p0, p1, expected):
        self._stop = threading.Event()

        def run():
            t0 = time.time()
            while not self._stop.wait(3):
                set_status(job, progress=p0 + (p1 - p0) * min(0.95, (time.time() - t0) / max(1.0, expected)))

        threading.Thread(target=run, daemon=True).start()

    def stop(self):
        self._stop.set()


# ---------------------------------------------------------------------------- pure helpers (unit-tested)

SENT_END = ".!?؟。！？…"
JUNK = ("subtitles by", "amara.org", "thanks for watching", "thank you for watching", "please subscribe",
        "transcribed by", "translated by")


def is_junk(text):
    t = text.strip()
    if not t or t[0] in "[(♪*" or set(t) <= set(".,!?-♪ …*"):
        return True
    low = t.lower()
    return any(j in low for j in JUNK)


def split_at_pauses(words, max_gap=0.6, sent_gap=0.15, max_len=12.0):
    """words: [(start, end, text)] → [(start, end, text, words)].
    Splits where the speaker pauses, and at a sentence end followed by a short pause (a reply often starts there)."""
    groups, cur = [], []
    for w in words:
        if cur:
            gap = w[0] - cur[-1][1]
            last = cur[-1][2].strip()[-1:]
            ends = last != "" and last in SENT_END
            too_long = w[1] - cur[0][0] > max_len and last != "" and last in SENT_END + ",،;"
            if gap > max_gap or (ends and gap >= sent_gap) or too_long:
                groups.append(cur)
                cur = []
        cur.append(w)
    if cur:
        groups.append(cur)
    out = []
    for g in groups:
        text = "".join(t for _, _, t in g).strip()
        if text:
            out.append((g[0][0], g[-1][1], text, list(g)))
    return out


def merge_lines(pieces, max_gap=0.35, max_dur=12.0, max_chars=220):
    """Joins consecutive pieces of one speaker that belong to the same sentence into one dubbing line
    (better translation context and more natural Arabic speech)."""
    lines = []
    for i, p in enumerate(pieces):
        if lines:
            L = lines[-1]
            last = L["text"].rstrip()[-1:]
            open_sentence = last != "" and last not in SENT_END
            if (L["spk"] == p["spk"] and open_sentence and p["start"] - L["end"] < max_gap
                    and p["end"] - L["start"] <= max_dur and len(L["text"]) + len(p["text"]) < max_chars):
                L["end"] = p["end"]
                L["text"] = L["text"] + " " + p["text"]
                L["words"] = L["words"] + list(p.get("words", []))
                L["pieces"].append(i)
                L["conf"] = min(L["conf"], p.get("conf", 0.0))
                L["lp"] = min(L["lp"], p.get("lp", -1.0))
                continue
        lines.append({"start": p["start"], "end": p["end"], "text": p["text"], "spk": p["spk"],
                      "conf": p.get("conf", 0.0), "lp": p.get("lp", -1.0),
                      "words": list(p.get("words", [])), "pieces": [i]})
    return lines


def target_duration(orig, avail, natural, lo=0.85, hi=1.15, squeeze=0.78):
    """How long the Arabic line should last.
    orig: original line length; avail: time until the next line; natural: this voice's natural pace for the Arabic.
    Follows the original timing as closely as a natural pace allows (±15 %), and speeds up (at most ~28 %)
    only when the slot is really too short."""
    t = min(max(orig, natural * lo), natural * hi)
    if t > avail:
        t = max(natural * squeeze, avail)
    return max(0.35, t)


def attach_to_cores(lab, X, core_min=3, attach=0.45):
    """Strict clustering splits an actor whose voice varies (shouting, whispering) into many tiny clusters.
    Real actors are the clusters with several lines; a leftover line joins the closest one only if it is
    clearly that voice, so a minor character with one or two lines keeps his own voice."""
    import numpy as np
    lab = np.array(lab)
    for _ in range(2):
        ks, cnt = np.unique(lab, return_counts=True)
        core = [k for k, c in zip(ks, cnt) if c >= core_min]
        if not core:
            break
        C = np.stack([X[lab == k].mean(axis=0) for k in core])
        C = C / (np.linalg.norm(C, axis=1, keepdims=True) + 1e-9)
        for i in range(len(X)):
            if lab[i] in core:
                continue
            s = C @ X[i]
            j = int(np.argmax(s))
            if s[j] >= attach:
                lab[i] = core[j]
    return [int(l) for l in lab]


def assign_speakers(embs, durs, starts, threshold, n_speakers=0, min_dur=1.0):
    """embs: unit vectors (or None for pieces too short to fingerprint).
    Clusters the reliable pieces, then gives every other piece the closest speaker.
    Returns (labels, confidences); labels are numbered by amount of speech, 0 = main speaker."""
    import numpy as np
    n = len(embs)
    idx = [i for i in range(n) if embs[i] is not None and durs[i] >= min_dur]
    if len(idx) < 2:
        idx = [i for i in range(n) if embs[i] is not None]
    if not idx:
        return [0] * n, [0.0] * n
    if len(idx) == 1:
        lab = {idx[0]: 0}
    else:
        from sklearn.cluster import AgglomerativeClustering
        X = np.stack([embs[i] for i in idx])
        if n_speakers and n_speakers > 0:
            raw = AgglomerativeClustering(n_clusters=min(n_speakers, len(X)), metric="cosine",
                                          linkage="average").fit_predict(X)
        else:
            raw = AgglomerativeClustering(n_clusters=None, metric="cosine", linkage="average",
                                          distance_threshold=threshold).fit_predict(X)
            raw = attach_to_cores(raw, X)
        lab = {i: int(l) for i, l in zip(idx, raw)}
    cent = {}
    for l in set(lab.values()):
        v = np.mean([embs[i] for i in lab if lab[i] == l], axis=0)
        cent[l] = v / (np.linalg.norm(v) + 1e-9)
    labels, conf = [None] * n, [0.0] * n
    for i in range(n):
        e = embs[i]
        if i in lab:
            labels[i] = lab[i]
            conf[i] = float(np.dot(e, cent[lab[i]]))
        elif e is not None:
            best = max(cent, key=lambda c: float(np.dot(e, cent[c])))
            labels[i] = best
            conf[i] = float(np.dot(e, cent[best]))
    known = [i for i in range(n) if labels[i] is not None]
    for i in range(n):
        if labels[i] is None:      # too short to fingerprint: the speaker closest in time
            near = min(known, key=lambda k: abs(starts[k] - starts[i]))
            labels[i] = labels[near]
    total = {}
    for l, d in zip(labels, durs):
        total[l] = total.get(l, 0.0) + d
    remap = {l: k for k, l in enumerate(sorted(total, key=lambda l: -total[l]))}
    return [remap[l] for l in labels], conf


def pick_clips(cands, want, cap, exclude=()):
    """Best reference clips (highest score first) until `want` seconds, never more than `cap` seconds."""
    chosen, total = [], 0.0
    for c in sorted(cands, key=lambda c: -c["score"]):
        if c["i"] in exclude or total + c["dur"] > cap:
            continue
        chosen.append(c)
        total += c["dur"]
        if total >= want:
            break
    chosen.sort(key=lambda c: c["start"])
    return chosen, total


def cut_words(words, start, max_dur):
    """The longest beginning of a line, at a word boundary, that lasts at most max_dur → (end, text)."""
    keep = [w for w in words if w[1] <= start + max_dur]
    if not keep:
        return None, ""
    return keep[-1][1], "".join(w[2] for w in keep).strip()


def place_clips(lines, lens):
    """Start time of every Arabic clip: at its original time, but never on top of the previous clip
    (unless the original lines overlap too, e.g. an interruption)."""
    starts = [None] * len(lines)
    prev_end, prev_line_end, prev_spk = -1.0, -1.0, None
    for i, L in enumerate(lines):
        if lens[i] is None:
            continue
        s = L["start"]
        interrupt = prev_line_end > L["start"] + 0.05 and L["spk"] != prev_spk
        if s < prev_end + 0.04 and not interrupt:
            s = prev_end + 0.04
        starts[i] = s
        prev_end = max(prev_end, s + lens[i])
        prev_line_end, prev_spk = L["end"], L["spk"]
    return starts


def mute_envelope(spans, total, rate=100, margin=0.12, ramp=0.06):
    """Gain curve (rate per second) that is 0 over the dubbed lines and 1 elsewhere, with soft edges."""
    import numpy as np
    n = int(total * rate) + 2
    m = np.zeros(n, dtype=np.float32)
    for a, b in spans:
        i0 = max(0, int((a - margin) * rate))
        i1 = min(n, int(math.ceil((b + margin) * rate)))
        m[i0:i1] = 1.0
    k = max(1, int(ramp * rate))
    if k > 1:
        m = np.convolve(m, np.ones(k, dtype=np.float32) / k, mode="same")
    return 1.0 - np.clip(m, 0.0, 1.0)


def arabic_ratio(text):
    letters = [c for c in text if c.isalpha()]
    if not letters:
        return 0.0
    return sum(1 for c in letters if "؀" <= c <= "ۿ" or "ݐ" <= c <= "ݿ") / len(letters)


def clean_translation(t):
    t = t.strip()
    t = re.sub(r"^(الترجمة|Translation|Arabic)\s*[:：]\s*", "", t, flags=re.I)
    t = t.strip().strip('"“”«»\'`').strip()
    return re.sub(r"\s*\n+\s*", " ", t)


def good_translation(src, ar):
    if not ar or not ar.strip() or arabic_ratio(ar) < 0.5:
        return False
    if len(ar) > 3 * len(src) + 30:                       # a generation loop
        return False
    w = ar.split()
    return not (len(w) >= 8 and len(set(w)) <= len(w) // 4)


AR_DIGITS = str.maketrans("٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹", "01234567890123456789")


def tts_text(t):
    """Arabic text as it should be spoken: digits become words, symbols the voice can't say are removed."""
    t = t.translate(AR_DIGITS).replace("ـ", "")
    t = re.sub(r"https?://\S+", " ", t)
    t = re.sub(r"(?<=\d),(?=\d{3}\b)", "", t)
    t = re.sub(r"(\d+(?:\.\d+)?)\s*%", r"\1 بالمئة", t)
    try:
        from num2words import num2words

        def num(m):
            s = m.group()
            try:
                return " " + num2words(float(s) if "." in s else int(s), lang="ar") + " "
            except Exception:
                return s
        t = re.sub(r"\d+(?:\.\d+)?", num, t)
    except ImportError:
        pass
    t = re.sub(r"[♪#*_~^<>|\[\]{}\\/=+@]", " ", t)
    return re.sub(r"\s+", " ", t).strip()


# ---------------------------------------------------------------------------- audio helpers

def ff_mono(src, dst, sr):
    sh(["ffmpeg", "-y", "-loglevel", "error", "-i", src, "-ac", "1", "-ar", str(sr), "-c:a", "pcm_s16le", dst])
    return dst


def read_wav(path):
    import numpy as np
    try:
        import soundfile as sf
        y, sr = sf.read(path, dtype="float32", always_2d=False)
    except ImportError:
        import wave
        with wave.open(path, "rb") as w:
            sr, ch = w.getframerate(), w.getnchannels()
            y = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype("float32") / 32768.0
            if ch > 1:
                y = y.reshape(-1, ch)
    if y.ndim > 1:
        y = y.mean(axis=1)
    return np.ascontiguousarray(y, dtype=np.float32), sr


def write_wav(path, data, sr):
    import numpy as np
    pcm = np.clip(data, -1, 1)
    try:
        import soundfile as sf
        sf.write(path, pcm, sr, subtype="PCM_16")
    except ImportError:
        import wave
        ch = 1 if pcm.ndim == 1 else pcm.shape[1]
        with wave.open(path, "wb") as w:
            w.setnchannels(ch)
            w.setsampwidth(2)
            w.setframerate(sr)
            w.writeframes((pcm * 32767).astype("<i2").tobytes())


def wav_blocks(path, seconds=10):
    """Yields (first_frame, block[frames, channels], sample_rate) without loading a whole film into memory."""
    import numpy as np
    try:
        import soundfile as sf
        with sf.SoundFile(path) as f:
            sr, pos = f.samplerate, 0
            while True:
                b = f.read(int(seconds * sr), dtype="float32", always_2d=True)
                if not len(b):
                    break
                yield pos, b, sr
                pos += len(b)
    except ImportError:
        import wave
        with wave.open(path, "rb") as w:
            sr, ch, pos = w.getframerate(), w.getnchannels(), 0
            while True:
                raw = w.readframes(int(seconds * sr))
                if not raw:
                    break
                b = np.frombuffer(raw, dtype="<i2").astype("float32").reshape(-1, ch) / 32768.0
                yield pos, b, sr
                pos += len(b)


def wav_level(path):
    """(rms, has_nan) of a whole file, block by block."""
    import numpy as np
    s, n, bad = 0.0, 0, False
    for _, b, _ in wav_blocks(path, 30):
        if not np.isfinite(b).all():
            bad = True
            b = np.nan_to_num(b)
        s += float(np.sum(b.astype(np.float64) ** 2))
        n += b.size
    return (math.sqrt(s / n) if n else 0.0), bad


def rms(x):
    import numpy as np
    return float(np.sqrt(np.mean(np.square(x)))) if len(x) else 0.0


def active_rms(x, sr, frame=0.02, floor_db=-35.0):
    """Loudness of the speech itself, ignoring the pauses inside a line."""
    import numpy as np
    n = max(1, int(frame * sr))
    k = len(x) // n
    if k < 2:
        return rms(x)
    e = np.sqrt(np.mean(np.square(x[:k * n].reshape(k, n)), axis=1) + 1e-12)
    act = e[e >= e.max() * 10 ** (floor_db / 20)]
    return float(np.sqrt(np.mean(np.square(act)))) if len(act) else rms(x)


def make_residual(vocals, out, spans, total):
    """The original vocals with the dubbed lines muted: laughs, screams, breaths and songs stay."""
    import numpy as np
    env = mute_envelope(spans, total)
    grid = np.arange(len(env), dtype=np.float64)
    try:
        import soundfile as sf
        writer, sr0 = None, None
        for pos, b, sr in wav_blocks(vocals):
            if writer is None:
                writer = sf.SoundFile(out, "w", samplerate=sr, channels=b.shape[1], subtype="PCM_16")
            g = np.interp((pos + np.arange(len(b))) * 100.0 / sr, grid, env).astype(np.float32)
            writer.write(np.clip(b * g[:, None], -1, 1))
        if writer is not None:
            writer.close()
    except ImportError:
        import wave
        w = None
        for pos, b, sr in wav_blocks(vocals):
            if w is None:
                w = wave.open(out, "wb")
                w.setnchannels(b.shape[1])
                w.setsampwidth(2)
                w.setframerate(sr)
            g = np.interp((pos + np.arange(len(b))) * 100.0 / sr, grid, env).astype(np.float32)
            w.writeframes((np.clip(b * g[:, None], -1, 1) * 32767).astype("<i2").tobytes())
        if w is not None:
            w.close()
    return out


# ---------------------------------------------------------------------------- models & GPU memory

_models = {}
_mlock = threading.RLock()


def free_gpu():
    gc.collect()
    try:
        import torch
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
    except Exception:
        pass


def gpu_free_gb():
    try:
        import torch
        if torch.cuda.is_available():
            return torch.cuda.mem_get_info()[0] / 2 ** 30
    except Exception:
        pass
    return 99.0


def unload(*names):
    with _mlock:
        for n in names:
            if _models.pop(n, None) is not None:
                log(f"تحرير ذاكرة البطاقة: {n}")
    free_gpu()


def make_room(gb, keep=()):
    """Unloads idle models (cheapest to reload first) until `gb` GiB of GPU memory are free."""
    with _mlock:
        for name in ("whisper", "ecapa", "omni", "hunyuan"):
            if gpu_free_gb() >= gb:
                return
            if name in _models and name not in keep:
                unload(name)


def get_whisper():
    with _mlock:
        if "whisper" not in _models:
            make_room(6.0, keep=("whisper",))           # 3 GB of weights + room for batched decoding
            from faster_whisper import WhisperModel
            _models["whisper"] = WhisperModel(WHISPER, device="cuda", compute_type="float16",
                                              download_root=os.path.join(CACHE, "whisper"))
        return _models["whisper"]


def get_omni():
    with _mlock:
        if "omni" not in _models:
            make_room(3.0, keep=("omni",))
            import torch
            from omnivoice import OmniVoice
            _models["omni"] = OmniVoice.from_pretrained(OMNI, device_map="cuda:0", dtype=torch.float16)
        return _models["omni"]


def get_ecapa():
    with _mlock:
        if "ecapa" not in _models:
            try:
                from speechbrain.inference.classifiers import EncoderClassifier
            except ImportError:
                from speechbrain.inference.speaker import EncoderClassifier
            _models["ecapa"] = EncoderClassifier.from_hparams(
                source=ECAPA, savedir=os.path.join(CACHE, "ecapa"), run_opts={"device": "cuda"})
        return _models["ecapa"]


def _hy_translate(tok, model, texts, gen_prompt):
    import torch
    prompts = [tok.apply_chat_template([{"role": "user", "content": HY_PROMPT.format(t)}],
                                       tokenize=False, add_generation_prompt=gen_prompt) for t in texts]
    enc = tok(prompts, return_tensors="pt", padding=True, add_special_tokens=False)
    ids, mask = enc["input_ids"].to(model.device), enc["attention_mask"].to(model.device)
    with torch.no_grad():
        out = model.generate(input_ids=ids, attention_mask=mask, max_new_tokens=256, do_sample=False, num_beams=1,
                             repetition_penalty=1.05, pad_token_id=tok.pad_token_id)
    return [clean_translation(tok.decode(o, skip_special_tokens=True)) for o in out[:, ids.shape[1]:]]


def get_hunyuan():
    with _mlock:
        if "hunyuan" not in _models:
            make_room(7.0, keep=("hunyuan",))
            import torch
            from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
            tok = AutoTokenizer.from_pretrained(HUNYUAN)
            tok.padding_side = "left"
            if tok.pad_token is None:
                tok.pad_token = tok.eos_token
            q = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4", bnb_4bit_use_double_quant=True,
                                   bnb_4bit_compute_dtype=torch.float16)
            model = AutoModelForCausalLM.from_pretrained(HUNYUAN, quantization_config=q, device_map="cuda:0",
                                                         dtype=torch.float16).eval()
            # make sure it really answers with Arabic (prompt format and fp16 on this GPU)
            mode = None
            for gen_prompt in (False, True):
                test = _hy_translate(tok, model, ["Good morning! How are you today?"], gen_prompt)[0]
                log(f"اختبار الترجمة: {test}")
                if good_translation("Good morning! How are you today?", test):
                    mode = gen_prompt
                    break
            if mode is None:
                del model
                free_gpu()
                raise RuntimeError("Hunyuan-MT did not produce Arabic")
            _models["hunyuan"] = (tok, model, mode)
        return _models["hunyuan"]


def warm_up():
    """Downloads every model and loads the small ones while the user is still picking a video."""
    t0 = time.time()
    log("تنزيل النماذج وتجهيزها (أول مرة 5–10 دقائق)…")
    try:
        from audio_separator.separator import Separator
        Separator(output_dir=os.path.join(CACHE, "sep_out"), model_file_dir=os.path.join(CACHE, "sep")) \
            .download_model_and_data(SEP_MODELS[0])
        log("جاهز: نموذج فصل الأصوات")
    except Exception:
        traceback.print_exc()
    for name, f in (("تحويل الكلام إلى نص", get_whisper), ("تمييز الأصوات", get_ecapa), ("أصوات الممثلين", get_omni)):
        try:
            f()
            log(f"جاهز: {name}")
        except Exception:
            traceback.print_exc()
    try:
        from huggingface_hub import snapshot_download
        snapshot_download(HUNYUAN, allow_patterns=["*.json", "*.safetensors", "*.txt", "*.model", "*.jinja",
                                                   "*.tiktoken"])
        log("جاهز: نموذج الترجمة")
    except Exception:
        traceback.print_exc()
    log(f"اكتمل تجهيز النماذج خلال {time.time() - t0:.0f} ثانية")


# ---------------------------------------------------------------------------- 1. separation

def stage_separate(job, wav, d, total):
    set_status(job, stage="separate", progress=4, message="فصل أصوات الممثلين عن الموسيقى والمؤثرات (BS-Roformer)…")
    out = os.path.join(d, "sep")
    os.makedirs(out, exist_ok=True)
    if FAKE:
        voc, bg = os.path.join(out, "vocals.wav"), os.path.join(out, "background.wav")
        sh(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-af", "highpass=f=200", voc])
        sh(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-af", "lowpass=f=200", bg])
        return voc, bg, "fake"
    in_rms, _ = wav_level(wav)
    ticker = Ticker(job, 4, 14, expected=40 + total * 0.12)
    try:
        for model_name in SEP_MODELS:
            sep = None
            try:
                make_room(4.5)
                import logging
                from audio_separator.separator import Separator
                sep = Separator(log_level=logging.WARNING, output_dir=out, output_format="WAV",
                                model_file_dir=os.path.join(CACHE, "sep"),
                                use_autocast=True, use_soundfile=True,
                                chunk_duration=600 if total > 900 else None,
                                mdxc_params={"segment_size": 256, "override_model_segment_size": False,
                                             "batch_size": 1, "overlap": 2 if total > 1800 else 4,
                                             "pitch_shift": 0})
                sep.load_model(model_filename=model_name)
                names = {"Vocals": "vocals", "vocals": "vocals", "Instrumental": "background",
                         "instrumental": "background", "Other": "background", "other": "background"}
                files = [f if os.path.isabs(f) else os.path.join(out, f)
                         for f in sep.separate(wav, custom_output_names=names)]
                voc = next((f for f in files if os.path.basename(f).lower().startswith("vocals")), None)
                bg = next((f for f in files if os.path.basename(f).lower().startswith("background")), None)
                if not (voc and bg and os.path.exists(voc) and os.path.exists(bg)):
                    raise RuntimeError(f"unexpected separation output: {files}")
                v_rms, v_bad = wav_level(voc)
                b_rms, b_bad = wav_level(bg)
                if v_bad or b_bad or (in_rms > 0.001 and v_rms + b_rms < in_rms * 0.05):
                    raise RuntimeError("separation produced silence or NaN")
                return voc, bg, model_name.split("_sdr")[0].replace("model_", "")
            except Exception:
                traceback.print_exc()
            finally:
                del sep
                free_gpu()
    finally:
        ticker.stop()
    return wav, None, "بدون فصل"


# ---------------------------------------------------------------------------- 2. speech → text

def _collect(job, segments, total):
    out = []
    for s in segments:
        if s.no_speech_prob > 0.6 and s.avg_logprob < -1.0:
            continue
        if s.compression_ratio > 2.6 or is_junk(s.text):
            continue
        words = [(float(w.start), float(w.end), w.word) for w in (s.words or []) if w.end > w.start]
        pieces = split_at_pauses(words) if words else [(float(s.start), float(s.end), s.text.strip(), [])]
        for a, b, txt, ws in pieces:
            if not is_junk(txt):
                out.append({"start": a, "end": b, "text": txt, "words": ws, "lp": float(s.avg_logprob)})
        set_status(job, progress=15 + 17 * min(1.0, s.end / max(total, 1.0)))
    out.sort(key=lambda p: p["start"])
    return out


def stage_transcribe(job, voc16_y, lang, total):
    set_status(job, stage="transcribe", progress=15, message="تحويل الكلام إلى نص (Whisper large-v3)…")
    if FAKE:
        def w(a, b, t):
            return a, b, t
        words = [w(1.0, 1.4, " Hello"), w(1.45, 2.0, " and"), w(2.05, 3.0, " welcome."),
                 w(3.2, 3.5, " Who"), w(3.55, 3.8, " are"), w(3.85, 4.3, " you?"),
                 w(5.0, 5.6, " It's"), w(5.65, 6.5, " me,"), w(6.55, 7.0, " your"), w(7.05, 7.9, " friend.")]
        return [{"start": a, "end": b, "text": t, "words": ws, "lp": -0.2}
                for a, b, t, ws in split_at_pauses(words)], lang or "en", "fake"
    try:
        from faster_whisper import BatchedInferencePipeline
        pipe = BatchedInferencePipeline(model=get_whisper())
        segments, info = pipe.transcribe(voc16_y, language=lang, task="transcribe", beam_size=5, batch_size=8,
                                         word_timestamps=True, vad_filter=True, language_detection_segments=3)
        out = _collect(job, segments, total)
        return out, info.language, "Whisper large-v3"
    except Exception:
        traceback.print_exc()
        free_gpu()
    try:
        segments, info = get_whisper().transcribe(
            voc16_y, language=lang, task="transcribe", beam_size=5, word_timestamps=True, vad_filter=True,
            condition_on_previous_text=False, hallucination_silence_threshold=2.0)
        out = _collect(job, segments, total)
        return out, info.language, "Whisper large-v3 (sequential)"
    except Exception:
        traceback.print_exc()
        unload("whisper")
    import torch
    from transformers import pipeline
    asr = pipeline("automatic-speech-recognition", model="openai/whisper-large-v3", dtype=torch.float16, device=0)
    kw = {"task": "transcribe"}
    if lang:
        kw["language"] = lang
    r = asr({"raw": voc16_y, "sampling_rate": 16000}, chunk_length_s=30, batch_size=8, return_timestamps=True,
            generate_kwargs=kw)
    out = []
    for c in r.get("chunks", []):
        a, b = c["timestamp"]
        if a is None or is_junk(c["text"]):
            continue
        out.append({"start": float(a), "end": float(b if b is not None else a + 3), "text": c["text"].strip(),
                    "words": [], "lp": -0.5})
    del asr
    free_gpu()
    if not lang:
        lang = "en"
        try:
            _, info = get_whisper().transcribe(voc16_y[: 16000 * 30])
            lang = info.language
        except Exception:
            pass
    return out, lang, "Whisper large-v3 (transformers)"


# ---------------------------------------------------------------------------- 3. speakers

def embed_ecapa(voc16_y, pieces):
    import numpy as np
    import torch
    enc = get_ecapa()
    sr = 16000
    items = sorted([(i, int(p["start"] * sr), int(p["end"] * sr)) for i, p in enumerate(pieces)
                    if p["end"] - p["start"] >= 0.25], key=lambda x: x[2] - x[1])
    embs = [None] * len(pieces)
    for k in range(0, len(items), 16):
        grp = items[k:k + 16]
        L = min(max(b - a for _, a, b in grp), 20 * sr)
        batch = np.zeros((len(grp), L), dtype=np.float32)
        lens = []
        for j, (_, a, b) in enumerate(grp):
            x = voc16_y[a:min(b, a + L)]
            batch[j, :len(x)] = x
            lens.append(max(len(x), 1) / L)
        with torch.no_grad():
            e = enc.encode_batch(torch.from_numpy(batch).to("cuda"), torch.tensor(lens, device="cuda"))
        e = e.reshape(len(grp), -1).float().cpu().numpy()
        for j, (i, _, _) in enumerate(grp):
            embs[i] = e[j] / (np.linalg.norm(e[j]) + 1e-9)
    return embs


def embed_mfcc(voc16_y, pieces):
    import librosa
    import numpy as np
    embs = []
    for p in pieces:
        a, b = int(p["start"] * 16000), int(p["end"] * 16000)
        if b - a < 4000:
            embs.append(None)
            continue
        m = librosa.feature.mfcc(y=voc16_y[a:b], sr=16000, n_mfcc=24)
        e = np.concatenate([m.mean(axis=1), m.std(axis=1)])
        e = (e - e.mean()) / (e.std() + 1e-9)
        embs.append(e / (np.linalg.norm(e) + 1e-9))
    return embs


def stage_speakers(job, voc16_y, pieces, n_speakers):
    set_status(job, stage="speakers", progress=33, message="تمييز أصوات الممثلين…")
    if FAKE:
        for k, p in enumerate(pieces):
            p["spk"], p["conf"] = (1 if p["text"].endswith("?") else 0), 0.9
        return len(set(p["spk"] for p in pieces)), "fake"
    method, thr = "ECAPA", 0.6
    try:
        embs = embed_ecapa(voc16_y, pieces)
    except Exception:
        traceback.print_exc()
        method, thr = "MFCC", 0.3
        embs = embed_mfcc(voc16_y, pieces)
    labels, conf = assign_speakers(embs, [p["end"] - p["start"] for p in pieces], [p["start"] for p in pieces],
                                   thr, n_speakers)
    for p, l, c in zip(pieces, labels, conf):
        p["spk"], p["conf"] = l, c
    return len(set(labels)), method


# ---------------------------------------------------------------------------- 4. translation

def translate_hunyuan(job, lines, ids):
    tok, model, mode = get_hunyuan()
    todo = sorted(ids, key=lambda i: len(lines[i]["text"]))
    B = 16
    for n in range(0, len(todo), B):
        part = todo[n:n + B]
        for i, t in zip(part, _hy_translate(tok, model, [lines[i]["text"] for i in part], mode)):
            lines[i]["ar"] = t
        set_status(job, progress=42 + 12 * min(1.0, (n + B) / max(1, len(todo))))


def translate_nllb(job, lines, lang, ids):
    import torch
    from transformers import AutoModelForSeq2SeqLM, AutoTokenizer
    src = NLLB.get(lang)
    if not src:
        raise RuntimeError(f"لغة الفيديو ({lang}) غير مدعومة في الترجمة")
    make_room(3.5)
    tok = AutoTokenizer.from_pretrained(NLLB_MODEL, src_lang=src)
    model = AutoModelForSeq2SeqLM.from_pretrained(NLLB_MODEL, dtype=torch.float16).to("cuda").eval()
    tgt = tok.convert_tokens_to_ids("arb_Arab")
    try:
        B = 16
        for n in range(0, len(ids), B):
            part = ids[n:n + B]
            enc = tok([lines[i]["text"] for i in part], return_tensors="pt", padding=True, truncation=True,
                      max_length=256).to("cuda")
            with torch.no_grad():
                out = model.generate(**enc, forced_bos_token_id=tgt, max_new_tokens=256, num_beams=4)
            for i, t in zip(part, tok.batch_decode(out, skip_special_tokens=True)):
                lines[i]["ar"] = t.strip()
            set_status(job, progress=42 + 12 * min(1.0, (n + B) / max(1, len(ids))))
    finally:
        del model, tok
        free_gpu()


def stage_translate(job, lines, lang):
    set_status(job, stage="translate", progress=40, message="الترجمة إلى العربية (Hunyuan-MT-7B)…")
    if lang == "ar":
        raise RuntimeError("الفيديو بالعربية أصلًا")
    used = None
    if FAKE:
        for L in lines:
            L["ar"] = "مرحبًا بكم، عمري 25 سنة!" if L["spk"] == 0 else "من أنت؟"
        used = "fake"
    ids = list(range(len(lines)))
    if used is None and (lang in HUNYUAN_LANGS or lang not in NLLB):
        try:
            set_status(job, message="تجهيز نموذج الترجمة Hunyuan-MT-7B…")
            get_hunyuan()
            set_status(job, message="الترجمة إلى العربية (Hunyuan-MT-7B)…")
            translate_hunyuan(job, lines, ids)
            used = "Hunyuan-MT-7B"
        except Exception:
            traceback.print_exc()
            unload("hunyuan")
    if used is None:
        set_status(job, message="الترجمة إلى العربية (NLLB-200)…")
        translate_nllb(job, lines, lang, ids)
        used = "NLLB-200"
    bad = [i for i, L in enumerate(lines) if not good_translation(L["text"], L.get("ar", ""))]
    if bad and not FAKE and used != "NLLB-200" and lang in NLLB:
        log(f"[{job}] إعادة ترجمة {len(bad)} جملة بنموذج NLLB")
        try:
            translate_nllb(job, lines, lang, bad)       # rescue the few lines that came out wrong
        except Exception:
            traceback.print_exc()
    kept = 0
    for L in lines:
        if good_translation(L["text"], L.get("ar", "")):
            L["tts"] = tts_text(L["ar"])
        else:
            L["tts"] = ""          # e.g. "Hmm!" or a name: the original voice stays there
            kept += 1
    if kept:
        log(f"[{job}] {kept} جملة تبقى بالصوت الأصلي")
    return used


# ---------------------------------------------------------------------------- 5. voices

def ref_candidates(pieces, voc, sr):
    """Clean single-speaker pieces with a reliable transcript: usable as voice references."""
    out = []
    for i, p in enumerate(pieces):
        d = p["end"] - p["start"]
        if d < 1.0 or d > 9.0:
            continue
        if rms(voc[int(p["start"] * sr):int(p["end"] * sr)]) < 0.004:
            continue
        score = p.get("conf", 0.5) + 0.25 * max(0.0, 1.0 + p.get("lp", -0.5)) + 0.1 * min(d, 6.0) / 6.0
        out.append({"i": i, "spk": p["spk"], "start": p["start"], "end": p["end"], "text": p["text"],
                    "dur": d, "score": score})
    return out


def build_prompts(job, model, voc, sr, lines, pieces, todo):
    """A voice prompt for every line: the actor's best clips + the line's own audio (its emotion and delivery)."""
    import numpy as np
    import torch
    gap = np.zeros(int(0.15 * sr), dtype=np.float32)

    def clip(a, b):
        return voc[int(a * sr):int(b * sr)]

    def make(parts, texts):
        seq = []
        for p in parts:
            seq += [p, gap]
        audio = np.concatenate(seq[:-1]).astype(np.float32)
        return model.create_voice_clone_prompt(ref_audio=(torch.from_numpy(audio), sr), ref_text=" ".join(texts))

    cands = ref_candidates(pieces, voc, sr)
    by_spk = {}
    for c in cands:
        by_spk.setdefault(c["spk"], []).append(c)
    anchors = {}
    for spk in sorted(set(L["spk"] for L in lines)):
        chosen, _ = pick_clips(by_spk.get(spk, []), want=7.0, cap=10.0)
        if not chosen:          # an actor with only very short lines: use what exists
            short = [{"i": i, "start": p["start"], "end": p["end"], "text": p["text"], "dur": p["end"] - p["start"],
                      "score": p["end"] - p["start"]} for i, p in enumerate(pieces)
                     if p["spk"] == spk and p["end"] - p["start"] >= 0.3]
            chosen, _ = pick_clips(short, want=6.0, cap=10.0)
        if chosen:
            try:
                anchors[spk] = make([clip(c["start"], c["end"]) for c in chosen], [c["text"] for c in chosen])
            except Exception:
                traceback.print_exc()
    if not anchors:
        raise RuntimeError("تعذّر استخراج بصمة صوت من الفيديو")
    main = max(anchors, key=lambda s: sum(L["end"] - L["start"] for L in lines if L["spk"] == s))
    prompts, own_used = {}, 0
    for k, i in enumerate(todo):
        L = lines[i]
        p = None
        dur = L["end"] - L["start"]
        if dur >= 1.2 and L.get("conf", 0.0) >= 0.5 and L.get("lp", -1.0) >= -1.0:
            own_end, own_text = L["end"], L["text"]
            if dur > 7.0:
                own_end, own_text = cut_words(L["words"], L["start"], 7.0)
            if own_end is not None and own_text:
                own = clip(L["start"], own_end)
                if rms(own) >= 0.004:
                    od = own_end - L["start"]
                    ctx = []
                    if od < 5.0:
                        ctx, _ = pick_clips(by_spk.get(L["spk"], []), want=6.5 - od, cap=10.0 - od,
                                            exclude=set(L["pieces"]))
                    try:
                        p = make([clip(c["start"], c["end"]) for c in ctx] + [own], [c["text"] for c in ctx] + [own_text])
                        own_used += 1
                    except Exception:
                        p = None
        prompts[i] = p or anchors.get(L["spk"]) or anchors[main]
        if k % 20 == 0:
            set_status(job, progress=55 + 4 * k / max(1, len(todo)))
    log(f"[{job}] بصمات الأصوات: {len(anchors)} ممثل، و{own_used} من {len(todo)} جملة بانفعالها الأصلي")
    return prompts


def stage_voices(job, d, lines, pieces, vocals, total):
    import numpy as np
    set_status(job, stage="voice", progress=55, message="تجهيز بصمة صوت كل ممثل (OmniVoice)…")
    todo = [i for i, L in enumerate(lines) if L.get("tts")]
    if not todo:
        raise RuntimeError("لم تنجح ترجمة أي جملة")
    clips = {}
    if FAKE:
        sr = 24000
        for i in todo:
            L = lines[i]
            n = int((L["end"] - L["start"]) * sr)
            clips[i] = (0.2 * np.sin(2 * np.pi * (330 + 110 * L["spk"]) * np.arange(n) / sr)).astype(np.float32)
        voc = read_wav(ff_mono(vocals, os.path.join(d, "voc_tts.wav"), sr))[0]
    else:
        model = get_omni()
        sr = model.sampling_rate
        fr = model.audio_tokenizer.config.frame_rate
        voc = read_wav(ff_mono(vocals, os.path.join(d, "voc_tts.wav"), sr))[0]
        prompts = build_prompts(job, model, voc, sr, lines, pieces, todo)
        for k, i in enumerate(todo):          # the length each Arabic line should have
            L, p = lines[i], prompts[i]
            try:
                nat = model._estimate_target_tokens(L["tts"], p.ref_text, p.ref_audio_tokens.size(-1)) / fr
            except Exception:
                nat = max(0.5, len(L["tts"]) / 14.0)
            nxt = lines[i + 1]["start"] if i + 1 < len(lines) else total + 1.0
            orig = L["end"] - L["start"]
            L["target"] = target_duration(orig, max(nxt - L["start"] - 0.05, orig), nat)
        set_status(job, progress=60, message="الدبلجة بأصوات الممثلين (OmniVoice)…")
        order = sorted(todo, key=lambda i: lines[i]["target"])
        B, done = 8, 0
        for n in range(0, len(order), B):
            ids = order[n:n + B]
            try:
                audios = model.generate(text=[lines[i]["tts"] for i in ids], language="arb",
                                        voice_clone_prompt=[prompts[i] for i in ids],
                                        duration=[lines[i]["target"] for i in ids],
                                        num_step=32, guidance_scale=2.0, denoise=True,
                                        pad_duration=0.03, fade_duration=0.03)
            except Exception:
                traceback.print_exc()
                free_gpu()
                audios = []
                for i in ids:              # one bad line must not sink the batch
                    try:
                        audios += model.generate(text=lines[i]["tts"], language="arb", voice_clone_prompt=prompts[i],
                                                 duration=lines[i]["target"], num_step=32, guidance_scale=2.0,
                                                 denoise=True, pad_duration=0.03, fade_duration=0.03)
                    except Exception:
                        traceback.print_exc()
                        audios.append(None)
            for i, w in zip(ids, audios):
                if w is not None and len(np.asarray(w).reshape(-1)) > sr // 20:
                    clips[i] = np.asarray(w, dtype=np.float32).reshape(-1)
            done += len(ids)
            set_status(job, progress=60 + 33 * done / len(order))
    # loudness: as loud as the original line (shouting stays loud, whispers stay soft)
    for i, w in clips.items():
        L = lines[i]
        target = max(0.012, active_rms(voc[int(L["start"] * sr):int(L["end"] * sr)], sr))
        w = w * min(5.0, max(0.2, target / (active_rms(w, sr) + 1e-6)))
        peak = float(np.abs(w).max()) if len(w) else 0.0
        clips[i] = w * (0.98 / peak) if peak > 0.98 else w
    lens = [len(clips[i]) / sr if i in clips else None for i in range(len(lines))]
    starts = place_clips(lines, lens)
    track = np.zeros(int((total + 5) * sr), dtype=np.float32)
    placed = {}
    for i, s in enumerate(starts):
        if s is None:
            continue
        a = int(s * sr)
        w = clips[i][: max(0, len(track) - a)]
        track[a:a + len(w)] += w
        placed[i] = (s, s + len(w) / sr)
    return np.clip(track, -1.0, 1.0), sr, placed


# ---------------------------------------------------------------------------- 6. mix

LIMIT = "alimiter=limit=0.89:attack=5:release=60:level=0:latency=1"


def stage_mix(job, d, wav_in, background, vocals, track, sr, lines, placed, total):
    set_status(job, stage="mix", progress=94, message="دمج الأصوات العربية مع الموسيقى والمؤثرات…")
    voices = os.path.join(d, "dub_voices.wav")
    write_wav(voices, track, sr)
    out = os.path.join(d, "dubbed.m4a")
    v = "aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo,highpass=f=70"
    if background:
        resid = make_residual(vocals, os.path.join(d, "residual.wav"),
                              [(lines[i]["start"], lines[i]["end"]) for i in placed], total)
        inputs = ["-i", background, "-i", resid, "-i", voices]
        filt = ("[0:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo[bg];"
                "[1:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo[rv];"
                f"[2:a]{v}[v];[bg][rv][v]amix=inputs=3:duration=first:normalize=0,{LIMIT}[out]")
    else:
        inputs = ["-i", wav_in, "-i", voices]
        filt = (f"[1:a]{v},asplit=2[vk][vm];"
                "[0:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo[o];"
                "[o][vk]sidechaincompress=threshold=0.04:ratio=4:attack=20:release=500[duck];"
                f"[duck][vm]amix=inputs=2:duration=first:normalize=0,{LIMIT}[out]")
    sh(["ffmpeg", "-y", "-loglevel", "error"] + inputs + ["-filter_complex", filt, "-map", "[out]",
                                                         "-c:a", "aac", "-b:a", "192k", out])
    return out


def write_srt(d, lines, placed):
    def ts(x):
        ms = int(max(0.0, x) * 1000)
        return "%02d:%02d:%02d,%03d" % (ms // 3600000, ms // 60000 % 60, ms // 1000 % 60, ms % 1000)
    path = os.path.join(d, "ar.srt")
    with open(path, "w", encoding="utf-8") as f:
        n = 1
        for i, L in enumerate(lines):
            if L.get("tts"):
                a, b = placed.get(i, (L["start"], L["end"]))
                f.write(f"{n}\n{ts(a)} --> {ts(b)}\n{L['ar']}\n\n")
                n += 1
    return path


# ---------------------------------------------------------------------------- job

def process(job, opts):
    d = os.path.join(ROOT, job)
    t0 = time.time()
    try:
        src = os.path.join(d, "input")
        with open(src, "wb") as out:
            for i in range(opts["parts"]):
                with open(os.path.join(d, f"part_{i}"), "rb") as p:
                    shutil.copyfileobj(p, out)
        set_status(job, stage="prepare", progress=2, message="تجهيز الصوت…")
        wav = os.path.join(d, "input.wav")
        sh(["ffmpeg", "-y", "-loglevel", "error", "-i", src, "-vn", "-ac", "2", "-ar", "44100", wav])
        total = float(sh(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", wav]).strip() or 0)

        vocals, background, sep_used = stage_separate(job, wav, d, total)
        voc16_y, _ = read_wav(ff_mono(vocals, os.path.join(d, "voc16.wav"), 16000))
        pieces, lang, asr_used = stage_transcribe(job, voc16_y, opts.get("lang") or None, total)
        if not pieces:
            raise RuntimeError("لم يُعثر على كلام في هذا الفيديو")
        n_spk, spk_used = stage_speakers(job, voc16_y, pieces, opts.get("speakers", 0))
        del voc16_y
        lines = merge_lines(pieces)
        mt_used = stage_translate(job, lines, lang)
        track, sr, placed = stage_voices(job, d, lines, pieces, vocals, total)
        audio = stage_mix(job, d, wav, background, vocals, track, sr, lines, placed, total)
        sub = write_srt(d, lines, placed)
        engines = {"separation": sep_used, "asr": asr_used, "speakers": spk_used, "translation": mt_used,
                   "voices": "OmniVoice" if not FAKE else "fake"}
        log(f"[{job}] انتهت خلال {time.time() - t0:.0f} ثانية • {engines}")
        set_status(job, stage="done", progress=100, done=True, audio=audio, srt=sub, lang=lang, engines=engines,
                   lines=len(placed), speakers=n_spk,
                   message=f"اكتملت الدبلجة • الجمل: {len(placed)} • الأصوات: {n_spk}")
    except Exception as e:
        traceback.print_exc()
        set_status(job, stage="failed", error=str(e)[:500], message="فشلت الدبلجة")
    finally:
        keep = {"dubbed.m4a", "ar.srt"}
        try:
            for name in os.listdir(d):
                if name not in keep:
                    path = os.path.join(d, name)
                    shutil.rmtree(path, ignore_errors=True) if os.path.isdir(path) else os.remove(path)
        except Exception:
            pass
        free_gpu()


def worker():
    while True:
        job, opts = WORK.get()
        try:
            process(job, opts)
        except Exception:
            traceback.print_exc()


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
            return self._json(200, {"ok": True, "gpu": gpu, "version": VERSION})
        job = self._job(q)
        if job is None:
            return
        if path == "/status":
            with LOCK:
                st = dict(JOBS.get(job) or {"stage": "unknown", "error": "لا توجد مهمة بهذا الرقم", "done": False})
            st.pop("audio", None)
            st.pop("srt", None)
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
                    end = min(size - 1, int(m.group(2))) if m.group(2) else size - 1
                self.send_response(206)
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            else:
                self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(max(0, end - start + 1)))
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
                WORK.put((job, {"parts": parts, "lang": q.get("lang", ""),
                                "speakers": int(q.get("speakers", "0") or 0)}))
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
        threading.Thread(target=warm_up, daemon=True).start()
    log(f"الخادم يعمل (الإصدار {VERSION})")
    ThreadingHTTPServer(("0.0.0.0", a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
