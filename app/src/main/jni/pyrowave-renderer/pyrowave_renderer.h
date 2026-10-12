// PyroWave renderer for Ratatoskr (arm64-v8a only).
//
// PyroWave (docs/pyrowave-protocol.md in Vibepollo) is an intra-only GPU
// wavelet codec. MediaCodec cannot decode it, so this module
// decodes each frame with PyroWave on Vulkan and presents it in a Vulkan
// swapchain on the stream's Surface. Decode units reach it straight from
// callbacks.c, without crossing into Java. The JNI entry points live in
// simplejni.c (moonlight-core hides the symbols of its static libraries).
#pragma once

#include <Limelight.h>

#include <android/native_window.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Whether this device can decode PyroWave: a Vulkan 1.3 GPU on which a
// PyroWave decoder can be created. Probed once (creates a Vulkan device).
int PyroWaveRendererIsSupported(void);

// Starts a renderer presenting to window, which it takes ownership of (it is
// released even on failure). Returns 0 on success.
int PyroWaveRendererStart(ANativeWindow* window, int videoFormat, int width, int height,
                          int colorspace, int colorRange);

// Stops and destroys the renderer, if any.
void PyroWaveRendererStop(void);

// Whether a renderer is running; callbacks.c routes decode units to it then.
int PyroWaveRendererIsActive(void);

// Hands one frame to the renderer. Copies what it needs and returns at once:
// decoding happens on the renderer's own thread, and a frame still waiting
// when a newer one arrives is dropped (every PyroWave frame is independent).
int PyroWaveRendererSubmitDecodeUnit(PDECODE_UNIT decodeUnit);

#ifdef __cplusplus
}
#endif
