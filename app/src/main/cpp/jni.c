// Small JNI bridge between the app and whisper.cpp (speech-to-text with timestamps).
#include <jni.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include "whisper.h"

static atomic_int g_abort = 0;

typedef struct {
    JNIEnv *env;
    jobject cb;
    jmethodID mid;
} progress_data;

static void on_progress(struct whisper_context *ctx, struct whisper_state *state, int progress, void *user) {
    (void) ctx; (void) state;
    progress_data *d = (progress_data *) user;
    if (d && d->cb && d->mid) (*d->env)->CallVoidMethod(d->env, d->cb, d->mid, (jint) progress);
}

static bool on_abort(void *user) {
    (void) user;
    return atomic_load(&g_abort) != 0;
}

JNIEXPORT jlong JNICALL
Java_com_vadila_downloader_Whisper_initContext(JNIEnv *env, jobject thiz, jstring path) {
    (void) thiz;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = true;
    struct whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL
Java_com_vadila_downloader_Whisper_freeContext(JNIEnv *env, jobject thiz, jlong ptr) {
    (void) env; (void) thiz;
    if (ptr) whisper_free((struct whisper_context *) (intptr_t) ptr);
}

JNIEXPORT void JNICALL
Java_com_vadila_downloader_Whisper_setAbort(JNIEnv *env, jobject thiz, jboolean on) {
    (void) env; (void) thiz;
    atomic_store(&g_abort, on ? 1 : 0);
}

/** Returns the number of segments, or -1 on failure / abort. */
JNIEXPORT jint JNICALL
Java_com_vadila_downloader_Whisper_transcribe(JNIEnv *env, jobject thiz, jlong ptr, jfloatArray samples,
                                              jstring lang, jint threads, jobject cb) {
    (void) thiz;
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) ptr;
    if (!ctx) return -1;

    progress_data pd = { env, cb, NULL };
    if (cb) {
        jclass cls = (*env)->GetObjectClass(env, cb);
        pd.mid = (*env)->GetMethodID(env, cls, "onProgress", "(I)V");
    }

    const char *language = (*env)->GetStringUTFChars(env, lang, NULL);
    struct whisper_full_params fp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    fp.n_threads = threads;
    fp.translate = false;
    fp.no_context = false;
    fp.single_segment = false;
    fp.print_special = false;
    fp.print_progress = false;
    fp.print_realtime = false;
    fp.print_timestamps = false;
    fp.suppress_blank = true;
    fp.language = language;
    fp.detect_language = false;
    fp.progress_callback = on_progress;
    fp.progress_callback_user_data = &pd;
    fp.abort_callback = on_abort;
    fp.abort_callback_user_data = NULL;

    jsize n = (*env)->GetArrayLength(env, samples);
    jfloat *data = (*env)->GetFloatArrayElements(env, samples, NULL);
    int rc = whisper_full(ctx, fp, data, n);
    (*env)->ReleaseFloatArrayElements(env, samples, data, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, lang, language);

    if (rc != 0 || atomic_load(&g_abort)) return -1;
    return whisper_full_n_segments(ctx);
}

/** Segment text as raw UTF-8 bytes (decoded in Kotlin, safe for any character). */
JNIEXPORT jbyteArray JNICALL
Java_com_vadila_downloader_Whisper_segmentText(JNIEnv *env, jobject thiz, jlong ptr, jint i) {
    (void) thiz;
    const char *t = whisper_full_get_segment_text((struct whisper_context *) (intptr_t) ptr, i);
    jsize len = t ? (jsize) strlen(t) : 0;
    jbyteArray out = (*env)->NewByteArray(env, len);
    if (len > 0) (*env)->SetByteArrayRegion(env, out, 0, len, (const jbyte *) t);
    return out;
}

/** Start / end of a segment in milliseconds. */
JNIEXPORT jlong JNICALL
Java_com_vadila_downloader_Whisper_segmentT0(JNIEnv *env, jobject thiz, jlong ptr, jint i) {
    (void) env; (void) thiz;
    return (jlong) whisper_full_get_segment_t0((struct whisper_context *) (intptr_t) ptr, i) * 10;
}

JNIEXPORT jlong JNICALL
Java_com_vadila_downloader_Whisper_segmentT1(JNIEnv *env, jobject thiz, jlong ptr, jint i) {
    (void) env; (void) thiz;
    return (jlong) whisper_full_get_segment_t1((struct whisper_context *) (intptr_t) ptr, i) * 10;
}

JNIEXPORT jstring JNICALL
Java_com_vadila_downloader_Whisper_detectedLang(JNIEnv *env, jobject thiz, jlong ptr) {
    (void) thiz;
    int id = whisper_full_lang_id((struct whisper_context *) (intptr_t) ptr);
    const char *s = id >= 0 ? whisper_lang_str(id) : "";
    return (*env)->NewStringUTF(env, s ? s : "");
}
