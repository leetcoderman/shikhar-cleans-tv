/*
 * JNI bridge between UxPlay's AirPlay protocol library (lib/) and the Android app.
 *
 * Replaces UxPlay's uxplay.cpp front end: instead of GStreamer renderers, decrypted
 * H.264/H.265 access units and AAC-ELD/ALAC/AAC frames are handed to Java, which decodes
 * them with MediaCodec. Structure follows uxplay.cpp (start_dnssd, start_raop_server,
 * the callbacks and the feedback/reset loop).
 *
 * Copyright (C) 2026 Gupta household. GPL-3.0-or-later, like UxPlay.
 */
#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <stdbool.h>
#include <stdatomic.h>

#include "raop.h"
#include "dnssd.h"
#include "stream.h"
#include "logger.h"

#define TAG "GuptaAirPlay"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define MISSED_FEEDBACK_LIMIT 15

static JavaVM *g_vm;
static jclass g_cls;
static jmethodID m_log, m_conn_count, m_client, m_video_format, m_video_data, m_video_reset,
        m_video_flush, m_audio_format, m_audio_data, m_audio_flush, m_volume, m_pin, m_mirror_running,
        m_session_reset;

static raop_t *raop;
static dnssd_t *dnssd;
static unsigned short raop_port;
static atomic_int open_connections;
static atomic_int missed_feedback;
static atomic_bool reset_requested;
static atomic_bool watchdog_running;
static pthread_t watchdog_thread;
static double current_volume = 0.0;
static pthread_key_t detach_key;

static void detach_thread(void *unused) {
    (void) unused;
    (*g_vm)->DetachCurrentThread(g_vm);
}

static JNIEnv *get_env(void) {
    JNIEnv *env = NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6) == JNI_OK) {
        return env;
    }
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) {
        return NULL;
    }
    pthread_setspecific(detach_key, env);  /* detach automatically when the native thread exits */
    return env;
}

static void check_exception(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

static jstring new_string(JNIEnv *env, const char *s) {
    return s ? (*env)->NewStringUTF(env, s) : NULL;
}

static jbyteArray new_bytes(JNIEnv *env, const unsigned char *data, int len) {
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (arr) (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *) data);
    return arr;
}

/* ---- UxPlay callbacks (see uxplay.cpp for the reference versions) ---- */

static void log_callback(void *cls, int level, const char *msg) {
    (void) cls;
    JNIEnv *env = get_env();
    if (!env) return;
    jstring s = new_string(env, msg);
    (*env)->CallStaticVoidMethod(env, g_cls, m_log, level, s);
    check_exception(env);
    (*env)->DeleteLocalRef(env, s);
}

static void notify_conn_count(void) {
    JNIEnv *env = get_env();
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, g_cls, m_conn_count, (jint) atomic_load(&open_connections));
    check_exception(env);
}

static void conn_init(void *cls) {
    (void) cls;
    atomic_fetch_add(&open_connections, 1);
    LOGI("open connections: %d", atomic_load(&open_connections));
    notify_conn_count();
}

static void conn_destroy(void *cls) {
    (void) cls;
    int n = atomic_fetch_sub(&open_connections, 1) - 1;
    LOGI("open connections: %d", n);
    if (n <= 0) {
        atomic_store(&open_connections, 0);
        atomic_store(&missed_feedback, 0);
    }
    notify_conn_count();
}

static void conn_feedback(void *cls) {
    (void) cls;
    atomic_store(&missed_feedback, 0);  /* client heartbeat: connection still exists */
}

static void conn_reset(void *cls, int reason) {
    (void) cls;
    LOGI("conn_reset reason=%d", reason);
    atomic_store(&reset_requested, true);
}

static void report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    (void) cls;
    LOGI("connection request from %s (%s) deviceID=%s", name ? name : "?", model ? model : "?",
         deviceid ? deviceid : "?");
    *admit = true;
    JNIEnv *env = get_env();
    if (!env) return;
    jstring jn = new_string(env, name), jm = new_string(env, model), jd = new_string(env, deviceid);
    (*env)->CallStaticVoidMethod(env, g_cls, m_client, jn, jm, jd);
    check_exception(env);
    if (jn) (*env)->DeleteLocalRef(env, jn);
    if (jm) (*env)->DeleteLocalRef(env, jm);
    if (jd) (*env)->DeleteLocalRef(env, jd);
}

static int video_set_codec_and_size(void *cls, video_codec_t codec, unsigned short width_source,
                                    unsigned short height_source, unsigned short x_offset,
                                    unsigned short y_offset, unsigned short width, unsigned short height) {
    (void) cls; (void) x_offset; (void) y_offset;
    JNIEnv *env = get_env();
    if (!env) return 0;
    (*env)->CallStaticVoidMethod(env, g_cls, m_video_format, (jboolean) (codec == VIDEO_CODEC_H265),
                                 (jint) width_source, (jint) height_source, (jint) width, (jint) height);
    check_exception(env);
    return 0;
}

static void video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    (void) cls; (void) ntp;
    if (!data || data->data_len <= 0) return;
    JNIEnv *env = get_env();
    if (!env) return;
    jbyteArray arr = new_bytes(env, data->data, data->data_len);
    if (!arr) { check_exception(env); return; }
    (*env)->CallStaticVoidMethod(env, g_cls, m_video_data, arr, (jboolean) data->is_h265,
                                 (jlong) data->ntp_time_remote);
    check_exception(env);
    (*env)->DeleteLocalRef(env, arr);
}

static void video_pause(void *cls) { (void) cls; }
static void video_resume(void *cls) { (void) cls; }

static void video_flush(void *cls) {
    (void) cls;
    JNIEnv *env = get_env();
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, g_cls, m_video_flush);
    check_exception(env);
}

static void video_reset(void *cls, reset_type_t type) {
    (void) cls;
    LOGI("video_reset type=%d", (int) type);
    JNIEnv *env = get_env();
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, g_cls, m_video_reset, (jint) type);
    check_exception(env);
}

static void mirror_video_running(void *cls, bool is_running) {
    (void) cls;
    LOGI("mirror video running=%d", is_running);
    JNIEnv *env = get_env();
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, g_cls, m_mirror_running, (jboolean) is_running);
    check_exception(env);
}

static void audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *usingScreen,
                             bool *isMedia, uint64_t *audioFormat) {
    (void) cls;
    LOGI("audio format ct=%d spf=%d usingScreen=%d isMedia=%d audioFormat=0x%llx", *ct, *spf,
         *usingScreen, *isMedia, (unsigned long long) *audioFormat);
    JNIEnv *env = get_env();
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, g_cls, m_audio_format, (jint) *ct, (jint) *spf);
    check_exception(env);
}

static void audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    (void) cls; (void) ntp;
    if (!data || data->data_len <= 0) return;
    JNIEnv *env = get_env();
    if (!env) return;
    jbyteArray arr = new_bytes(env, data->data, data->data_len);
    if (!arr) { check_exception(env); return; }
    (*env)->CallStaticVoidMethod(env, g_cls, m_audio_data, arr, (jint) data->ct);
    check_exception(env);
    (*env)->DeleteLocalRef(env, arr);
}

static void audio_flush(void *cls) {
    (void) cls;
    JNIEnv *env = get_env();
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, g_cls, m_audio_flush);
    check_exception(env);
}

static double audio_set_client_volume(void *cls) {
    (void) cls;
    return current_volume;
}

static void audio_set_volume(void *cls, float volume) {
    (void) cls;
    current_volume = volume;
    JNIEnv *env = get_env();
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, g_cls, m_volume, (jfloat) volume);
    check_exception(env);
}

static void audio_set_metadata(void *cls, const void *buffer, int buflen) { (void) cls; (void) buffer; (void) buflen; }
static void audio_set_coverart(void *cls, const void *buffer, int buflen) { (void) cls; (void) buffer; (void) buflen; }
static void audio_stop_coverart_rendering(void *cls) { (void) cls; }
static void audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end) {
    (void) cls; (void) start; (void) curr; (void) end;
}
static void export_dacp(void *cls, const char *active_remote, const char *dacp_id) {
    (void) cls; (void) active_remote; (void) dacp_id;
}

static void display_pin(void *cls, char *pin) {
    (void) cls;
    LOGI("display PIN %s", pin);
    JNIEnv *env = get_env();
    if (!env) return;
    jstring s = new_string(env, pin);
    (*env)->CallStaticVoidMethod(env, g_cls, m_pin, s);
    check_exception(env);
    if (s) (*env)->DeleteLocalRef(env, s);
}

static void register_client(void *cls, const char *device_id, const char *pk_str, const char *name) {
    (void) cls; (void) pk_str;
    LOGI("client paired: %s (%s)", name ? name : "?", device_id ? device_id : "?");
}

static bool check_register(void *cls, const char *pk_str) {
    (void) cls; (void) pk_str;
    return true;  /* no registration list is kept */
}

/* HLS (video URL casting) is not advertised; these only satisfy the callback table. */
static void on_video_play(void *cls, const char *location, const float start_position) {
    (void) cls; (void) location; (void) start_position;
}
static void on_video_scrub(void *cls, const float position) { (void) cls; (void) position; }
static void on_video_rate(void *cls, const float rate) { (void) cls; (void) rate; }
static void on_video_stop(void *cls) { (void) cls; }
static void on_video_acquire_playback_info(void *cls, playback_info_t *info) { (void) cls; (void) info; }
static float on_video_playlist_remove(void *cls) { (void) cls; return 0.0f; }

/* ---- feedback watchdog and reset, like uxplay.cpp's feedback_callback/main loop ---- */

static void do_reset(void) {
    LOGI("resetting AirPlay session");
    raop_stop_httpd(raop);
    raop_remove_known_connections(raop);
    atomic_store(&open_connections, 0);
    atomic_store(&missed_feedback, 0);
    JNIEnv *env = get_env();
    if (env) {
        (*env)->CallStaticVoidMethod(env, g_cls, m_session_reset);
        check_exception(env);
    }
    unsigned short port = raop_port;
    raop_start_httpd(raop, &port);
    raop_set_port(raop, port);
    if (port != raop_port) LOGE("httpd restarted on a different port %u (was %u)", port, raop_port);
}

static void *watchdog(void *unused) {
    (void) unused;
    while (atomic_load(&watchdog_running)) {
        sleep(1);
        if (!atomic_load(&watchdog_running)) break;
        if (atomic_load(&open_connections) > 0) {
            int missed = atomic_fetch_add(&missed_feedback, 1) + 1;
            if (missed > MISSED_FEEDBACK_LIMIT) {
                LOGI("no client feedback for %d s: connection lost", missed);
                atomic_store(&reset_requested, true);
            }
        } else {
            atomic_store(&missed_feedback, 0);
        }
        if (atomic_exchange(&reset_requested, false)) {
            do_reset();
        }
    }
    return NULL;
}

/* ---- JNI entry points ---- */

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass local = (*env)->FindClass(env, "com/gupta/airplayreceiver/NativeReceiver");
    if (!local) return JNI_ERR;
    g_cls = (*env)->NewGlobalRef(env, local);
    m_log = (*env)->GetStaticMethodID(env, g_cls, "onLog", "(ILjava/lang/String;)V");
    m_conn_count = (*env)->GetStaticMethodID(env, g_cls, "onConnectionCount", "(I)V");
    m_client = (*env)->GetStaticMethodID(env, g_cls, "onClientRequest",
                                         "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    m_video_format = (*env)->GetStaticMethodID(env, g_cls, "onVideoFormat", "(ZIIII)V");
    m_video_data = (*env)->GetStaticMethodID(env, g_cls, "onVideoData", "([BZJ)V");
    m_video_reset = (*env)->GetStaticMethodID(env, g_cls, "onVideoReset", "(I)V");
    m_video_flush = (*env)->GetStaticMethodID(env, g_cls, "onVideoFlush", "()V");
    m_audio_format = (*env)->GetStaticMethodID(env, g_cls, "onAudioFormat", "(II)V");
    m_audio_data = (*env)->GetStaticMethodID(env, g_cls, "onAudioData", "([BI)V");
    m_audio_flush = (*env)->GetStaticMethodID(env, g_cls, "onAudioFlush", "()V");
    m_volume = (*env)->GetStaticMethodID(env, g_cls, "onVolume", "(F)V");
    m_pin = (*env)->GetStaticMethodID(env, g_cls, "onPin", "(Ljava/lang/String;)V");
    m_mirror_running = (*env)->GetStaticMethodID(env, g_cls, "onMirrorRunning", "(Z)V");
    m_session_reset = (*env)->GetStaticMethodID(env, g_cls, "onSessionReset", "()V");
    if ((*env)->ExceptionCheck(env)) return JNI_ERR;
    pthread_key_create(&detach_key, detach_thread);
    return JNI_VERSION_1_6;
}

static char *dup_jstring(JNIEnv *env, jstring s) {
    if (!s) return NULL;
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    char *r = strdup(c);
    (*env)->ReleaseStringUTFChars(env, s, c);
    return r;
}

JNIEXPORT void JNICALL
Java_com_gupta_airplayreceiver_NativeReceiver_nativeStop(JNIEnv *env, jclass clazz);

JNIEXPORT jint JNICALL
Java_com_gupta_airplayreceiver_NativeReceiver_nativeStart(JNIEnv *env, jclass clazz, jstring jname,
        jbyteArray jhwaddr, jstring jdevice_id, jstring jkeyfile, jint width, jint height, jint fps,
        jint pin_mode, jint fixed_pin, jstring jhost_label, jstring jipv4, jboolean h265, jboolean debug) {
    (void) clazz;
    if (raop) return raop_port;

    char *name = dup_jstring(env, jname);
    char *device_id = dup_jstring(env, jdevice_id);
    char *keyfile = dup_jstring(env, jkeyfile);
    char *host_label = dup_jstring(env, jhost_label);
    char *ipv4 = dup_jstring(env, jipv4);
    jsize hwlen = (*env)->GetArrayLength(env, jhwaddr);
    char hwaddr[16];
    if (hwlen > (jsize) sizeof(hwaddr)) hwlen = sizeof(hwaddr);
    (*env)->GetByteArrayRegion(env, jhwaddr, 0, hwlen, (jbyte *) hwaddr);

    /* read by the patched lib/mdnsd (see README: "Changes to UxPlay") */
    if (host_label) setenv("UXPLAY_MDNS_HOSTNAME", host_label, 1);
    if (ipv4 && *ipv4) setenv("UXPLAY_MDNS_IPV4", ipv4, 1); else unsetenv("UXPLAY_MDNS_IPV4");

    int ret = 0;
    ntp_global_init();

    /* start_dnssd() */
    int dnssd_error = 0;
    dnssd = dnssd_init(name, strlen(name), hwaddr, hwlen, (unsigned char) pin_mode, &dnssd_error);
    if (dnssd_error || !dnssd) {
        LOGE("dnssd_init failed: %d", dnssd_error);
        ret = -1;
        goto out;
    }
    dnssd_set_airplay_features(dnssd, 0, 0);   /* no HLS video casting */
    dnssd_set_airplay_features(dnssd, 4, 0);
    dnssd_set_airplay_features(dnssd, 42, h265 ? 1 : 0);  /* screen multi-codec (H.265) */
    dnssd_set_airplay_features(dnssd, 27, 1);  /* legacy pairing: as UxPlay's default */

    /* start_raop_server() */
    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.conn_init = conn_init;
    cbs.conn_destroy = conn_destroy;
    cbs.conn_reset = conn_reset;
    cbs.conn_feedback = conn_feedback;
    cbs.audio_process = audio_process;
    cbs.video_process = video_process;
    cbs.audio_flush = audio_flush;
    cbs.video_flush = video_flush;
    cbs.video_pause = video_pause;
    cbs.video_resume = video_resume;
    cbs.audio_set_client_volume = audio_set_client_volume;
    cbs.audio_set_volume = audio_set_volume;
    cbs.audio_get_format = audio_get_format;
    cbs.audio_set_metadata = audio_set_metadata;
    cbs.audio_set_coverart = audio_set_coverart;
    cbs.audio_stop_coverart_rendering = audio_stop_coverart_rendering;
    cbs.audio_set_progress = audio_set_progress;
    cbs.report_client_request = report_client_request;
    cbs.display_pin = display_pin;
    cbs.register_client = register_client;
    cbs.check_register = check_register;
    cbs.export_dacp = export_dacp;
    cbs.video_reset = video_reset;
    cbs.video_set_codec_and_size = video_set_codec_and_size;
    cbs.mirror_video_running = mirror_video_running;
    cbs.on_video_play = on_video_play;
    cbs.on_video_scrub = on_video_scrub;
    cbs.on_video_rate = on_video_rate;
    cbs.on_video_stop = on_video_stop;
    cbs.on_video_playlist_remove = on_video_playlist_remove;
    cbs.on_video_acquire_playback_info = on_video_acquire_playback_info;

    raop = raop_init(&cbs);
    if (!raop) {
        LOGE("raop_init failed");
        ret = -2;
        goto out;
    }
    raop_set_log_callback(raop, log_callback, NULL);
    raop_set_log_level(raop, debug ? LOGGER_DEBUG : LOGGER_INFO);
    if (raop_init2(raop, 1, device_id, keyfile)) {
        LOGE("raop_init2 failed");
        free(raop);
        raop = NULL;
        ret = -3;
        goto out;
    }
    raop_set_plist(raop, "width", width);
    raop_set_plist(raop, "height", height);
    raop_set_plist(raop, "refreshRate", fps);
    raop_set_plist(raop, "maxFPS", fps);
    if (pin_mode == 1 && fixed_pin > 0) raop_set_plist(raop, "pin", fixed_pin + 10000);

    unsigned short tcp[3] = {0, 0, 0}, udp[3] = {0, 0, 0};  /* all ports chosen dynamically */
    raop_set_tcp_ports(raop, tcp);
    raop_set_udp_ports(raop, udp);
    raop_port = raop_get_port(raop);
    raop_start_httpd(raop, &raop_port);
    raop_set_port(raop, raop_port);
    raop_set_dnssd(raop, dnssd);

    /* register_dnssd() */
    if (dnssd_register_raop(dnssd, raop_port) || dnssd_register_airplay(dnssd, raop_port)) {
        LOGE("mDNS registration failed");
        ret = -4;
        goto out;
    }
    LOGI("AirPlay receiver \"%s\" on port %u, features 0x%llX", name, raop_port,
         (unsigned long long) dnssd_get_airplay_features(dnssd));

    atomic_store(&watchdog_running, true);
    pthread_create(&watchdog_thread, NULL, watchdog, NULL);
    ret = raop_port;

out:
    free(name); free(device_id); free(keyfile); free(host_label); free(ipv4);
    if (ret < 0) Java_com_gupta_airplayreceiver_NativeReceiver_nativeStop(env, NULL);
    return ret;
}

JNIEXPORT void JNICALL
Java_com_gupta_airplayreceiver_NativeReceiver_nativeStop(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    if (atomic_exchange(&watchdog_running, false)) {
        pthread_join(watchdog_thread, NULL);
    }
    if (dnssd) {
        dnssd_unregister_raop(dnssd);
        dnssd_unregister_airplay(dnssd);
    }
    if (raop) {
        raop_destroy(raop);
        raop = NULL;
    }
    if (dnssd) {
        dnssd_destroy(dnssd);
        dnssd = NULL;
    }
    atomic_store(&open_connections, 0);
}

JNIEXPORT void JNICALL
Java_com_gupta_airplayreceiver_NativeReceiver_nativeDisconnect(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    if (raop) atomic_store(&reset_requested, true);
}
