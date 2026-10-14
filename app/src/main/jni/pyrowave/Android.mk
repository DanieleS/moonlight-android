# PyroWave codec (the C API plus the Granite subset it builds against), as the
# static library `pyrowave` for pyrowave-renderer. The sources are vendored in
# pyrowave/ by vendor-pyrowave.sh at the commit Vibepollo encodes with; see
# VENDOR.txt. Upstream builds with CMake, but nothing here needs it: the SPIR-V
# is precompiled in pyrowave/shaders/slangmosh*.hpp, so the build is a plain
# list of C++ files and the same configuration upstream's standalone build
# uses (GRANITE_SHIPPING, FP32 math with reduced-range storage).
#
# arm64-v8a only: PyroWave needs a Vulkan 1.3 GPU with subgroup size control,
# which no 32-bit Android device has. On other ABIs this file defines nothing.
#
# Vulkan is loaded at runtime through volk (dlopen of libvulkan.so), so nothing
# links libvulkan and the app keeps minSdk 21.

LOCAL_PATH := $(call my-dir)

ifeq ($(TARGET_ARCH_ABI),arm64-v8a)

PW_DIR := $(LOCAL_PATH)/pyrowave
GRANITE_DIR := $(PW_DIR)/Granite

include $(CLEAR_VARS)
LOCAL_MODULE := pyrowave

LOCAL_SRC_FILES := \
    pyrowave/pyrowave_c.cpp \
    pyrowave/pyrowave_common.cpp \
    pyrowave/pyrowave_decoder.cpp \
    pyrowave/pyrowave_encoder.cpp \
    pyrowave/Granite/video/scaler.cpp \
    pyrowave/Granite/third_party/volk/volk.c \
    pyrowave/Granite/math/aabb.cpp \
    pyrowave/Granite/math/frustum.cpp \
    pyrowave/Granite/math/interpolation.cpp \
    pyrowave/Granite/math/math.cpp \
    pyrowave/Granite/math/muglm/muglm.cpp \
    pyrowave/Granite/math/transforms.cpp \
    pyrowave/Granite/util/aligned_alloc.cpp \
    pyrowave/Granite/util/arena_allocator.cpp \
    pyrowave/Granite/util/cli_parser.cpp \
    pyrowave/Granite/util/dynamic_library.cpp \
    pyrowave/Granite/util/environment.cpp \
    pyrowave/Granite/util/logging.cpp \
    pyrowave/Granite/util/message_queue.cpp \
    pyrowave/Granite/util/slab_allocator.cpp \
    pyrowave/Granite/util/string_helpers.cpp \
    pyrowave/Granite/util/thread_id.cpp \
    pyrowave/Granite/util/thread_name.cpp \
    pyrowave/Granite/util/thread_priority.cpp \
    pyrowave/Granite/util/timeline_trace_file.cpp \
    pyrowave/Granite/util/timer.cpp \
    pyrowave/Granite/vulkan/breadcrumbs.cpp \
    pyrowave/Granite/vulkan/buffer.cpp \
    pyrowave/Granite/vulkan/buffer_pool.cpp \
    pyrowave/Granite/vulkan/command_buffer.cpp \
    pyrowave/Granite/vulkan/command_pool.cpp \
    pyrowave/Granite/vulkan/context.cpp \
    pyrowave/Granite/vulkan/cookie.cpp \
    pyrowave/Granite/vulkan/descriptor_set.cpp \
    pyrowave/Granite/vulkan/device.cpp \
    pyrowave/Granite/vulkan/event_manager.cpp \
    pyrowave/Granite/vulkan/fence.cpp \
    pyrowave/Granite/vulkan/fence_manager.cpp \
    pyrowave/Granite/vulkan/image.cpp \
    pyrowave/Granite/vulkan/indirect_layout.cpp \
    pyrowave/Granite/vulkan/memory_allocator.cpp \
    pyrowave/Granite/vulkan/pipeline_cache.cpp \
    pyrowave/Granite/vulkan/pipeline_event.cpp \
    pyrowave/Granite/vulkan/query_pool.cpp \
    pyrowave/Granite/vulkan/render_pass.cpp \
    pyrowave/Granite/vulkan/renderdoc_capture.cpp \
    pyrowave/Granite/vulkan/rtas.cpp \
    pyrowave/Granite/vulkan/sampler.cpp \
    pyrowave/Granite/vulkan/semaphore.cpp \
    pyrowave/Granite/vulkan/semaphore_manager.cpp \
    pyrowave/Granite/vulkan/shader.cpp \
    pyrowave/Granite/vulkan/texture/texture_format.cpp \
    pyrowave/Granite/vulkan/wsi.cpp \
    pyrowave/Granite/vulkan/wsi_pacer.cpp

LOCAL_C_INCLUDES := \
    $(PW_DIR) \
    $(PW_DIR)/shaders \
    $(GRANITE_DIR)/video \
    $(GRANITE_DIR)/vulkan \
    $(GRANITE_DIR)/util \
    $(GRANITE_DIR)/math \
    $(GRANITE_DIR)/application/global \
    $(GRANITE_DIR)/third_party/volk \
    $(GRANITE_DIR)/third_party/renderdoc \
    $(GRANITE_DIR)/third_party/khronos/vulkan-headers/include

# pyrowave-renderer includes <pyrowave.h> after <vulkan/vulkan.h>
LOCAL_EXPORT_C_INCLUDES := \
    $(PW_DIR) \
    $(GRANITE_DIR)/third_party/khronos/vulkan-headers/include

# Hidden visibility keeps volk's global vk* pointers and every Granite symbol
# out of libmoonlight-core.so's dynamic symbol table.
LOCAL_CFLAGS := -DGRANITE_SHIPPING -DGRANITE_RENDERDOC_CAPTURE -DPYROWAVE_PRECISION=1 \
    -fvisibility=hidden -Wno-missing-field-initializers -Wno-unused-parameter
LOCAL_CPPFLAGS := -std=c++14 -fvisibility-inlines-hidden
LOCAL_CPP_FEATURES := exceptions rtti
LOCAL_EXPORT_LDLIBS := -llog -ldl

LOCAL_BRANCH_PROTECTION := standard

include $(BUILD_STATIC_LIBRARY)

endif
