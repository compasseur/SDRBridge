/*
 * Module:      rtlsdr_jni.c
 * Description: Thin JNI bridge between rfsource/RtlSdr.kt and librtlsdr.
 *              Only exposes the device: the TCP server and the command parsing
 *              stay in DriverService (Kotlin). Samples are pushed to Kotlin from
 *              the librtlsdr async callback as direct ByteBuffers (no copy here,
 *              the Kotlin side copies them into its own buffers).
 *
 * Copyright (C) 2024 Grégoire de Courtivron
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <unistd.h>
#include <android/log.h>

#include "rtl-sdr.h"
#include "rtl-sdr-android.h"

#define LOG_TAG "rtlsdr_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define JNI_FN(name) Java_com_compasseur_sdrbridge_rfsource_RtlSdr_##name
#define DEV(handle) ((rtlsdr_dev_t *) (intptr_t) (handle))

typedef struct {
    JavaVM *vm;
    jobject thiz; // global ref: the callback can run on any thread
    jmethodID on_samples;
} read_ctx_t;

static void throw_rf_source_exception(JNIEnv *env, const char *message) {
    jclass clazz = (*env)->FindClass(env, "com/compasseur/sdrbridge/rfsource/RfSourceException");
    if (clazz != NULL) (*env)->ThrowNew(env, clazz, message);
}

// Called by librtlsdr once per USB transfer. The buffer is reused by librtlsdr as soon
// as this returns.
// Usually runs on the thread of nativeReadAsync, but not always: a control transfer made
// by a command (e.g. nativeSetCenterFreq) also handles libusb events and can complete
// sample transfers on the command thread. So the JNIEnv of the current thread is used.
static void read_callback(unsigned char *buf, uint32_t len, void *ptr) {
    read_ctx_t *ctx = (read_ctx_t *) ptr;
    JNIEnv *env = NULL;
    if ((*ctx->vm)->GetEnv(ctx->vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return;

    jobject byte_buffer = (*env)->NewDirectByteBuffer(env, buf, (jlong) len);
    if (byte_buffer == NULL) {
        (*env)->ExceptionClear(env);
        return;
    }
    (*env)->CallVoidMethod(env, ctx->thiz, ctx->on_samples, byte_buffer, (jint) len);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    // The receive thread stays in native code for the whole session: free local refs now
    (*env)->DeleteLocalRef(env, byte_buffer);
}

// Opens the dongle from the fd of an Android UsbDeviceConnection. librtlsdr takes
// ownership of (and later closes) the fd it gets, so it is given a duplicate: the
// UsbDeviceConnection keeps owning the original one.
JNIEXPORT jlong JNICALL
JNI_FN(nativeOpen)(JNIEnv *env, jobject thiz, jint fd, jstring device_path) {
    int dup_fd = dup(fd);
    if (dup_fd < 0) {
        throw_rf_source_exception(env, "RTL-SDR: could not duplicate the USB file descriptor");
        return 0;
    }

    const char *path = (*env)->GetStringUTFChars(env, device_path, NULL);
    rtlsdr_dev_t *dev = NULL;
    int r = rtlsdr_open2(&dev, dup_fd, path);
    (*env)->ReleaseStringUTFChars(env, device_path, path);

    if (r < 0 || dev == NULL) {
        // rtlsdr_open2 has already closed dup_fd
        char message[96];
        snprintf(message, sizeof(message), "RTL-SDR: rtlsdr_open2 failed (%d)", r);
        throw_rf_source_exception(env, message);
        return 0;
    }
    LOGI("Opened RTL-SDR, tuner type %d", rtlsdr_get_tuner_type(dev));
    return (jlong) (intptr_t) dev;
}

JNIEXPORT void JNICALL
JNI_FN(nativeClose)(JNIEnv *env, jobject thiz, jlong handle) {
    rtlsdr_close(DEV(handle));
}

JNIEXPORT jint JNICALL
JNI_FN(nativeGetTunerType)(JNIEnv *env, jobject thiz, jlong handle) {
    return (jint) rtlsdr_get_tuner_type(DEV(handle));
}

// Supported tuner gains in tenths of dB
JNIEXPORT jintArray JNICALL
JNI_FN(nativeGetTunerGains)(JNIEnv *env, jobject thiz, jlong handle) {
    int count = rtlsdr_get_tuner_gains(DEV(handle), NULL);
    if (count < 0) count = 0;

    jintArray result = (*env)->NewIntArray(env, count);
    if (result == NULL || count == 0) return result;

    jint *gains = (*env)->GetIntArrayElements(env, result, NULL);
    rtlsdr_get_tuner_gains(DEV(handle), (int *) gains);
    (*env)->ReleaseIntArrayElements(env, result, gains, 0);
    return result;
}

JNIEXPORT jint JNICALL
JNI_FN(nativeSetCenterFreq)(JNIEnv *env, jobject thiz, jlong handle, jlong frequency) {
    return rtlsdr_set_center_freq(DEV(handle), (uint32_t) frequency);
}

JNIEXPORT jint JNICALL
JNI_FN(nativeSetSampleRate)(JNIEnv *env, jobject thiz, jlong handle, jint rate) {
    return rtlsdr_set_sample_rate(DEV(handle), (uint32_t) rate);
}

JNIEXPORT jint JNICALL
JNI_FN(nativeSetTunerGainMode)(JNIEnv *env, jobject thiz, jlong handle, jint manual) {
    return rtlsdr_set_tuner_gain_mode(DEV(handle), manual);
}

JNIEXPORT jint JNICALL
JNI_FN(nativeSetTunerGain)(JNIEnv *env, jobject thiz, jlong handle, jint gain) {
    return rtlsdr_set_tuner_gain(DEV(handle), gain);
}

JNIEXPORT jint JNICALL
JNI_FN(nativeSetTunerIfGain)(JNIEnv *env, jobject thiz, jlong handle, jint stage, jint gain) {
    return rtlsdr_set_tuner_if_gain(DEV(handle), stage, gain);
}

JNIEXPORT jint JNICALL
JNI_FN(nativeSetTunerBandwidth)(JNIEnv *env, jobject thiz, jlong handle, jint bandwidth) {
    return rtlsdr_set_tuner_bandwidth(DEV(handle), (uint32_t) bandwidth);
}

JNIEXPORT jint JNICALL
JNI_FN(nativeSetFreqCorrection)(JNIEnv *env, jobject thiz, jlong handle, jint ppm) {
    return rtlsdr_set_freq_correction(DEV(handle), ppm);
}

JNIEXPORT jint JNICALL
JNI_FN(nativeSetAgcMode)(JNIEnv *env, jobject thiz, jlong handle, jint on) {
    return rtlsdr_set_agc_mode(DEV(handle), on);
}

JNIEXPORT jint JNICALL
JNI_FN(nativeSetDirectSampling)(JNIEnv *env, jobject thiz, jlong handle, jint mode) {
    return rtlsdr_set_direct_sampling(DEV(handle), mode);
}

JNIEXPORT jint JNICALL
JNI_FN(nativeSetOffsetTuning)(JNIEnv *env, jobject thiz, jlong handle, jint on) {
    return rtlsdr_set_offset_tuning(DEV(handle), on);
}

JNIEXPORT jint JNICALL
JNI_FN(nativeSetTestMode)(JNIEnv *env, jobject thiz, jlong handle, jint on) {
    return rtlsdr_set_testmode(DEV(handle), on);
}

// 0 keeps the current value of either crystal
JNIEXPORT jint JNICALL
JNI_FN(nativeSetXtalFreq)(JNIEnv *env, jobject thiz, jlong handle, jint rtl_freq, jint tuner_freq) {
    return rtlsdr_set_xtal_freq(DEV(handle), (uint32_t) rtl_freq, (uint32_t) tuner_freq);
}

JNIEXPORT jint JNICALL
JNI_FN(nativeSetBiasTee)(JNIEnv *env, jobject thiz, jlong handle, jint on) {
    return rtlsdr_set_bias_tee(DEV(handle), on);
}

// Blocks until nativeCancelAsync is called or the device is lost. Every USB transfer
// of bufLen bytes is delivered to RtlSdr.onSamples(ByteBuffer, Int).
JNIEXPORT jint JNICALL
JNI_FN(nativeReadAsync)(JNIEnv *env, jobject thiz, jlong handle, jint buf_num, jint buf_len) {
    jclass clazz = (*env)->GetObjectClass(env, thiz);
    read_ctx_t ctx = {
            .vm = NULL,
            .thiz = NULL,
            .on_samples = (*env)->GetMethodID(env, clazz, "onSamples", "(Ljava/nio/ByteBuffer;I)V")
    };
    (*env)->DeleteLocalRef(env, clazz);
    if (ctx.on_samples == NULL || (*env)->GetJavaVM(env, &ctx.vm) != JNI_OK) return -1;
    ctx.thiz = (*env)->NewGlobalRef(env, thiz);

    rtlsdr_reset_buffer(DEV(handle));
    int r = rtlsdr_read_async(DEV(handle), read_callback, &ctx, (uint32_t) buf_num, (uint32_t) buf_len);
    LOGI("rtlsdr_read_async returned %d", r);
    (*env)->DeleteGlobalRef(env, ctx.thiz);
    return r;
}

JNIEXPORT jint JNICALL
JNI_FN(nativeCancelAsync)(JNIEnv *env, jobject thiz, jlong handle) {
    return rtlsdr_cancel_async(DEV(handle));
}

// Protection against the tuner losing part of its configuration during fast retuning, see
// RtlSdr.verifyTunerRegisters() and RtlSdr.recoverSignal()

// Retunes and checks the tuner registers in the same I2C gate opening. The retune result
// is returned in result[0], the check report (null when all registers match) as the value.
JNIEXPORT jstring JNICALL
JNI_FN(nativeSetCenterFreqAndVerify)(JNIEnv *env, jobject thiz, jlong handle, jlong frequency, jintArray result) {
    char report[512];
    jint r = rtlsdr_set_center_freq_and_verify(DEV(handle), (uint32_t) frequency, report, sizeof(report));
    (*env)->SetIntArrayRegion(env, result, 0, 1, &r);
    if (report[0] == 0) return NULL;
    return (*env)->NewStringUTF(env, report);
}

JNIEXPORT jint JNICALL
JNI_FN(nativeReinitTuner)(JNIEnv *env, jobject thiz, jlong handle) {
    return rtlsdr_reinit_tuner(DEV(handle));
}

// 0 when the last retune left the tuner PLL unlocked, 1 otherwise, see RtlSdr.setFrequency()
JNIEXPORT jint JNICALL
JNI_FN(nativeTunerLocked)(JNIEnv *env, jobject thiz, jlong handle) {
    return rtlsdr_tuner_locked(DEV(handle));
}
