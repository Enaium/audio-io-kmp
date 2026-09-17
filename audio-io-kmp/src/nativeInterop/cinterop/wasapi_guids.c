/*
 * Definitions for the Core Audio ids that the MinGW sysroot of Kotlin/Native
 * does not carry.
 *
 * mmdeviceapi.h, audioclient.h and functiondiscoverykeys_devpkey.h declare the
 * class id, the interface ids and the property key with DEFINE_GUID, i.e. as
 * `extern const GUID` declarations; the definitions come from the Windows
 * import libraries (uuid.lib, mmdevapi.lib). The MinGW sysroot that
 * Kotlin/Native links against has neither - libuuid.a lists no Core Audio id
 * and there is no libmmdevapi.a - so the mingwX64 klib leaves those six
 * symbols undefined and a consumer link stops with
 *
 *   ld.lld: error: undefined symbol: CLSID_MMDeviceEnumerator
 *
 * Defining INITGUID before those headers is the usual way to get definitions,
 * but it also defines every other GUID they pull in (600 in this sysroot, 437
 * of which libuuid.a provides as well), and those collide. The six values
 * below are the ones the headers spell out; they are the Windows ABI and
 * cannot change.
 */
#include <guiddef.h>                        /* GUID, PROPERTYKEY */
#include <objbase.h>
#include <combaseapi.h>
#include <mmdeviceapi.h>                    /* CLSID_/IID_IMMDeviceEnumerator */
#include <audioclient.h>                    /* IID_IAudioClient and friends   */
#include <functiondiscoverykeys_devpkey.h>  /* PKEY_Device_FriendlyName       */

const GUID CLSID_MMDeviceEnumerator = {
    0xbcde0395, 0xe52f, 0x467c, {0x8e, 0x3d, 0xc4, 0x57, 0x92, 0x91, 0x69, 0x2e}};

const GUID IID_IMMDeviceEnumerator = {
    0xa95664d2, 0x9614, 0x4f35, {0xa7, 0x46, 0xde, 0x8d, 0xb6, 0x36, 0x17, 0xe6}};

const GUID IID_IAudioClient = {
    0x1cb9ad4c, 0xdbfa, 0x4c32, {0xb1, 0x78, 0xc2, 0xf5, 0x68, 0xa7, 0x03, 0xb2}};

const GUID IID_IAudioCaptureClient = {
    0xc8adbd64, 0xe71e, 0x48a0, {0xa4, 0xde, 0x18, 0x5c, 0x39, 0x5c, 0xd3, 0x17}};

const GUID IID_IAudioRenderClient = {
    0xf294acfc, 0x3146, 0x4483, {0xa7, 0xbf, 0xad, 0xdc, 0xa7, 0xc2, 0x60, 0xe2}};

const PROPERTYKEY PKEY_Device_FriendlyName = {
    {0xa45c254e, 0xdf1c, 0x4efd, {0x80, 0x20, 0x67, 0xd1, 0x46, 0xa8, 0x50, 0xe0}},
    14};
