/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 *
 * JNI glue for app.pictureshow.mpv.MpvNative. Each handle is a struct holding the mpv client and the global
 * reference to the Surface mpv draws on. Events are polled by a Kotlin thread through waitEvent, so libmpv never
 * calls into Java from its own threads.
 */
#include <jni.h>
#include <math.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <libavcodec/jni.h>
#include <libavcodec/avcodec.h>
#include <mpv/client.h>

typedef struct {
    mpv_handle *mpv;
    jobject surface;
} Player;

static JavaVM *java_vm;
static jclass event_class;
static jmethodID event_init;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    java_vm = vm;
    av_jni_set_java_vm(vm, NULL);
    jclass local = (*env)->FindClass(env, "app/pictureshow/mpv/MpvEvent");
    if (!local) return JNI_ERR;
    event_class = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    event_init = (*env)->GetMethodID(env, event_class, "<init>",
        "(IJILjava/lang/String;ILjava/lang/String;JDILjava/lang/String;)V");
    return event_init ? JNI_VERSION_1_6 : JNI_ERR;
}

static Player *player(jlong handle) { return (Player *)(intptr_t)handle; }

#define FN(name) Java_app_pictureshow_mpv_MpvNative_##name

JNIEXPORT jlong JNICALL FN(create)(JNIEnv *env, jobject self, jobject context) {
    (void)self;
#if LIBAVCODEC_VERSION_MAJOR >= 61
    jobject app = (*env)->NewGlobalRef(env, context);
    av_jni_set_android_app_ctx(app, NULL);
#else
    (void)context;
#endif
    Player *p = calloc(1, sizeof(Player));
    if (!p) return 0;
    p->mpv = mpv_create();
    if (!p->mpv) {
        free(p);
        return 0;
    }
    return (jlong)(intptr_t)p;
}

JNIEXPORT jint JNICALL FN(initialize)(JNIEnv *env, jobject self, jlong handle) {
    (void)env; (void)self;
    return mpv_initialize(player(handle)->mpv);
}

JNIEXPORT void JNICALL FN(destroy)(JNIEnv *env, jobject self, jlong handle) {
    (void)self;
    Player *p = player(handle);
    if (!p) return;
    mpv_terminate_destroy(p->mpv);
    if (p->surface) (*env)->DeleteGlobalRef(env, p->surface);
    free(p);
}

JNIEXPORT jint JNICALL FN(setOptionString)(JNIEnv *env, jobject self, jlong handle, jstring name, jstring value) {
    (void)self;
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    const char *v = (*env)->GetStringUTFChars(env, value, NULL);
    int result = mpv_set_option_string(player(handle)->mpv, n, v);
    (*env)->ReleaseStringUTFChars(env, name, n);
    (*env)->ReleaseStringUTFChars(env, value, v);
    return result;
}

/* Copies a Java String[] into a NULL-terminated array of UTF-8 strings; free with release_args. */
static const char **copy_args(JNIEnv *env, jobjectArray args, jsize *count) {
    *count = (*env)->GetArrayLength(env, args);
    const char **out = calloc((size_t)*count + 1, sizeof(char *));
    if (!out) return NULL;
    for (jsize i = 0; i < *count; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        const char *utf = s ? (*env)->GetStringUTFChars(env, s, NULL) : "";
        out[i] = strdup(utf ? utf : "");
        if (s) {
            (*env)->ReleaseStringUTFChars(env, s, utf);
            (*env)->DeleteLocalRef(env, s);
        }
    }
    return out;
}

static void release_args(const char **args, jsize count) {
    for (jsize i = 0; i < count; i++) free((void *)args[i]);
    free(args);
}

JNIEXPORT jint JNICALL FN(command)(JNIEnv *env, jobject self, jlong handle, jobjectArray args) {
    (void)self;
    jsize count;
    const char **a = copy_args(env, args, &count);
    if (!a) return MPV_ERROR_NOMEM;
    int result = mpv_command(player(handle)->mpv, a);
    release_args(a, count);
    return result;
}

JNIEXPORT jint JNICALL FN(commandAsync)(JNIEnv *env, jobject self, jlong handle, jlong reply, jobjectArray args) {
    (void)self;
    jsize count;
    const char **a = copy_args(env, args, &count);
    if (!a) return MPV_ERROR_NOMEM;
    int result = mpv_command_async(player(handle)->mpv, (uint64_t)reply, a);
    release_args(a, count);
    return result;
}

JNIEXPORT jstring JNICALL FN(getPropertyString)(JNIEnv *env, jobject self, jlong handle, jstring name) {
    (void)self;
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    char *value = mpv_get_property_string(player(handle)->mpv, n);
    (*env)->ReleaseStringUTFChars(env, name, n);
    if (!value) return NULL;
    jstring out = (*env)->NewStringUTF(env, value);
    mpv_free(value);
    return out;
}

JNIEXPORT jdouble JNICALL FN(getPropertyDouble)(JNIEnv *env, jobject self, jlong handle, jstring name) {
    (void)self;
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    double value = 0;
    int result = mpv_get_property(player(handle)->mpv, n, MPV_FORMAT_DOUBLE, &value);
    (*env)->ReleaseStringUTFChars(env, name, n);
    return result < 0 ? NAN : value;
}

JNIEXPORT jlong JNICALL FN(getPropertyLong)(JNIEnv *env, jobject self, jlong handle, jstring name) {
    (void)self;
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    int64_t value = 0;
    int result = mpv_get_property(player(handle)->mpv, n, MPV_FORMAT_INT64, &value);
    (*env)->ReleaseStringUTFChars(env, name, n);
    return result < 0 ? INT64_MIN : value;
}

JNIEXPORT jint JNICALL FN(getPropertyFlag)(JNIEnv *env, jobject self, jlong handle, jstring name) {
    (void)self;
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    int value = 0;
    int result = mpv_get_property(player(handle)->mpv, n, MPV_FORMAT_FLAG, &value);
    (*env)->ReleaseStringUTFChars(env, name, n);
    return result < 0 ? -1 : (value ? 1 : 0);
}

JNIEXPORT jint JNICALL FN(setPropertyString)(JNIEnv *env, jobject self, jlong handle, jstring name, jstring value) {
    (void)self;
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    const char *v = (*env)->GetStringUTFChars(env, value, NULL);
    int result = mpv_set_property_string(player(handle)->mpv, n, v);
    (*env)->ReleaseStringUTFChars(env, name, n);
    (*env)->ReleaseStringUTFChars(env, value, v);
    return result;
}

static int set_property(JNIEnv *env, jlong handle, jstring name, mpv_format format, void *value) {
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    int result = mpv_set_property(player(handle)->mpv, n, format, value);
    (*env)->ReleaseStringUTFChars(env, name, n);
    return result;
}

JNIEXPORT jint JNICALL FN(setPropertyDouble)(JNIEnv *env, jobject self, jlong handle, jstring name, jdouble value) {
    (void)self;
    double v = value;
    return set_property(env, handle, name, MPV_FORMAT_DOUBLE, &v);
}

JNIEXPORT jint JNICALL FN(setPropertyLong)(JNIEnv *env, jobject self, jlong handle, jstring name, jlong value) {
    (void)self;
    int64_t v = value;
    return set_property(env, handle, name, MPV_FORMAT_INT64, &v);
}

JNIEXPORT jint JNICALL FN(setPropertyFlag)(JNIEnv *env, jobject self, jlong handle, jstring name, jboolean value) {
    (void)self;
    int v = value ? 1 : 0;
    return set_property(env, handle, name, MPV_FORMAT_FLAG, &v);
}

JNIEXPORT jint JNICALL FN(observeProperty)(JNIEnv *env, jobject self, jlong handle, jlong reply, jstring name, jint format) {
    (void)self;
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    int result = mpv_observe_property(player(handle)->mpv, (uint64_t)reply, n, (mpv_format)format);
    (*env)->ReleaseStringUTFChars(env, name, n);
    return result;
}

JNIEXPORT jint JNICALL FN(requestLogMessages)(JNIEnv *env, jobject self, jlong handle, jstring level) {
    (void)self;
    const char *l = (*env)->GetStringUTFChars(env, level, NULL);
    int result = mpv_request_log_messages(player(handle)->mpv, l);
    (*env)->ReleaseStringUTFChars(env, level, l);
    return result;
}

JNIEXPORT jint JNICALL FN(attachSurface)(JNIEnv *env, jobject self, jlong handle, jobject surface) {
    (void)self;
    Player *p = player(handle);
    jobject next = (*env)->NewGlobalRef(env, surface);
    int64_t wid = (int64_t)(intptr_t)next;
    int result = mpv_set_option(p->mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (result < 0) {
        (*env)->DeleteGlobalRef(env, next);
        return result;
    }
    if (p->surface) (*env)->DeleteGlobalRef(env, p->surface);
    p->surface = next;
    return result;
}

JNIEXPORT jint JNICALL FN(detachSurface)(JNIEnv *env, jobject self, jlong handle) {
    (void)self;
    Player *p = player(handle);
    int64_t wid = 0;
    int result = mpv_set_option(p->mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (p->surface) {
        (*env)->DeleteGlobalRef(env, p->surface);
        p->surface = NULL;
    }
    return result;
}

static jstring utf(JNIEnv *env, const char *s) { return s ? (*env)->NewStringUTF(env, s) : NULL; }

JNIEXPORT jobject JNICALL FN(waitEvent)(JNIEnv *env, jobject self, jlong handle, jdouble timeout) {
    (void)self;
    mpv_event *e = mpv_wait_event(player(handle)->mpv, timeout);
    if (!e || e->event_id == MPV_EVENT_NONE) return NULL;
    jstring name = NULL, string_value = NULL, text = NULL;
    jint format = MPV_FORMAT_NONE, end_reason = -1, error = e->error;
    jlong long_value = 0;
    jdouble double_value = NAN;
    if (e->event_id == MPV_EVENT_PROPERTY_CHANGE && e->data) {
        mpv_event_property *prop = e->data;
        name = utf(env, prop->name);
        format = prop->format;
        if (prop->data) {
            switch (prop->format) {
            case MPV_FORMAT_STRING:
                string_value = utf(env, *(char **)prop->data);
                break;
            case MPV_FORMAT_FLAG:
                long_value = *(int *)prop->data;
                break;
            case MPV_FORMAT_INT64:
                long_value = *(int64_t *)prop->data;
                double_value = (double)long_value;
                break;
            case MPV_FORMAT_DOUBLE:
                double_value = *(double *)prop->data;
                break;
            default:
                break;
            }
        } else {
            format = MPV_FORMAT_NONE;
        }
    } else if (e->event_id == MPV_EVENT_END_FILE && e->data) {
        mpv_event_end_file *end = e->data;
        end_reason = end->reason;
        error = end->error;
    } else if (e->event_id == MPV_EVENT_LOG_MESSAGE && e->data) {
        mpv_event_log_message *log = e->data;
        name = utf(env, log->level);
        size_t length = strlen(log->prefix) + strlen(log->text) + 3;
        char *line = malloc(length);
        if (line) {
            snprintf(line, length, "%s: %s", log->prefix, log->text);
            text = utf(env, line);
            free(line);
        }
    }
    jobject event = (*env)->NewObject(env, event_class, event_init, (jint)e->event_id, (jlong)e->reply_userdata, error,
        name, format, string_value, long_value, double_value, end_reason, text);
    if (name) (*env)->DeleteLocalRef(env, name);
    if (string_value) (*env)->DeleteLocalRef(env, string_value);
    if (text) (*env)->DeleteLocalRef(env, text);
    return event;
}

JNIEXPORT void JNICALL FN(wakeup)(JNIEnv *env, jobject self, jlong handle) {
    (void)env; (void)self;
    mpv_wakeup(player(handle)->mpv);
}

JNIEXPORT jstring JNICALL FN(buildInfo)(JNIEnv *env, jobject self) {
    (void)self;
    unsigned long api = mpv_client_api_version();
    const char *license = avcodec_license();
    const char *config = avcodec_configuration();
    size_t length = strlen(license) + strlen(config) + 128;
    char *text = malloc(length);
    if (!text) return NULL;
    snprintf(text, length, "mpv-api=%lu.%lu\nffmpeg-license=%s\nffmpeg-configuration=%s", api >> 16, api & 0xffff, license, config);
    jstring out = (*env)->NewStringUTF(env, text);
    free(text);
    return out;
}
