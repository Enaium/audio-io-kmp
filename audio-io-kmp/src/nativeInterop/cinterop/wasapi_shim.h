/*
 * Extra declarations for the WASAPI cinterop.
 *
 * `mmreg.h` hides `WAVEFORMATEXTENSIBLE` behind `GUID_DEFINED`, which the
 * cinterop preprocessor pass does not define, so the structure is declared here
 * with exactly the layout of the Windows headers (WAVEFORMATEX is packed, the
 * channel mask and the sub-format GUID follow it without padding).
 */
#ifndef AUDIO_IO_WASAPI_SHIM_H
#define AUDIO_IO_WASAPI_SHIM_H

#include <guiddef.h>
#include <mmreg.h>
#include <ksmedia.h>

#pragma pack(push, 1)
typedef struct audio_io_wave_format_extensible {
    WAVEFORMATEX format;
    union {
        WORD validBitsPerSample;
        WORD samplesPerBlock;
        WORD reserved;
    } samples;
    DWORD channelMask;
    GUID subFormat;
} audio_io_wave_format_extensible;
#pragma pack(pop)

#endif
