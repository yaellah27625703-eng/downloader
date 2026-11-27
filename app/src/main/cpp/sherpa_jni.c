// JNI bridge from the app to sherpa-onnx (C API): source separation (voices / music + effects)
// and speaker fingerprints, both running on the phone's CPU.
#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "sherpa-onnx/c-api/c-api.h"

#define FN(name) Java_com_vadila_downloader_Sherpa_##name

static const char *str(JNIEnv *env, jstring s) {
    return s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
}

static void unstr(JNIEnv *env, jstring s, const char *c) {
    if (s && c) (*env)->ReleaseStringUTFChars(env, s, c);
}

// ------------------------------------------------------------------ source separation

JNIEXPORT jlong JNICALL FN(sepCreate)(JNIEnv *env, jclass cls, jstring jvocals, jstring jaccomp, jstring juvr,
                                      jint threads) {
    (void)cls;
    const char *v = str(env, jvocals), *a = str(env, jaccomp), *u = str(env, juvr);
    SherpaOnnxOfflineSourceSeparationConfig cfg;
    memset(&cfg, 0, sizeof(cfg));
    if (u && u[0]) {
        cfg.model.uvr.model = u;
    } else {
        cfg.model.spleeter.vocals = v;
        cfg.model.spleeter.accompaniment = a;
    }
    cfg.model.num_threads = threads > 0 ? threads : 2;
    cfg.model.provider = "cpu";
    const SherpaOnnxOfflineSourceSeparation *ss = SherpaOnnxCreateOfflineSourceSeparation(&cfg);
    unstr(env, jvocals, v);
    unstr(env, jaccomp, a);
    unstr(env, juvr, u);
    return (jlong)(intptr_t)ss;
}

JNIEXPORT void JNICALL FN(sepFree)(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    if (h) SherpaOnnxDestroyOfflineSourceSeparation((const SherpaOnnxOfflineSourceSeparation *)(intptr_t)h);
}

/*
 * Separates the first n samples of a stereo chunk. out = float[4][]: vocals L, vocals R, rest L, rest R
 * (each at least as long as the input). Returns the number of samples written per channel, or -1 on error.
 */
JNIEXPORT jint JNICALL FN(sepProcess)(JNIEnv *env, jclass cls, jlong h, jfloatArray jl, jfloatArray jr, jint jn,
                                      jint sr, jobjectArray jout) {
    (void)cls;
    if (!h) return -1;
    jsize n = (*env)->GetArrayLength(env, jl);
    if (jn > 0 && jn < n) n = jn;
    jfloat *l = (*env)->GetFloatArrayElements(env, jl, NULL);
    jfloat *r = (*env)->GetFloatArrayElements(env, jr, NULL);
    const float *chs[2] = {l, r};
    const SherpaOnnxSourceSeparationOutput *o = SherpaOnnxOfflineSourceSeparationProcess(
        (const SherpaOnnxOfflineSourceSeparation *)(intptr_t)h, chs, 2, n, sr);
    (*env)->ReleaseFloatArrayElements(env, jl, l, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, jr, r, JNI_ABORT);
    if (!o || o->num_stems < 2) {
        if (o) SherpaOnnxDestroySourceSeparationOutput(o);
        return -1;
    }
    jint m = o->stems[0].n;
    for (int s = 0; s < 2; ++s) {
        const SherpaOnnxSourceSeparationStem *st = &o->stems[s];
        for (int c = 0; c < 2; ++c) {
            jfloatArray dst = (jfloatArray)(*env)->GetObjectArrayElement(env, jout, s * 2 + c);
            jsize cap = (*env)->GetArrayLength(env, dst);
            jsize k = st->n < cap ? st->n : cap;
            if (k < m) m = k;
            const float *src = st->samples[st->num_channels > c ? c : 0];
            (*env)->SetFloatArrayRegion(env, dst, 0, k, src);
            (*env)->DeleteLocalRef(env, dst);
        }
    }
    SherpaOnnxDestroySourceSeparationOutput(o);
    return m;
}

// ------------------------------------------------------------------ speaker fingerprints

JNIEXPORT jlong JNICALL FN(embCreate)(JNIEnv *env, jclass cls, jstring jmodel, jint threads) {
    (void)cls;
    const char *m = str(env, jmodel);
    SherpaOnnxSpeakerEmbeddingExtractorConfig cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.model = m;
    cfg.num_threads = threads > 0 ? threads : 2;
    cfg.provider = "cpu";
    const SherpaOnnxSpeakerEmbeddingExtractor *ex = SherpaOnnxCreateSpeakerEmbeddingExtractor(&cfg);
    unstr(env, jmodel, m);
    return (jlong)(intptr_t)ex;
}

JNIEXPORT void JNICALL FN(embFree)(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    if (h) SherpaOnnxDestroySpeakerEmbeddingExtractor((const SherpaOnnxSpeakerEmbeddingExtractor *)(intptr_t)h);
}

/* Fingerprint of one 16 kHz mono clip, or null when the clip is too short. */
JNIEXPORT jfloatArray JNICALL FN(embCompute)(JNIEnv *env, jclass cls, jlong h, jfloatArray jx, jint sr) {
    (void)cls;
    if (!h) return NULL;
    const SherpaOnnxSpeakerEmbeddingExtractor *ex = (const SherpaOnnxSpeakerEmbeddingExtractor *)(intptr_t)h;
    jsize n = (*env)->GetArrayLength(env, jx);
    jfloat *x = (*env)->GetFloatArrayElements(env, jx, NULL);
    const SherpaOnnxOnlineStream *st = SherpaOnnxSpeakerEmbeddingExtractorCreateStream(ex);
    SherpaOnnxOnlineStreamAcceptWaveform(st, sr, x, n);
    SherpaOnnxOnlineStreamInputFinished(st);
    (*env)->ReleaseFloatArrayElements(env, jx, x, JNI_ABORT);
    jfloatArray ans = NULL;
    if (SherpaOnnxSpeakerEmbeddingExtractorIsReady(ex, st)) {
        const float *v = SherpaOnnxSpeakerEmbeddingExtractorComputeEmbedding(ex, st);
        if (v) {
            int dim = SherpaOnnxSpeakerEmbeddingExtractorDim(ex);
            ans = (*env)->NewFloatArray(env, dim);
            (*env)->SetFloatArrayRegion(env, ans, 0, dim, v);
            SherpaOnnxSpeakerEmbeddingExtractorDestroyEmbedding(v);
        }
    }
    SherpaOnnxDestroyOnlineStream(st);
    return ans;
}

// ------------------------------------------------------------------ man / woman / child (audio tagging)

JNIEXPORT jlong JNICALL FN(tagCreate)(JNIEnv *env, jclass cls, jstring jmodel, jstring jlabels, jint threads) {
    (void)cls;
    const char *m = str(env, jmodel), *l = str(env, jlabels);
    SherpaOnnxAudioTaggingConfig cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.model.ced = m;
    cfg.model.num_threads = threads > 0 ? threads : 2;
    cfg.model.provider = "cpu";
    cfg.labels = l;
    cfg.top_k = 5;
    const SherpaOnnxAudioTagging *t = SherpaOnnxCreateAudioTagging(&cfg);
    unstr(env, jmodel, m);
    unstr(env, jlabels, l);
    return (jlong)(intptr_t)t;
}

JNIEXPORT void JNICALL FN(tagFree)(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    if (h) SherpaOnnxDestroyAudioTagging((const SherpaOnnxAudioTagging *)(intptr_t)h);
}

/* AudioSet probabilities of [speech, man speaking, woman speaking, child speaking] for a 16 kHz clip. */
JNIEXPORT jfloatArray JNICALL FN(tagSpeech)(JNIEnv *env, jclass cls, jlong h, jfloatArray jx, jint sr) {
    (void)cls;
    if (!h) return NULL;
    const SherpaOnnxAudioTagging *t = (const SherpaOnnxAudioTagging *)(intptr_t)h;
    jsize n = (*env)->GetArrayLength(env, jx);
    jfloat *x = (*env)->GetFloatArrayElements(env, jx, NULL);
    const SherpaOnnxOfflineStream *st = SherpaOnnxAudioTaggingCreateOfflineStream(t);
    SherpaOnnxAcceptWaveformOffline(st, sr, x, n);
    (*env)->ReleaseFloatArrayElements(env, jx, x, JNI_ABORT);
    float out[4] = {0, 0, 0, 0};
    const SherpaOnnxAudioEvent *const *res = SherpaOnnxAudioTaggingCompute(t, st, 527);
    if (res) {
        for (int i = 0; res[i] != NULL; ++i) {
            int k = res[i]->index;
            if (k >= 0 && k < 4) out[k] = res[i]->prob;
        }
        SherpaOnnxAudioTaggingFreeResults(res);
    }
    SherpaOnnxDestroyOfflineStream(st);
    jfloatArray ans = (*env)->NewFloatArray(env, 4);
    (*env)->SetFloatArrayRegion(env, ans, 0, 4, out);
    return ans;
}

// ------------------------------------------------------------------ helpers

/*
 * Stereo → mono 16 kHz with an anti-aliasing low-pass, for samples [off, off + n) of the inputs
 * (the filter may read the neighbours outside that range). Returns the number of samples written.
 */
JNIEXPORT jint JNICALL FN(toMono16k)(JNIEnv *env, jclass cls, jfloatArray jl, jfloatArray jr, jint off, jint n,
                                     jint sr, jshortArray jout) {
    (void)cls;
    jsize len = (*env)->GetArrayLength(env, jl);
    jfloat *l = (*env)->GetFloatArrayElements(env, jl, NULL);
    jfloat *r = (*env)->GetFloatArrayElements(env, jr, NULL);
    jsize cap = (*env)->GetArrayLength(env, jout);
    jshort *o = (*env)->GetShortArrayElements(env, jout, NULL);
    // 31-tap windowed-sinc low-pass at 7.2 kHz, then linear interpolation
    enum { T = 31 };
    float taps[T];
    double fc = 7200.0 / sr, sum = 0;
    for (int i = 0; i < T; ++i) {
        int k = i - T / 2;
        double s = k == 0 ? 2 * fc : sin(2 * 3.141592653589793 * fc * k) / (3.141592653589793 * k);
        double w = 0.54 - 0.46 * cos(2 * 3.141592653589793 * i / (T - 1));
        taps[i] = (float)(s * w);
        sum += taps[i];
    }
    for (int i = 0; i < T; ++i) taps[i] = (float)(taps[i] / sum);
    double step = (double)sr / 16000.0;
    jint m = 0;
    for (double pos = 0; m < cap; pos += step) {
        int i0 = (int)pos;
        if (i0 + 1 >= n) break;
        float acc[2] = {0, 0};
        for (int t = 0; t < 2; ++t) {
            int c = off + i0 + t;
            float a = 0;
            for (int i = 0; i < T; ++i) {
                int j = c + i - T / 2;
                if (j < 0) j = 0;
                if (j >= len) j = len - 1;
                a += taps[i] * 0.5f * (l[j] + r[j]);
            }
            acc[t] = a;
        }
        double f = pos - i0;
        double v = (acc[0] + (acc[1] - acc[0]) * f) * 32767.0;
        if (v > 32767) v = 32767;
        if (v < -32768) v = -32768;
        o[m++] = (jshort)v;
    }
    (*env)->ReleaseFloatArrayElements(env, jl, l, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, jr, r, JNI_ABORT);
    (*env)->ReleaseShortArrayElements(env, jout, o, 0);
    return m;
}
