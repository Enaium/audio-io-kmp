/*
 * Minimal AAudio declarations for the cinterop of this library.
 *
 * AAudio is the NDK audio API from Android 8.0 (API 26) on. The Kotlin/Native
 * Android sysroot ships the stub shared object but no headers, and the library
 * is loaded with dlopen at runtime (see androidNativeMain/AAudio.kt), so the
 * declarations this library needs are repeated here: the opaque handle types,
 * the enum values (which are part of AAudio's stable ABI) and the table of
 * function pointers that dlsym fills in.
 */
#ifndef AUDIO_IO_AAUDIO_MIN_H
#define AUDIO_IO_AAUDIO_MIN_H

#include <stdint.h>

typedef struct AAudioStreamBuilder AAudioStreamBuilder;
typedef struct AAudioStream AAudioStream;
typedef int32_t aaudio_result_t;

/* aaudio_direction_t */
#define AUDIO_IO_AAUDIO_DIRECTION_OUTPUT 0
#define AUDIO_IO_AAUDIO_DIRECTION_INPUT 1

/* aaudio_format_t */
#define AUDIO_IO_AAUDIO_FORMAT_INVALID (-1)
#define AUDIO_IO_AAUDIO_FORMAT_UNSPECIFIED 0
#define AUDIO_IO_AAUDIO_FORMAT_PCM_I16 1
#define AUDIO_IO_AAUDIO_FORMAT_PCM_FLOAT 2
#define AUDIO_IO_AAUDIO_FORMAT_PCM_I24_PACKED 3
#define AUDIO_IO_AAUDIO_FORMAT_PCM_I32 4

/* aaudio_sharing_mode_t */
#define AUDIO_IO_AAUDIO_SHARING_MODE_EXCLUSIVE 0
#define AUDIO_IO_AAUDIO_SHARING_MODE_SHARED 1

/* aaudio_performance_mode_t */
#define AUDIO_IO_AAUDIO_PERFORMANCE_MODE_NONE 10
#define AUDIO_IO_AAUDIO_PERFORMANCE_MODE_POWER_SAVING 11
#define AUDIO_IO_AAUDIO_PERFORMANCE_MODE_LOW_LATENCY 12

/* aaudio_stream_state_t */
#define AUDIO_IO_AAUDIO_STATE_UNINITIALIZED 0
#define AUDIO_IO_AAUDIO_STATE_UNKNOWN 1
#define AUDIO_IO_AAUDIO_STATE_OPEN 2
#define AUDIO_IO_AAUDIO_STATE_STARTING 3
#define AUDIO_IO_AAUDIO_STATE_STARTED 4
#define AUDIO_IO_AAUDIO_STATE_PAUSING 5
#define AUDIO_IO_AAUDIO_STATE_PAUSED 6
#define AUDIO_IO_AAUDIO_STATE_FLUSHING 7
#define AUDIO_IO_AAUDIO_STATE_FLUSHED 8
#define AUDIO_IO_AAUDIO_STATE_STOPPING 9
#define AUDIO_IO_AAUDIO_STATE_STOPPED 10
#define AUDIO_IO_AAUDIO_STATE_CLOSING 11
#define AUDIO_IO_AAUDIO_STATE_CLOSED 12
#define AUDIO_IO_AAUDIO_STATE_DISCONNECTED 13

/* AAUDIO_OK */
#define AUDIO_IO_AAUDIO_OK 0

/*
 * The libaaudio entry points this library calls. Every member is resolved with
 * dlsym right after the library is loaded; a member stays NULL when the running
 * libaaudio does not export it.
 */
typedef struct audio_io_aaudio_api {
    aaudio_result_t (*createStreamBuilder)(AAudioStreamBuilder **builder);
    void (*builderDelete)(AAudioStreamBuilder *builder);
    void (*builderSetDeviceId)(AAudioStreamBuilder *builder, int32_t deviceId);
    void (*builderSetDirection)(AAudioStreamBuilder *builder, int32_t direction);
    void (*builderSetSharingMode)(AAudioStreamBuilder *builder, int32_t sharingMode);
    void (*builderSetFormat)(AAudioStreamBuilder *builder, int32_t format);
    void (*builderSetChannelCount)(AAudioStreamBuilder *builder, int32_t channelCount);
    void (*builderSetSampleRate)(AAudioStreamBuilder *builder, int32_t sampleRate);
    void (*builderSetBufferCapacityInFrames)(AAudioStreamBuilder *builder, int32_t numFrames);
    void (*builderSetPerformanceMode)(AAudioStreamBuilder *builder, int32_t mode);
    aaudio_result_t (*builderOpenStream)(AAudioStreamBuilder *builder, AAudioStream **stream);

    aaudio_result_t (*streamClose)(AAudioStream *stream);
    aaudio_result_t (*streamRequestStart)(AAudioStream *stream);
    aaudio_result_t (*streamRequestStop)(AAudioStream *stream);
    aaudio_result_t (*streamRead)(AAudioStream *stream, void *buffer, int32_t numFrames, int64_t timeoutNanoseconds);
    aaudio_result_t (*streamWrite)(AAudioStream *stream, const void *buffer, int32_t numFrames, int64_t timeoutNanoseconds);
    aaudio_result_t (*streamSetBufferSizeInFrames)(AAudioStream *stream, int32_t numFrames);
    int32_t (*streamGetBufferSizeInFrames)(AAudioStream *stream);
    int32_t (*streamGetBufferCapacityInFrames)(AAudioStream *stream);
    int32_t (*streamGetSampleRate)(AAudioStream *stream);
    int32_t (*streamGetChannelCount)(AAudioStream *stream);
    int32_t (*streamGetDeviceId)(AAudioStream *stream);
    int32_t (*streamGetFormat)(AAudioStream *stream);
    int32_t (*streamGetState)(AAudioStream *stream);
    aaudio_result_t (*streamWaitForStateChange)(AAudioStream *stream, int32_t inputState, int32_t *nextState, int64_t timeoutNanoseconds);
    const char *(*convertResultToText)(aaudio_result_t result);
} audio_io_aaudio_api;

#endif
