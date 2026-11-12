/*
 * Force-included (-include) into librtlsdr: on Android stderr goes nowhere, so the
 * library's fprintf(stderr, ...) diagnostics (tuner detection, RTL-SDR Blog V4
 * detection, errors) are redirected to logcat under the "librtlsdr" tag.
 */
#ifndef SDRBRIDGE_ANDROID_STDERR_H
#define SDRBRIDGE_ANDROID_STDERR_H

#include <stdio.h>
#include <android/log.h>

#define fprintf(stream, ...) \
	((stream) == stderr \
		? __android_log_print(ANDROID_LOG_INFO, "librtlsdr", __VA_ARGS__) \
		: fprintf(stream, __VA_ARGS__))

#endif
