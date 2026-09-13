/*
 * Minimal ALSA PCM declarations for the cinterop of this library.
 *
 * The Linux audio stack is accessed through libasound. Distributions do not
 * agree on where the development headers live (and a cross compiled klib cannot
 * depend on them), so the handful of declarations this library needs are
 * repeated here: opaque handle types, the frame types and a table of function
 * pointers that is filled with dlsym at runtime. The enumerator values below
 * are the stable ABI values of ALSA's snd_pcm_format_t / snd_pcm_access_t /
 * snd_pcm_stream_t enums, which cannot change without breaking every existing
 * binary.
 */
#ifndef AUDIO_IO_ALSA_MIN_H
#define AUDIO_IO_ALSA_MIN_H

typedef struct _snd_pcm snd_pcm_t;
typedef struct _snd_pcm_hw_params snd_pcm_hw_params_t;

typedef unsigned long snd_pcm_uframes_t;
typedef long snd_pcm_sframes_t;

/* snd_pcm_stream_t */
#define AUDIO_IO_ALSA_STREAM_PLAYBACK 0
#define AUDIO_IO_ALSA_STREAM_CAPTURE 1

/* snd_pcm_access_t */
#define AUDIO_IO_ALSA_ACCESS_RW_INTERLEAVED 3

/* snd_pcm_format_t (little endian members only, the only ones this library uses) */
#define AUDIO_IO_ALSA_FORMAT_U8 1
#define AUDIO_IO_ALSA_FORMAT_S16_LE 2
#define AUDIO_IO_ALSA_FORMAT_S32_LE 10
#define AUDIO_IO_ALSA_FORMAT_FLOAT_LE 14
#define AUDIO_IO_ALSA_FORMAT_FLOAT64_LE 16
#define AUDIO_IO_ALSA_FORMAT_S24_3LE 32

/* A NULL terminated array of device hint pointers, as returned by
 * snd_device_name_hint. */
typedef void *audio_io_alsa_hints;

/*
 * The libasound entry points this library calls. Every member is resolved with
 * dlsym right after the library is loaded; a member stays NULL when the running
 * libasound does not export it.
 */
typedef struct audio_io_alsa_api {
    int (*pcm_open)(snd_pcm_t **pcm, const char *name, int stream, int mode);
    int (*pcm_close)(snd_pcm_t *pcm);
    int (*pcm_prepare)(snd_pcm_t *pcm);
    int (*pcm_start)(snd_pcm_t *pcm);
    int (*pcm_drop)(snd_pcm_t *pcm);
    int (*pcm_drain)(snd_pcm_t *pcm);
    int (*pcm_resume)(snd_pcm_t *pcm);
    int (*pcm_recover)(snd_pcm_t *pcm, int err, int silent);
    snd_pcm_sframes_t (*pcm_readi)(snd_pcm_t *pcm, void *buffer, snd_pcm_uframes_t size);
    snd_pcm_sframes_t (*pcm_writei)(snd_pcm_t *pcm, const void *buffer, snd_pcm_uframes_t size);
    snd_pcm_sframes_t (*pcm_avail_update)(snd_pcm_t *pcm);

    int (*hw_params_malloc)(snd_pcm_hw_params_t **ptr);
    void (*hw_params_free)(snd_pcm_hw_params_t *obj);
    int (*hw_params_any)(snd_pcm_t *pcm, snd_pcm_hw_params_t *params);
    int (*hw_params_set_access)(snd_pcm_t *pcm, snd_pcm_hw_params_t *params, int access);
    int (*hw_params_set_format)(snd_pcm_t *pcm, snd_pcm_hw_params_t *params, int format);
    int (*hw_params_set_channels)(snd_pcm_t *pcm, snd_pcm_hw_params_t *params, unsigned int channels);
    int (*hw_params_set_rate)(snd_pcm_t *pcm, snd_pcm_hw_params_t *params, unsigned int rate, int dir);
    int (*hw_params_set_period_size_near)(snd_pcm_t *pcm, snd_pcm_hw_params_t *params, snd_pcm_uframes_t *val, int *dir);
    int (*hw_params_set_buffer_size_near)(snd_pcm_t *pcm, snd_pcm_hw_params_t *params, snd_pcm_uframes_t *val);
    int (*hw_params)(snd_pcm_t *pcm, snd_pcm_hw_params_t *params);
    int (*hw_params_get_period_size)(const snd_pcm_hw_params_t *params, snd_pcm_uframes_t *frames, int *dir);
    int (*hw_params_get_buffer_size)(const snd_pcm_hw_params_t *params, snd_pcm_uframes_t *val);

    const char *(*strerror)(int errnum);

    int (*device_name_hint)(int card, const char *iface, audio_io_alsa_hints *hints);
    int (*device_name_free_hint)(audio_io_alsa_hints hints);
    char *(*device_name_get_hint)(const void *hint, const char *id);
} audio_io_alsa_api;

#endif
