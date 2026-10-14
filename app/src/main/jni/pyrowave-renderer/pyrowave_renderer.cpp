// PyroWave renderer for Ratatoskr: decodes each frame with PyroWave on Vulkan
// and presents it in a swapchain on the stream's Surface.
//
// Ported from LavArtemis (Lavagnou/LavArtemis 34e3daa6, GPLv3), with the
// decode path and precision overrides of joemossjr16/artemis-android-pyrowave
// (2e6231a9). PyroWave picks the fragment decode path on Adreno itself.
//
// Frame path (render thread):
//   decode unit -> PyroWaveFraming::parse (record framing, partial frames)
//   -> pyrowave_decoder_push_packet -> pyrowave_decoder_decode_gpu_buffer into
//   three R8 planes (signals the decode timeline semaphore)
//   -> one render pass samples the planes, converts Y'CbCr to RGB into the
//   acquired swapchain image (waits on the decode, signals the render timeline)
//   -> vkQueuePresentKHR.
// The next decode waits for the render timeline before overwriting the planes,
// so the GPU orders everything and the CPU never waits on a decode.
//
// PyroWave and this renderer share one VkDevice and one queue: the renderer
// creates them (with the swapchain extension PyroWave does not need) and hands
// them to pyrowave_create_device().

#define VK_NO_PROTOTYPES
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#include <pyrowave.h>

#include "pyrowave_renderer.h"
#include "pyrowaveframing.h"
#include "shaders_spv.h"

#include <android/log.h>
#include <android/native_window.h>
#include <dlfcn.h>
#include <sys/system_properties.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#define LOG_TAG "PyroWave"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ---------------------------------------------------------------------------
// Vulkan entry points. libvulkan is opened at runtime so the app keeps its
// minSdk (Vulkan only exists from API 24, and 1.3 much later).
// ---------------------------------------------------------------------------

#define PW_VK_GLOBAL(X) \
    X(CreateInstance) \
    X(EnumerateInstanceExtensionProperties)

#define PW_VK_INSTANCE(X) \
    X(DestroyInstance) \
    X(EnumeratePhysicalDevices) \
    X(GetPhysicalDeviceProperties) \
    X(GetPhysicalDeviceFeatures2) \
    X(GetPhysicalDeviceQueueFamilyProperties) \
    X(GetPhysicalDeviceFormatProperties) \
    X(GetPhysicalDeviceMemoryProperties) \
    X(EnumerateDeviceExtensionProperties) \
    X(CreateDevice) \
    X(GetDeviceProcAddr)

// Only there when the instance enabled the surface extensions: the support probe's doesn't.
#define PW_VK_SURFACE(X) \
    X(CreateAndroidSurfaceKHR) \
    X(DestroySurfaceKHR) \
    X(GetPhysicalDeviceSurfaceSupportKHR) \
    X(GetPhysicalDeviceSurfaceCapabilitiesKHR) \
    X(GetPhysicalDeviceSurfaceFormatsKHR) \
    X(GetPhysicalDeviceSurfacePresentModesKHR)

#define PW_VK_DEVICE(X) \
    X(DestroyDevice) \
    X(GetDeviceQueue) \
    X(DeviceWaitIdle) \
    X(QueueSubmit) \
    X(QueueWaitIdle) \
    X(CreateImage) \
    X(DestroyImage) \
    X(GetImageMemoryRequirements) \
    X(AllocateMemory) \
    X(FreeMemory) \
    X(BindImageMemory) \
    X(CreateImageView) \
    X(DestroyImageView) \
    X(CreateSampler) \
    X(DestroySampler) \
    X(CreateRenderPass) \
    X(DestroyRenderPass) \
    X(CreateFramebuffer) \
    X(DestroyFramebuffer) \
    X(CreateShaderModule) \
    X(DestroyShaderModule) \
    X(CreatePipelineLayout) \
    X(DestroyPipelineLayout) \
    X(CreateGraphicsPipelines) \
    X(DestroyPipeline) \
    X(CreateDescriptorSetLayout) \
    X(DestroyDescriptorSetLayout) \
    X(CreateDescriptorPool) \
    X(DestroyDescriptorPool) \
    X(AllocateDescriptorSets) \
    X(UpdateDescriptorSets) \
    X(CreateCommandPool) \
    X(DestroyCommandPool) \
    X(AllocateCommandBuffers) \
    X(BeginCommandBuffer) \
    X(EndCommandBuffer) \
    X(ResetCommandBuffer) \
    X(CmdPipelineBarrier) \
    X(CmdBeginRenderPass) \
    X(CmdEndRenderPass) \
    X(CmdBindPipeline) \
    X(CmdBindDescriptorSets) \
    X(CmdPushConstants) \
    X(CmdSetViewport) \
    X(CmdSetScissor) \
    X(CmdDraw) \
    X(CreateSemaphore) \
    X(DestroySemaphore) \
    X(CreateFence) \
    X(DestroyFence) \
    X(WaitForFences) \
    X(ResetFences)

// Only there when the device enabled VK_KHR_swapchain, which the support probe's doesn't.
#define PW_VK_SWAPCHAIN(X) \
    X(CreateSwapchainKHR) \
    X(DestroySwapchainKHR) \
    X(GetSwapchainImagesKHR) \
    X(AcquireNextImageKHR) \
    X(QueuePresentKHR)

struct Vk {
    void* library = nullptr;
    PFN_vkGetInstanceProcAddr GetInstanceProcAddr = nullptr;
    PFN_vkEnumerateInstanceVersion EnumerateInstanceVersion = nullptr;
#define PW_DECLARE(name) PFN_vk##name name = nullptr;
    PW_VK_GLOBAL(PW_DECLARE)
    PW_VK_INSTANCE(PW_DECLARE)
    PW_VK_SURFACE(PW_DECLARE)
    PW_VK_DEVICE(PW_DECLARE)
    PW_VK_SWAPCHAIN(PW_DECLARE)
#undef PW_DECLARE

    bool loadGlobal()
    {
        library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        if (library == nullptr) {
            return false;
        }
        GetInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(library, "vkGetInstanceProcAddr"));
        if (GetInstanceProcAddr == nullptr) {
            return false;
        }
        EnumerateInstanceVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
            GetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
#define PW_LOAD(name) \
        name = reinterpret_cast<PFN_vk##name>(GetInstanceProcAddr(VK_NULL_HANDLE, "vk" #name)); \
        if (name == nullptr) return false;
        PW_VK_GLOBAL(PW_LOAD)
#undef PW_LOAD
        return true;
    }

    bool loadInstance(VkInstance instance, bool withSurface)
    {
#define PW_LOAD(name) \
        name = reinterpret_cast<PFN_vk##name>(GetInstanceProcAddr(instance, "vk" #name)); \
        if (name == nullptr) { LOGE("missing vk" #name); return false; }
        PW_VK_INSTANCE(PW_LOAD)
        if (withSurface) {
            PW_VK_SURFACE(PW_LOAD)
        }
#undef PW_LOAD
        return true;
    }

    bool loadDevice(VkDevice device, bool withSwapchain)
    {
#define PW_LOAD(name) \
        name = reinterpret_cast<PFN_vk##name>(GetDeviceProcAddr(device, "vk" #name)); \
        if (name == nullptr) { LOGE("missing vk" #name); return false; }
        PW_VK_DEVICE(PW_LOAD)
        if (withSwapchain) {
            PW_VK_SWAPCHAIN(PW_LOAD)
        }
#undef PW_LOAD
        return true;
    }

    ~Vk()
    {
        if (library != nullptr) {
            dlclose(library);
        }
    }
};

const char* resultString(pyrowave_result result)
{
    switch (result) {
    case PYROWAVE_SUCCESS: return "success";
    case PYROWAVE_TIMEOUT: return "timeout";
    case PYROWAVE_ERROR_INVALID_ARGUMENT: return "invalid argument";
    case PYROWAVE_ERROR_OUT_OF_HOST_MEMORY: return "out of host memory";
    case PYROWAVE_ERROR_OUT_OF_DEVICE_MEMORY: return "out of device memory";
    case PYROWAVE_ERROR_NO_VULKAN: return "no Vulkan";
    case PYROWAVE_ERROR_NOT_IMPLEMENTED: return "not implemented";
    default: return "error";
    }
}

bool hasExtension(const std::vector<VkExtensionProperties>& extensions, const char* name)
{
    return std::any_of(extensions.begin(), extensions.end(), [name](const VkExtensionProperties& e) {
        return strcmp(e.extensionName, name) == 0;
    });
}

// ---------------------------------------------------------------------------
// A Vulkan 1.3 instance and device that PyroWave shares. The create infos and
// feature structs stay alive with the context: pyrowave_create_device() keeps
// pointers to them for the lifetime of the pyrowave_device.
// ---------------------------------------------------------------------------
// How long a frame may wait for the previous one's GPU work (1 s)
constexpr uint64_t k_FenceTimeoutNs = 1000000000ull;

struct VulkanContext {
    Vk vk;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    std::string gpuName;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    pyrowave_device pwDevice = nullptr;
    std::mutex queueLock;

    VkApplicationInfo appInfo = {};
    std::vector<const char*> instanceExtensions;
    VkInstanceCreateInfo instanceInfo = {};

    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo = {};
    std::vector<const char*> deviceExtensions;
    VkPhysicalDeviceFeatures2 features2 = {};
    VkPhysicalDeviceVulkan11Features features11 = {};
    VkPhysicalDeviceVulkan12Features features12 = {};
    VkPhysicalDeviceVulkan13Features features13 = {};
    VkDeviceCreateInfo deviceInfo = {};
    pyrowave_device_create_queue_info pwQueueInfo = {};

    static void lockQueue(void* userdata)
    {
        static_cast<VulkanContext*>(userdata)->queueLock.lock();
    }

    static void unlockQueue(void* userdata)
    {
        static_cast<VulkanContext*>(userdata)->queueLock.unlock();
    }

    // forPresentation adds the surface and swapchain extensions.
    bool createInstance(bool forPresentation)
    {
        if (!vk.loadGlobal()) {
            LOGI("No Vulkan loader on this device");
            return false;
        }
        uint32_t apiVersion = VK_API_VERSION_1_0;
        if (vk.EnumerateInstanceVersion != nullptr) {
            vk.EnumerateInstanceVersion(&apiVersion);
        }
        if (apiVersion < VK_API_VERSION_1_3) {
            LOGI("Vulkan instance is %u.%u, PyroWave needs 1.3",
                 VK_API_VERSION_MAJOR(apiVersion), VK_API_VERSION_MINOR(apiVersion));
            return false;
        }

        uint32_t count = 0;
        vk.EnumerateInstanceExtensionProperties(nullptr, &count, nullptr);
        std::vector<VkExtensionProperties> available(count);
        vk.EnumerateInstanceExtensionProperties(nullptr, &count, available.data());
        if (forPresentation) {
            for (const char* name : {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME}) {
                if (!hasExtension(available, name)) {
                    LOGW("Missing instance extension %s", name);
                    return false;
                }
                instanceExtensions.push_back(name);
            }
        }

        appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        appInfo.pApplicationName = "Ratatoskr";
        appInfo.pEngineName = "Ratatoskr PyroWave";
        appInfo.apiVersion = VK_API_VERSION_1_3;

        instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        instanceInfo.pApplicationInfo = &appInfo;
        instanceInfo.enabledExtensionCount = uint32_t(instanceExtensions.size());
        instanceInfo.ppEnabledExtensionNames = instanceExtensions.data();

        if (vk.CreateInstance(&instanceInfo, nullptr, &instance) != VK_SUCCESS) {
            LOGW("vkCreateInstance failed");
            return false;
        }
        return vk.loadInstance(instance, forPresentation);
    }

    // Picks a Vulkan 1.3 GPU with a graphics+compute queue (that can present to
    // surface, when given) and creates the shared device on it.
    bool createDevice(VkSurfaceKHR surface)
    {
        uint32_t count = 0;
        vk.EnumeratePhysicalDevices(instance, &count, nullptr);
        std::vector<VkPhysicalDevice> gpus(count);
        vk.EnumeratePhysicalDevices(instance, &count, gpus.data());

        for (VkPhysicalDevice gpu : gpus) {
            VkPhysicalDeviceProperties props;
            vk.GetPhysicalDeviceProperties(gpu, &props);
            if (props.apiVersion < VK_API_VERSION_1_3) {
                LOGI("GPU %s is Vulkan %u.%u, PyroWave needs 1.3", props.deviceName,
                     VK_API_VERSION_MAJOR(props.apiVersion), VK_API_VERSION_MINOR(props.apiVersion));
                continue;
            }

            uint32_t extCount = 0;
            vk.EnumerateDeviceExtensionProperties(gpu, nullptr, &extCount, nullptr);
            std::vector<VkExtensionProperties> extensions(extCount);
            vk.EnumerateDeviceExtensionProperties(gpu, nullptr, &extCount, extensions.data());
            if (!hasExtension(extensions, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
                continue;
            }

            uint32_t familyCount = 0;
            vk.GetPhysicalDeviceQueueFamilyProperties(gpu, &familyCount, nullptr);
            std::vector<VkQueueFamilyProperties> families(familyCount);
            vk.GetPhysicalDeviceQueueFamilyProperties(gpu, &familyCount, families.data());
            for (uint32_t family = 0; family < familyCount; family++) {
                const VkQueueFlags wanted = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
                if ((families[family].queueFlags & wanted) != wanted) {
                    continue;
                }
                if (surface != VK_NULL_HANDLE) {
                    VkBool32 present = VK_FALSE;
                    vk.GetPhysicalDeviceSurfaceSupportKHR(gpu, family, surface, &present);
                    if (!present) {
                        continue;
                    }
                }
                physicalDevice = gpu;
                queueFamily = family;
                gpuName = props.deviceName;
                LOGI("Using %s (Vulkan %u.%u) for PyroWave", props.deviceName,
                     VK_API_VERSION_MAJOR(props.apiVersion), VK_API_VERSION_MINOR(props.apiVersion));
                break;
            }
            if (physicalDevice != VK_NULL_HANDLE) {
                break;
            }
        }
        if (physicalDevice == VK_NULL_HANDLE) {
            return false;
        }

        // Enable every core feature the GPU has (PyroWave probes the subgroup,
        // 8/16-bit and FP16 features it needs), except the robustness ones,
        // which only cost performance here.
        features13.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES;
        features12.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
        features12.pNext = &features13;
        features11.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
        features11.pNext = &features12;
        features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        features2.pNext = &features11;
        vk.GetPhysicalDeviceFeatures2(physicalDevice, &features2);
        features2.features.robustBufferAccess = VK_FALSE;
        features13.robustImageAccess = VK_FALSE;
        if (!features12.timelineSemaphore) {
            LOGW("GPU lacks timeline semaphores");
            return false;
        }

        queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        queueInfo.queueFamilyIndex = queueFamily;
        queueInfo.queueCount = 1;
        queueInfo.pQueuePriorities = &queuePriority;

        if (surface != VK_NULL_HANDLE) {
            deviceExtensions.push_back(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
        }

        deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        deviceInfo.pNext = &features2;
        deviceInfo.queueCreateInfoCount = 1;
        deviceInfo.pQueueCreateInfos = &queueInfo;
        deviceInfo.enabledExtensionCount = uint32_t(deviceExtensions.size());
        deviceInfo.ppEnabledExtensionNames = deviceExtensions.data();

        if (vk.CreateDevice(physicalDevice, &deviceInfo, nullptr, &device) != VK_SUCCESS) {
            LOGW("vkCreateDevice failed");
            return false;
        }
        if (!vk.loadDevice(device, surface != VK_NULL_HANDLE)) {
            return false;
        }
        vk.GetDeviceQueue(device, queueFamily, 0, &queue);
        return true;
    }

    bool createPyroWaveDevice()
    {
        pwQueueInfo.queue = queue;
        pwQueueInfo.familyIndex = queueFamily;
        pwQueueInfo.index = 0;

        pyrowave_device_create_info info = {};
        info.GetInstanceProcAddr = vk.GetInstanceProcAddr;
        info.instance = instance;
        info.physical_device = physicalDevice;
        info.device = device;
        info.instance_create_info = &instanceInfo;
        info.device_create_info = &deviceInfo;
        info.queue_info = &pwQueueInfo;
        info.queue_info_count = 1;
        info.queue_lock_callback = lockQueue;
        info.queue_unlock_callback = unlockQueue;
        info.userdata = this;

        const pyrowave_result result = pyrowave_create_device(&info, &pwDevice);
        if (result != PYROWAVE_SUCCESS) {
            LOGW("pyrowave_create_device failed: %s", resultString(result));
            pwDevice = nullptr;
            return false;
        }
        return true;
    }

    ~VulkanContext()
    {
        if (pwDevice != nullptr) {
            pyrowave_device_destroy(pwDevice);
        }
        if (device != VK_NULL_HANDLE) {
            vk.DeviceWaitIdle(device);
            vk.DestroyDevice(device, nullptr);
        }
        if (instance != VK_NULL_HANDLE) {
            vk.DestroyInstance(instance, nullptr);
        }
    }
};

// Whether R8_UNORM can be written from compute shaders. PyroWave's compute
// decoder needs it; its fragment decoder only needs a colour attachment,
// which R8_UNORM always supports.
bool r8StorageSupported(VulkanContext& ctx)
{
    VkFormatProperties props;
    ctx.vk.GetPhysicalDeviceFormatProperties(ctx.physicalDevice, VK_FORMAT_R8_UNORM, &props);
    return (props.optimalTilingFeatures & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) != 0;
}

// Debug overrides, read with getprop: `adb shell setprop debug.pyrowave.path
// compute|fragment` and `adb shell setprop debug.pyrowave.precision 0|1|2`.
std::string debugProperty(const char* name)
{
    char value[PROP_VALUE_MAX] = {};
    return __system_property_get(name, value) > 0 ? std::string(value) : std::string();
}

bool chooseFragmentPath(VulkanContext& ctx)
{
    // PyroWave prefers the fragment path on Qualcomm's and Arm's drivers: it
    // runs the inverse DWT in render passes, which mobile GPUs do faster than
    // compute (6.1 ms against 8.8 ms per frame on an Adreno 840).
    bool fragment = pyrowave_decoder_device_prefers_fragment_path(ctx.pwDevice) || !r8StorageSupported(ctx);
    const std::string path = debugProperty("debug.pyrowave.path");
    if (path == "compute" && r8StorageSupported(ctx)) {
        fragment = false;
    }
    else if (path == "fragment") {
        fragment = true;
    }
    if (!path.empty()) {
        LOGI("PyroWave decode path override: %s", path.c_str());
    }
    return fragment;
}

// PyroWave reads its wavelet precision (0: FP16, 1: FP16 storage with FP32
// coarse levels, the default, 2: FP32) from PYROWAVE_PRECISION once per
// process, when the first decoder is created.
//
// The default stays 1, as in Vibepollo's encoder: precision 0 also stores the
// three coarsest levels in FP16, while the host quantises them for FP32 (up to
// 4096 rather than the 512 PyroWave caps them at when FP16 is in play), so FP16
// could band or clip on bright, flat areas. joemossjr16/artemis-android-pyrowave
// 2e6231a9 measured full FP16 on an Adreno 840's fragment path at 5.66 ms per
// frame against 6.1 ms, but kept the default and only added this override:
// `adb shell setprop debug.pyrowave.precision 0` tries it.
void choosePrecision()
{
    static std::once_flag once;
    std::call_once(once, [] {
        const std::string precision = debugProperty("debug.pyrowave.precision");
        if (precision == "0" || precision == "1" || precision == "2") {
            setenv("PYROWAVE_PRECISION", precision.c_str(), 1);
            LOGI("PyroWave precision override: %s", precision.c_str());
        }
    });
}

// ---------------------------------------------------------------------------
// The renderer
// ---------------------------------------------------------------------------

struct CscConstants {
    float rowR[4];
    float rowG[4];
    float rowB[4];
    float offsetScale[4];
};

// Matches MoonBridge.COLORSPACE_* and COLOR_RANGE_*
CscConstants makeCsc(int colorspace, int colorRange)
{
    double kr, kb;
    switch (colorspace) {
    case 0: kr = 0.299; kb = 0.114; break;     // Rec. 601
    case 2: kr = 0.2627; kb = 0.0593; break;   // Rec. 2020
    default: kr = 0.2126; kb = 0.0722; break;  // Rec. 709
    }
    const double kg = 1.0 - kr - kb;

    CscConstants csc = {};
    csc.rowR[0] = 1.0f;
    csc.rowR[2] = float(2.0 * (1.0 - kr));
    csc.rowG[0] = 1.0f;
    csc.rowG[1] = float(-2.0 * kb * (1.0 - kb) / kg);
    csc.rowG[2] = float(-2.0 * kr * (1.0 - kr) / kg);
    csc.rowB[0] = 1.0f;
    csc.rowB[1] = float(2.0 * (1.0 - kb));

    if (colorRange == 1) {
        // Full range
        csc.offsetScale[0] = 0.0f;
        csc.offsetScale[1] = 128.0f / 255.0f;
        csc.offsetScale[2] = 1.0f;
        csc.offsetScale[3] = 1.0f;
    }
    else {
        // Limited range: 16-235 luma, 16-240 chroma
        csc.offsetScale[0] = 16.0f / 255.0f;
        csc.offsetScale[1] = 128.0f / 255.0f;
        csc.offsetScale[2] = 255.0f / 219.0f;
        csc.offsetScale[3] = 255.0f / 224.0f;
    }
    return csc;
}

struct PendingFrame {
    std::vector<uint8_t> data;
    std::vector<PyroWaveFraming::Segment> segments;
    size_t criticalPackets = 0;
    uint64_t receiveTimeUs = 0;
    bool valid = false;
};

class Renderer {
public:
    // What the stats overlay shows for the last window, handed over and reset by takeStats().
    struct WindowStats {
        uint64_t received = 0;
        uint64_t presented = 0;
        uint64_t dropped = 0;
        uint64_t partial = 0;
        // Frames the host sent that never arrived at all (gaps in the frame numbers)
        uint64_t lost = 0;
        uint64_t hostLatencyFrames = 0;
        uint64_t hostLatencyTotal = 0;  // tenths of a ms, as the host reports them
        uint32_t hostLatencyMin = 0;
        uint32_t hostLatencyMax = 0;
    };

    std::atomic<uint64_t> framesSubmitted {0};
    std::atomic<uint64_t> framesReplaced {0};
    std::atomic<uint64_t> framesRejected {0};
    std::atomic<uint64_t> framesPartial {0};
    std::atomic<uint64_t> framesPresented {0};

    ~Renderer() { shutdown(); }

    // Takes ownership of window, released by shutdown() even if this fails.
    bool initialize(ANativeWindow* window, int videoFormat, int width, int height,
                    int colorspace, int colorRange)
    {
        m_Window = window;
        m_Width = width;
        m_Height = height;
        m_Chroma444 = (videoFormat & VIDEO_FORMAT_MASK_YUV444) != 0;
        m_Csc = makeCsc(colorspace, colorRange);
        m_Geometry = {width, height, m_Chroma444};

        if (!m_Ctx.createInstance(true)) {
            return false;
        }
        VkAndroidSurfaceCreateInfoKHR surfaceInfo = {};
        surfaceInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        surfaceInfo.window = window;
        if (vk().CreateAndroidSurfaceKHR(m_Ctx.instance, &surfaceInfo, nullptr, &m_Surface) != VK_SUCCESS) {
            LOGE("vkCreateAndroidSurfaceKHR failed");
            return false;
        }
        if (!m_Ctx.createDevice(m_Surface) || !m_Ctx.createPyroWaveDevice()) {
            return false;
        }

        m_FragmentPath = chooseFragmentPath(m_Ctx);
        choosePrecision();
        pyrowave_decoder_create_info decoderInfo = {};
        decoderInfo.device = m_Ctx.pwDevice;
        decoderInfo.width = width;
        decoderInfo.height = height;
        decoderInfo.chroma = m_Chroma444 ? PYROWAVE_CHROMA_SUBSAMPLING_444 : PYROWAVE_CHROMA_SUBSAMPLING_420;
        decoderInfo.fragment_path = m_FragmentPath;
        const pyrowave_result result = pyrowave_decoder_create(&decoderInfo, &m_Decoder);
        if (result != PYROWAVE_SUCCESS) {
            LOGE("pyrowave_decoder_create failed: %s", resultString(result));
            m_Decoder = nullptr;
            return false;
        }

        if (!createSyncObjects() || !createPlanes() || !createPipeline() || !createSwapchain()) {
            return false;
        }

        LOGI("PyroWave renderer ready: %dx%d %s, %s decode",
             width, height, m_Chroma444 ? "4:4:4" : "4:2:0",
             m_FragmentPath ? "fragment" : "compute");

        m_Thread = std::thread([this] { renderLoop(); });
        return true;
    }

    void submit(PDECODE_UNIT du)
    {
        framesSubmitted++;
        {
            std::lock_guard<std::mutex> lock(m_StatsLock);
            m_StatsWindow.received++;
            if (m_LastFrameNumber != 0 && du->frameNumber > m_LastFrameNumber + 1) {
                m_StatsWindow.lost += uint64_t(du->frameNumber - m_LastFrameNumber - 1);
            }
            m_LastFrameNumber = du->frameNumber;
            if (du->frameHostProcessingLatency != 0) {
                const uint32_t latency = du->frameHostProcessingLatency;
                if (m_StatsWindow.hostLatencyFrames == 0 || latency < m_StatsWindow.hostLatencyMin) {
                    m_StatsWindow.hostLatencyMin = latency;
                }
                m_StatsWindow.hostLatencyMax = std::max(m_StatsWindow.hostLatencyMax, latency);
                m_StatsWindow.hostLatencyTotal += latency;
                m_StatsWindow.hostLatencyFrames++;
            }
        }
        std::lock_guard<std::mutex> lock(m_FrameLock);
        PendingFrame& frame = m_Incoming;
        if (frame.valid) {
            // The render thread has not taken the previous frame yet. Every
            // PyroWave frame is independent, so the newer one replaces it.
            framesReplaced++;
        }
        frame.data.resize(size_t(du->fullLength));
        frame.segments.clear();
        size_t offset = 0;
        for (PLENTRY entry = du->bufferList; entry != nullptr; entry = entry->next) {
            if (offset + size_t(entry->length) > frame.data.size()) {
                frame.data.resize(offset + size_t(entry->length));
            }
            memcpy(frame.data.data() + offset, entry->data, size_t(entry->length));
            // Each buffer is one RTP packet's payload; lost ones are zero-filled
            frame.segments.push_back({offset, size_t(entry->length),
                                      entry->bufferType == BUFFER_TYPE_LOST,
                                      entry->bufferType == BUFFER_TYPE_RECORD_START});
            offset += size_t(entry->length);
        }
        frame.data.resize(offset);
        frame.criticalPackets = du->pyrowaveCriticalPackets;
        frame.receiveTimeUs = du->receiveTimeUs;
        frame.valid = true;
        m_FrameCv.notify_one();
    }

    // Idempotent: called by pyroWaveStop() and again by the destructor.
    void shutdown()
    {
        if (m_ShutDown) {
            return;
        }
        m_ShutDown = true;
        {
            std::lock_guard<std::mutex> lock(m_FrameLock);
            m_Stop = true;
            m_FrameCv.notify_all();
        }
        if (m_Thread.joinable()) {
            m_Thread.join();
        }
        if (m_Ctx.device == VK_NULL_HANDLE) {
            // initialize() failed after creating the surface but before the device
            if (m_Surface != VK_NULL_HANDLE) {
                vk().DestroySurfaceKHR(m_Ctx.instance, m_Surface, nullptr);
                m_Surface = VK_NULL_HANDLE;
            }
            releaseWindow();
            return;
        }

        {
            std::lock_guard<std::mutex> lock(m_Ctx.queueLock);
            vk().DeviceWaitIdle(m_Ctx.device);
        }
        if (m_Decoder != nullptr) {
            pyrowave_decoder_destroy(m_Decoder);
            m_Decoder = nullptr;
        }
        destroySwapchain(VK_NULL_HANDLE);
        VkDevice dev = m_Ctx.device;
        auto& v = vk();
        if (m_Pipeline) v.DestroyPipeline(dev, m_Pipeline, nullptr);
        if (m_PipelineLayout) v.DestroyPipelineLayout(dev, m_PipelineLayout, nullptr);
        if (m_RenderPass) v.DestroyRenderPass(dev, m_RenderPass, nullptr);
        if (m_DescriptorPool) v.DestroyDescriptorPool(dev, m_DescriptorPool, nullptr);
        if (m_DescriptorSetLayout) v.DestroyDescriptorSetLayout(dev, m_DescriptorSetLayout, nullptr);
        if (m_Sampler) v.DestroySampler(dev, m_Sampler, nullptr);
        for (int i = 0; i < 3; i++) {
            if (m_PlaneViews[i]) v.DestroyImageView(dev, m_PlaneViews[i], nullptr);
            if (m_Planes[i]) v.DestroyImage(dev, m_Planes[i], nullptr);
            if (m_PlaneMemory[i]) v.FreeMemory(dev, m_PlaneMemory[i], nullptr);
        }
        if (m_FrameFence) v.DestroyFence(dev, m_FrameFence, nullptr);
        if (m_AcquireSemaphore) v.DestroySemaphore(dev, m_AcquireSemaphore, nullptr);
        if (m_DecodeTimeline) v.DestroySemaphore(dev, m_DecodeTimeline, nullptr);
        if (m_RenderTimeline) v.DestroySemaphore(dev, m_RenderTimeline, nullptr);
        if (m_CommandPool) v.DestroyCommandPool(dev, m_CommandPool, nullptr);
        if (m_Surface) v.DestroySurfaceKHR(m_Ctx.instance, m_Surface, nullptr);
        m_Surface = VK_NULL_HANDLE;
        m_Pipeline = VK_NULL_HANDLE;
        m_CommandPool = VK_NULL_HANDLE;
        releaseWindow();
    }

private:
    Vk& vk() { return m_Ctx.vk; }

    void releaseWindow()
    {
        if (m_Window != nullptr) {
            ANativeWindow_release(m_Window);
            m_Window = nullptr;
        }
    }

    bool createSyncObjects()
    {
        auto& v = vk();
        VkDevice dev = m_Ctx.device;

        VkSemaphoreTypeCreateInfo timelineType = {};
        timelineType.sType = VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO;
        timelineType.semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE;
        VkSemaphoreCreateInfo timelineInfo = {};
        timelineInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        timelineInfo.pNext = &timelineType;
        VkSemaphoreCreateInfo binaryInfo = {};
        binaryInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        VkFenceCreateInfo fenceInfo = {};
        fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;

        VkCommandPoolCreateInfo poolInfo = {};
        poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        poolInfo.queueFamilyIndex = m_Ctx.queueFamily;

        if (v.CreateSemaphore(dev, &timelineInfo, nullptr, &m_DecodeTimeline) != VK_SUCCESS ||
                v.CreateSemaphore(dev, &timelineInfo, nullptr, &m_RenderTimeline) != VK_SUCCESS ||
                v.CreateSemaphore(dev, &binaryInfo, nullptr, &m_AcquireSemaphore) != VK_SUCCESS ||
                v.CreateFence(dev, &fenceInfo, nullptr, &m_FrameFence) != VK_SUCCESS ||
                v.CreateCommandPool(dev, &poolInfo, nullptr, &m_CommandPool) != VK_SUCCESS) {
            LOGE("Creating synchronization objects failed");
            return false;
        }

        VkCommandBufferAllocateInfo allocInfo = {};
        allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocInfo.commandPool = m_CommandPool;
        allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocInfo.commandBufferCount = 1;
        return v.AllocateCommandBuffers(dev, &allocInfo, &m_CommandBuffer) == VK_SUCCESS;
    }

    bool findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags flags, uint32_t& index)
    {
        VkPhysicalDeviceMemoryProperties props;
        vk().GetPhysicalDeviceMemoryProperties(m_Ctx.physicalDevice, &props);
        for (uint32_t i = 0; i < props.memoryTypeCount; i++) {
            if ((typeBits & (1u << i)) && (props.memoryTypes[i].propertyFlags & flags) == flags) {
                index = i;
                return true;
            }
        }
        return false;
    }

    // The three planes PyroWave decodes into and the renderer samples. They
    // stay in VK_IMAGE_LAYOUT_GENERAL, which both sides accept.
    bool createPlanes()
    {
        auto& v = vk();
        VkDevice dev = m_Ctx.device;
        for (int i = 0; i < 3; i++) {
            const bool chroma = i != 0 && !m_Chroma444;
            m_PlaneWidth[i] = chroma ? uint32_t(m_Width + 1) / 2 : uint32_t(m_Width);
            m_PlaneHeight[i] = chroma ? uint32_t(m_Height + 1) / 2 : uint32_t(m_Height);

            VkImageCreateInfo imageInfo = {};
            imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
            imageInfo.imageType = VK_IMAGE_TYPE_2D;
            imageInfo.format = VK_FORMAT_R8_UNORM;
            imageInfo.extent = {m_PlaneWidth[i], m_PlaneHeight[i], 1};
            imageInfo.mipLevels = 1;
            imageInfo.arrayLayers = 1;
            imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
            imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
            imageInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT |
                              (m_FragmentPath ? VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT : VK_IMAGE_USAGE_STORAGE_BIT);
            imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
            imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            if (v.CreateImage(dev, &imageInfo, nullptr, &m_Planes[i]) != VK_SUCCESS) {
                LOGE("Creating plane %d failed", i);
                return false;
            }

            VkMemoryRequirements reqs;
            v.GetImageMemoryRequirements(dev, m_Planes[i], &reqs);
            VkMemoryAllocateInfo allocInfo = {};
            allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
            allocInfo.allocationSize = reqs.size;
            if (!findMemoryType(reqs.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, allocInfo.memoryTypeIndex) ||
                    v.AllocateMemory(dev, &allocInfo, nullptr, &m_PlaneMemory[i]) != VK_SUCCESS ||
                    v.BindImageMemory(dev, m_Planes[i], m_PlaneMemory[i], 0) != VK_SUCCESS) {
                LOGE("Allocating plane %d failed", i);
                return false;
            }

            VkImageViewCreateInfo viewInfo = {};
            viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
            viewInfo.image = m_Planes[i];
            viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
            viewInfo.format = VK_FORMAT_R8_UNORM;
            viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            if (v.CreateImageView(dev, &viewInfo, nullptr, &m_PlaneViews[i]) != VK_SUCCESS) {
                return false;
            }

            pyrowave_image_view& pv = m_Buffers.planes[i];
            pv.image = m_Planes[i];
            pv.width = m_PlaneWidth[i];
            pv.height = m_PlaneHeight[i];
            pv.image_format = VK_FORMAT_R8_UNORM;
            pv.view_format = VK_FORMAT_R8_UNORM;
            pv.mip_level = 0;
            pv.layer = 0;
            pv.aspect = VK_IMAGE_ASPECT_COLOR_BIT;
            pv.swizzle = VK_COMPONENT_SWIZZLE_IDENTITY;
            pv.layout = VK_IMAGE_LAYOUT_GENERAL;
        }

        // Move the planes to GENERAL once, with black content until the first frame
        VkCommandBufferBeginInfo beginInfo = {};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        v.BeginCommandBuffer(m_CommandBuffer, &beginInfo);
        VkImageMemoryBarrier barriers[3] = {};
        for (int i = 0; i < 3; i++) {
            barriers[i].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            barriers[i].srcAccessMask = 0;
            barriers[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT |
                                        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            barriers[i].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            barriers[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
            barriers[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barriers[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barriers[i].image = m_Planes[i];
            barriers[i].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        }
        v.CmdPipelineBarrier(m_CommandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 3, barriers);
        v.EndCommandBuffer(m_CommandBuffer);

        VkSubmitInfo submitInfo = {};
        submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &m_CommandBuffer;
        std::lock_guard<std::mutex> lock(m_Ctx.queueLock);
        if (v.QueueSubmit(m_Ctx.queue, 1, &submitInfo, VK_NULL_HANDLE) != VK_SUCCESS) {
            return false;
        }
        return v.QueueWaitIdle(m_Ctx.queue) == VK_SUCCESS;
    }

    bool createPipeline()
    {
        auto& v = vk();
        VkDevice dev = m_Ctx.device;

        VkSamplerCreateInfo samplerInfo = {};
        samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
        samplerInfo.magFilter = VK_FILTER_LINEAR;
        samplerInfo.minFilter = VK_FILTER_LINEAR;
        samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.maxLod = 0.0f;
        if (v.CreateSampler(dev, &samplerInfo, nullptr, &m_Sampler) != VK_SUCCESS) {
            return false;
        }

        VkDescriptorSetLayoutBinding bindings[3] = {};
        for (uint32_t i = 0; i < 3; i++) {
            bindings[i].binding = i;
            bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            bindings[i].descriptorCount = 1;
            bindings[i].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        }
        VkDescriptorSetLayoutCreateInfo layoutInfo = {};
        layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        layoutInfo.bindingCount = 3;
        layoutInfo.pBindings = bindings;
        if (v.CreateDescriptorSetLayout(dev, &layoutInfo, nullptr, &m_DescriptorSetLayout) != VK_SUCCESS) {
            return false;
        }

        VkDescriptorPoolSize poolSize = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 3};
        VkDescriptorPoolCreateInfo poolInfo = {};
        poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        poolInfo.maxSets = 1;
        poolInfo.poolSizeCount = 1;
        poolInfo.pPoolSizes = &poolSize;
        if (v.CreateDescriptorPool(dev, &poolInfo, nullptr, &m_DescriptorPool) != VK_SUCCESS) {
            return false;
        }
        VkDescriptorSetAllocateInfo allocInfo = {};
        allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocInfo.descriptorPool = m_DescriptorPool;
        allocInfo.descriptorSetCount = 1;
        allocInfo.pSetLayouts = &m_DescriptorSetLayout;
        if (v.AllocateDescriptorSets(dev, &allocInfo, &m_DescriptorSet) != VK_SUCCESS) {
            return false;
        }
        VkDescriptorImageInfo imageInfos[3] = {};
        VkWriteDescriptorSet writes[3] = {};
        for (uint32_t i = 0; i < 3; i++) {
            imageInfos[i].sampler = m_Sampler;
            imageInfos[i].imageView = m_PlaneViews[i];
            imageInfos[i].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
            writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[i].dstSet = m_DescriptorSet;
            writes[i].dstBinding = i;
            writes[i].descriptorCount = 1;
            writes[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            writes[i].pImageInfo = &imageInfos[i];
        }
        v.UpdateDescriptorSets(dev, 3, writes, 0, nullptr);

        VkPushConstantRange pushRange = {VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(CscConstants)};
        VkPipelineLayoutCreateInfo pipelineLayoutInfo = {};
        pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        pipelineLayoutInfo.setLayoutCount = 1;
        pipelineLayoutInfo.pSetLayouts = &m_DescriptorSetLayout;
        pipelineLayoutInfo.pushConstantRangeCount = 1;
        pipelineLayoutInfo.pPushConstantRanges = &pushRange;
        if (v.CreatePipelineLayout(dev, &pipelineLayoutInfo, nullptr, &m_PipelineLayout) != VK_SUCCESS) {
            return false;
        }

        // The swapchain format is not known yet; Android swapchains are RGBA8
        // or BGRA8, and both are compatible with a render pass for either.
        m_SwapchainFormat = pickSurfaceFormat().format;
        return createRenderPassAndPipeline();
    }

    VkSurfaceFormatKHR pickSurfaceFormat()
    {
        auto& v = vk();
        uint32_t count = 0;
        v.GetPhysicalDeviceSurfaceFormatsKHR(m_Ctx.physicalDevice, m_Surface, &count, nullptr);
        std::vector<VkSurfaceFormatKHR> formats(count);
        v.GetPhysicalDeviceSurfaceFormatsKHR(m_Ctx.physicalDevice, m_Surface, &count, formats.data());
        for (const auto& format : formats) {
            // UNORM: the shader writes display-encoded values already
            if ((format.format == VK_FORMAT_R8G8B8A8_UNORM || format.format == VK_FORMAT_B8G8R8A8_UNORM) &&
                    format.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                return format;
            }
        }
        return formats.empty() ? VkSurfaceFormatKHR {VK_FORMAT_R8G8B8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR} :
                                 formats[0];
    }

    bool createRenderPassAndPipeline()
    {
        auto& v = vk();
        VkDevice dev = m_Ctx.device;

        VkAttachmentDescription attachment = {};
        attachment.format = m_SwapchainFormat;
        attachment.samples = VK_SAMPLE_COUNT_1_BIT;
        attachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        attachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        VkAttachmentReference colorRef = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription subpass = {};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &colorRef;
        // The acquire semaphore is waited at COLOR_ATTACHMENT_OUTPUT: order the
        // layout transition after it.
        VkSubpassDependency dependency = {};
        dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
        dependency.dstSubpass = 0;
        dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.srcAccessMask = 0;
        dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        VkRenderPassCreateInfo renderPassInfo = {};
        renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        renderPassInfo.attachmentCount = 1;
        renderPassInfo.pAttachments = &attachment;
        renderPassInfo.subpassCount = 1;
        renderPassInfo.pSubpasses = &subpass;
        renderPassInfo.dependencyCount = 1;
        renderPassInfo.pDependencies = &dependency;
        if (v.CreateRenderPass(dev, &renderPassInfo, nullptr, &m_RenderPass) != VK_SUCCESS) {
            return false;
        }

        VkShaderModuleCreateInfo vertInfo = {};
        vertInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        vertInfo.codeSize = sizeof(k_FullscreenVertSpv);
        vertInfo.pCode = k_FullscreenVertSpv;
        VkShaderModuleCreateInfo fragInfo = {};
        fragInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        fragInfo.codeSize = sizeof(k_PlanarCscFragSpv);
        fragInfo.pCode = k_PlanarCscFragSpv;
        VkShaderModule vert = VK_NULL_HANDLE, frag = VK_NULL_HANDLE;
        if (v.CreateShaderModule(dev, &vertInfo, nullptr, &vert) != VK_SUCCESS ||
                v.CreateShaderModule(dev, &fragInfo, nullptr, &frag) != VK_SUCCESS) {
            if (vert) v.DestroyShaderModule(dev, vert, nullptr);
            return false;
        }

        VkPipelineShaderStageCreateInfo stages[2] = {};
        stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        stages[0].module = vert;
        stages[0].pName = "main";
        stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        stages[1].module = frag;
        stages[1].pName = "main";

        VkPipelineVertexInputStateCreateInfo vertexInput = {};
        vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
        VkPipelineInputAssemblyStateCreateInfo inputAssembly = {};
        inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VkPipelineViewportStateCreateInfo viewportState = {};
        viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        viewportState.viewportCount = 1;
        viewportState.scissorCount = 1;
        VkPipelineRasterizationStateCreateInfo raster = {};
        raster.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        raster.polygonMode = VK_POLYGON_MODE_FILL;
        raster.cullMode = VK_CULL_MODE_NONE;
        raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        raster.lineWidth = 1.0f;
        VkPipelineMultisampleStateCreateInfo multisample = {};
        multisample.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
        VkPipelineColorBlendAttachmentState blendAttachment = {};
        blendAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                         VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
        VkPipelineColorBlendStateCreateInfo blend = {};
        blend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        blend.attachmentCount = 1;
        blend.pAttachments = &blendAttachment;
        VkDynamicState dynamicStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
        VkPipelineDynamicStateCreateInfo dynamic = {};
        dynamic.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
        dynamic.dynamicStateCount = 2;
        dynamic.pDynamicStates = dynamicStates;

        VkGraphicsPipelineCreateInfo pipelineInfo = {};
        pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        pipelineInfo.stageCount = 2;
        pipelineInfo.pStages = stages;
        pipelineInfo.pVertexInputState = &vertexInput;
        pipelineInfo.pInputAssemblyState = &inputAssembly;
        pipelineInfo.pViewportState = &viewportState;
        pipelineInfo.pRasterizationState = &raster;
        pipelineInfo.pMultisampleState = &multisample;
        pipelineInfo.pColorBlendState = &blend;
        pipelineInfo.pDynamicState = &dynamic;
        pipelineInfo.layout = m_PipelineLayout;
        pipelineInfo.renderPass = m_RenderPass;
        const VkResult result = v.CreateGraphicsPipelines(dev, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &m_Pipeline);
        v.DestroyShaderModule(dev, vert, nullptr);
        v.DestroyShaderModule(dev, frag, nullptr);
        return result == VK_SUCCESS;
    }

    bool createSwapchain()
    {
        auto& v = vk();
        VkDevice dev = m_Ctx.device;

        VkSurfaceCapabilitiesKHR caps;
        if (v.GetPhysicalDeviceSurfaceCapabilitiesKHR(m_Ctx.physicalDevice, m_Surface, &caps) != VK_SUCCESS) {
            return false;
        }
        VkExtent2D extent = caps.currentExtent;
        if (extent.width == 0xFFFFFFFFu) {
            extent.width = uint32_t(ANativeWindow_getWidth(m_Window));
            extent.height = uint32_t(ANativeWindow_getHeight(m_Window));
        }
        if (extent.width == 0 || extent.height == 0) {
            return false;
        }

        // MAILBOX keeps the newest frame without blocking; FIFO always exists
        uint32_t modeCount = 0;
        v.GetPhysicalDeviceSurfacePresentModesKHR(m_Ctx.physicalDevice, m_Surface, &modeCount, nullptr);
        std::vector<VkPresentModeKHR> modes(modeCount);
        v.GetPhysicalDeviceSurfacePresentModesKHR(m_Ctx.physicalDevice, m_Surface, &modeCount, modes.data());
        VkPresentModeKHR presentMode = VK_PRESENT_MODE_FIFO_KHR;
        if (std::find(modes.begin(), modes.end(), VK_PRESENT_MODE_MAILBOX_KHR) != modes.end()) {
            presentMode = VK_PRESENT_MODE_MAILBOX_KHR;
        }

        uint32_t imageCount = std::max(caps.minImageCount, 3u);
        if (caps.maxImageCount != 0) {
            imageCount = std::min(imageCount, caps.maxImageCount);
        }
        VkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
        for (VkCompositeAlphaFlagBitsKHR candidate : {VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
                                                      VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR}) {
            if (caps.supportedCompositeAlpha & candidate) {
                alpha = candidate;
                break;
            }
        }

        VkSwapchainKHR oldSwapchain = m_Swapchain;
        VkSwapchainCreateInfoKHR info = {};
        info.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
        info.surface = m_Surface;
        info.minImageCount = imageCount;
        info.imageFormat = m_SwapchainFormat;
        info.imageColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        info.imageExtent = extent;
        info.imageArrayLayers = 1;
        info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        // The compositor rotates the identity-transformed image for us. That
        // makes the swapchain SUBOPTIMAL when the display is rotated, which is
        // expected and must not trigger a recreation on every frame.
        info.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) ?
                                VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;
        info.compositeAlpha = alpha;
        info.presentMode = presentMode;
        info.clipped = VK_TRUE;
        info.oldSwapchain = oldSwapchain;
        VkSwapchainKHR swapchain = VK_NULL_HANDLE;
        if (v.CreateSwapchainKHR(dev, &info, nullptr, &swapchain) != VK_SUCCESS) {
            LOGE("vkCreateSwapchainKHR failed");
            return false;
        }
        destroySwapchain(oldSwapchain);
        m_Swapchain = swapchain;
        m_Extent = extent;

        uint32_t count = 0;
        v.GetSwapchainImagesKHR(dev, m_Swapchain, &count, nullptr);
        m_SwapchainImages.resize(count);
        v.GetSwapchainImagesKHR(dev, m_Swapchain, &count, m_SwapchainImages.data());
        m_SwapchainViews.resize(count, VK_NULL_HANDLE);
        m_Framebuffers.resize(count, VK_NULL_HANDLE);
        m_PresentSemaphores.resize(count, VK_NULL_HANDLE);
        for (uint32_t i = 0; i < count; i++) {
            VkImageViewCreateInfo viewInfo = {};
            viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
            viewInfo.image = m_SwapchainImages[i];
            viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
            viewInfo.format = m_SwapchainFormat;
            viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            VkFramebufferCreateInfo fbInfo = {};
            fbInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
            fbInfo.renderPass = m_RenderPass;
            fbInfo.attachmentCount = 1;
            fbInfo.width = extent.width;
            fbInfo.height = extent.height;
            fbInfo.layers = 1;
            VkSemaphoreCreateInfo semInfo = {};
            semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
            if (v.CreateImageView(dev, &viewInfo, nullptr, &m_SwapchainViews[i]) != VK_SUCCESS) {
                return false;
            }
            fbInfo.pAttachments = &m_SwapchainViews[i];
            if (v.CreateFramebuffer(dev, &fbInfo, nullptr, &m_Framebuffers[i]) != VK_SUCCESS ||
                    v.CreateSemaphore(dev, &semInfo, nullptr, &m_PresentSemaphores[i]) != VK_SUCCESS) {
                return false;
            }
        }
        LOGI("Swapchain %ux%u, %u images, %s", extent.width, extent.height, count,
             presentMode == VK_PRESENT_MODE_MAILBOX_KHR ? "mailbox" : "fifo");
        return true;
    }

    // Destroys the per-image resources of the current swapchain and retires
    // retired (the swapchain a new one replaced, or the current one).
    void destroySwapchain(VkSwapchainKHR retired)
    {
        auto& v = vk();
        VkDevice dev = m_Ctx.device;
        if (dev == VK_NULL_HANDLE) {
            return;
        }
        for (auto fb : m_Framebuffers) if (fb) v.DestroyFramebuffer(dev, fb, nullptr);
        for (auto view : m_SwapchainViews) if (view) v.DestroyImageView(dev, view, nullptr);
        for (auto sem : m_PresentSemaphores) if (sem) v.DestroySemaphore(dev, sem, nullptr);
        m_Framebuffers.clear();
        m_SwapchainViews.clear();
        m_PresentSemaphores.clear();
        m_SwapchainImages.clear();
        const VkSwapchainKHR target = retired != VK_NULL_HANDLE ? retired : m_Swapchain;
        if (target != VK_NULL_HANDLE) {
            v.DestroySwapchainKHR(dev, target, nullptr);
        }
        if (target == m_Swapchain) {
            m_Swapchain = VK_NULL_HANDLE;
        }
    }

    bool recreateSwapchain()
    {
        {
            std::lock_guard<std::mutex> lock(m_Ctx.queueLock);
            vk().DeviceWaitIdle(m_Ctx.device);
        }
        return createSwapchain();
    }

    void renderLoop()
    {
        PendingFrame frame;
        while (true) {
            {
                std::unique_lock<std::mutex> lock(m_FrameLock);
                m_FrameCv.wait(lock, [this] { return m_Stop || m_Incoming.valid; });
                if (m_Stop) {
                    return;
                }
                std::swap(frame, m_Incoming);
                m_Incoming.valid = false;
            }
            decodeAndPresent(frame);
            sampleGpuTimesIfDue();
        }
    }

    // Once a second, PyroWave's own GPU timings per pass: their sum is the decode
    // time the stats overlay shows. Every 5 s, they go to the log too, with the
    // frame counters, which show how long a decode takes on this GPU.
    void sampleGpuTimesIfDue()
    {
        const auto now = std::chrono::steady_clock::now();
        if (m_LastGpuSample.time_since_epoch().count() == 0) {
            m_LastGpuSample = now;
            m_LastStatsLog = now;
            return;
        }
        if (now - m_LastGpuSample < std::chrono::seconds(1)) {
            return;
        }
        m_LastGpuSample = now;
        const bool log = now - m_LastStatsLog >= std::chrono::seconds(5);
        if (log) {
            m_LastStatsLog = now;
            LOGI("PyroWave: %llu received, %llu presented, %llu replaced, %llu dropped, %llu partial",
                 static_cast<unsigned long long>(framesSubmitted.load()),
                 static_cast<unsigned long long>(framesPresented.load()),
                 static_cast<unsigned long long>(framesReplaced.load()),
                 static_cast<unsigned long long>(framesRejected.load()),
                 static_cast<unsigned long long>(framesPartial.load()));
        }

        struct Sample {
            bool log;
            double totalMs;
            bool any;
        } sample = {log, 0, false};
        pyrowave_device_report_performance_stats(m_Ctx.pwDevice, [](void* userdata, const char* msg) {
            auto* sample = static_cast<Sample*>(userdata);
            // Skip the memory heap lines, which do not change during a session
            if (strncmp(msg, "Memory Heap", 11) == 0) {
                return;
            }
            // "<pass>: <ms> ms per frame"
            const char* colon = strrchr(msg, ':');
            double ms = 0;
            if (colon != nullptr && sscanf(colon + 1, "%lf ms per frame", &ms) == 1) {
                sample->totalMs += ms;
                sample->any = true;
            }
            if (sample->log) {
                LOGI("PyroWave pass: %s", msg);
            }
        }, &sample, true);
        if (sample.any) {
            m_GpuDecodeMs.store(sample.totalMs);
        }
    }

    bool decodeFrame(const PendingFrame& frame)
    {
        std::string error;
        if (!PyroWaveFraming::parse(frame.data.data(), frame.data.size(), frame.segments,
                                    frame.criticalPackets, m_Geometry, m_Parsed, error)) {
            logDrop(error);
            return false;
        }

        // Every frame is independent. Clearing first keeps PyroWave's 3-bit
        // sequence counter from treating a frame after a long drop as stale.
        pyrowave_decoder_clear(m_Decoder);
        for (const auto& span : m_Parsed.spans) {
            const pyrowave_result result = pyrowave_decoder_push_packet(m_Decoder, frame.data.data() + span.offset,
                                                                        span.size);
            if (result != PYROWAVE_SUCCESS) {
                logDrop(std::string("decoder rejected a packet: ") + resultString(result));
                return false;
            }
        }

        if (!m_Parsed.partial) {
            if (!pyrowave_decoder_decode_is_ready(m_Decoder, false)) {
                logDrop("frame is incomplete");
                return false;
            }
        }
        else {
            // A partial frame decodes whenever its coarsest wavelet level is
            // intact; every other loss blurs the area it covered.
            if (!m_Parsed.coarseLevelIntact) {
                logDrop("part of the coarsest wavelet level was lost");
                return false;
            }
            // As Vibepollo's protocol document prescribes: no pristine-band
            // requirement (PyroWave cannot tell a lost block from an all-zero
            // block that was never sent), and more than 90% of the blocks.
            if (!pyrowave_decoder_decode_is_ready_with_sideband(m_Decoder, true, 0, 0.9f, nullptr, 0)) {
                logDrop("fewer than 90% of the blocks arrived");
                return false;
            }
            framesPartial++;
        }

        // The planes are free once the previous render that sampled them is done
        pyrowave_gpu_sync_operation acquire = {};
        acquire.sync = {m_RenderTimeline, m_RenderValue};
        pyrowave_gpu_sync_operation release = {};
        release.sync = {m_DecodeTimeline, m_DecodeValue + 1};
        const pyrowave_result result = pyrowave_decoder_decode_gpu_buffer(m_Decoder, &acquire, &release, &m_Buffers);
        if (result != PYROWAVE_SUCCESS) {
            logDrop(std::string("GPU decode failed: ") + resultString(result));
            return false;
        }
        m_DecodeValue++;
        return true;
    }

    void logDrop(const std::string& reason)
    {
        framesRejected++;
        const auto now = std::chrono::steady_clock::now();
        if (now - m_LastDropLog >= std::chrono::seconds(1)) {
            m_LastDropLog = now;
            LOGW("PyroWave dropped a frame: %s (%llu dropped so far)", reason.c_str(),
                 static_cast<unsigned long long>(framesRejected.load()));
        }
    }

    // Stops decoding and presenting for the rest of the session. The stream
    // freezes on its last frame, but shutdown can still join the render thread.
    void markBroken(const char* reason)
    {
        if (!m_Broken) {
            LOGE("PyroWave renderer stopped presenting: %s", reason);
            m_Broken = true;
        }
    }

    void decodeAndPresent(const PendingFrame& frame)
    {
        if (m_Broken) {
            framesRejected++;
            return;
        }
        if (!decodeFrame(frame)) {
            return;
        }

        auto& v = vk();
        VkDevice dev = m_Ctx.device;
        if (m_NeedRecreate) {
            if (!recreateSwapchain()) {
                return;
            }
            m_NeedRecreate = false;
        }

        // The command buffer and acquire semaphore are reused every frame. The
        // wait is bounded so that a hung GPU cannot keep shutdown from joining
        // this thread; a frame takes milliseconds.
        const VkResult fenceResult = v.WaitForFences(dev, 1, &m_FrameFence, VK_TRUE, k_FenceTimeoutNs);
        if (fenceResult == VK_TIMEOUT) {
            logDrop("the previous frame is still on the GPU");
            return;
        }
        if (fenceResult != VK_SUCCESS) {
            markBroken("device lost");
            return;
        }

        uint32_t imageIndex = 0;
        VkResult result = v.AcquireNextImageKHR(dev, m_Swapchain, 100000000ull, m_AcquireSemaphore,
                                                VK_NULL_HANDLE, &imageIndex);
        if (result == VK_ERROR_OUT_OF_DATE_KHR) {
            m_NeedRecreate = true;
            return;
        }
        if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
            // Timeout or a lost surface: skip this frame, the next one retries
            return;
        }
        v.ResetFences(dev, 1, &m_FrameFence);

        VkCommandBufferBeginInfo beginInfo = {};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        v.ResetCommandBuffer(m_CommandBuffer, 0);
        v.BeginCommandBuffer(m_CommandBuffer, &beginInfo);

        VkClearValue clear = {};
        clear.color = {{0.0f, 0.0f, 0.0f, 1.0f}};
        VkRenderPassBeginInfo passInfo = {};
        passInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        passInfo.renderPass = m_RenderPass;
        passInfo.framebuffer = m_Framebuffers[imageIndex];
        passInfo.renderArea.extent = m_Extent;
        passInfo.clearValueCount = 1;
        passInfo.pClearValues = &clear;
        v.CmdBeginRenderPass(m_CommandBuffer, &passInfo, VK_SUBPASS_CONTENTS_INLINE);

        // Letterbox: fit the stream into the surface, preserving its aspect ratio
        const float scale = std::min(float(m_Extent.width) / float(m_Width),
                                     float(m_Extent.height) / float(m_Height));
        VkViewport viewport = {};
        viewport.width = std::floor(float(m_Width) * scale);
        viewport.height = std::floor(float(m_Height) * scale);
        viewport.x = std::floor((float(m_Extent.width) - viewport.width) / 2.0f);
        viewport.y = std::floor((float(m_Extent.height) - viewport.height) / 2.0f);
        viewport.maxDepth = 1.0f;
        VkRect2D scissor = {{0, 0}, m_Extent};
        v.CmdSetViewport(m_CommandBuffer, 0, 1, &viewport);
        v.CmdSetScissor(m_CommandBuffer, 0, 1, &scissor);
        v.CmdBindPipeline(m_CommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, m_Pipeline);
        v.CmdBindDescriptorSets(m_CommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, m_PipelineLayout,
                                0, 1, &m_DescriptorSet, 0, nullptr);
        v.CmdPushConstants(m_CommandBuffer, m_PipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT,
                           0, sizeof(m_Csc), &m_Csc);
        v.CmdDraw(m_CommandBuffer, 3, 1, 0, 0);
        v.CmdEndRenderPass(m_CommandBuffer);
        v.EndCommandBuffer(m_CommandBuffer);

        // Wait for the image and the decode; signal the present and the render
        // timeline the next decode waits for before overwriting the planes.
        const VkSemaphore waitSems[2] = {m_AcquireSemaphore, m_DecodeTimeline};
        const VkPipelineStageFlags waitStages[2] = {VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                                                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT};
        const uint64_t waitValues[2] = {0, m_DecodeValue};
        const VkSemaphore signalSems[2] = {m_PresentSemaphores[imageIndex], m_RenderTimeline};
        const uint64_t signalValues[2] = {0, m_RenderValue + 1};
        VkTimelineSemaphoreSubmitInfo timelineInfo = {};
        timelineInfo.sType = VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO;
        timelineInfo.waitSemaphoreValueCount = 2;
        timelineInfo.pWaitSemaphoreValues = waitValues;
        timelineInfo.signalSemaphoreValueCount = 2;
        timelineInfo.pSignalSemaphoreValues = signalValues;
        VkSubmitInfo submitInfo = {};
        submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submitInfo.pNext = &timelineInfo;
        submitInfo.waitSemaphoreCount = 2;
        submitInfo.pWaitSemaphores = waitSems;
        submitInfo.pWaitDstStageMask = waitStages;
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &m_CommandBuffer;
        submitInfo.signalSemaphoreCount = 2;
        submitInfo.pSignalSemaphores = signalSems;

        VkPresentInfoKHR presentInfo = {};
        presentInfo.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        presentInfo.waitSemaphoreCount = 1;
        presentInfo.pWaitSemaphores = &m_PresentSemaphores[imageIndex];
        presentInfo.swapchainCount = 1;
        presentInfo.pSwapchains = &m_Swapchain;
        presentInfo.pImageIndices = &imageIndex;

        {
            std::lock_guard<std::mutex> lock(m_Ctx.queueLock);
            if (v.QueueSubmit(m_Ctx.queue, 1, &submitInfo, m_FrameFence) != VK_SUCCESS) {
                // The fence stays unsignalled, the image acquired and the acquire
                // semaphore signalled: none of them can be used again safely
                markBroken("vkQueueSubmit failed");
                return;
            }
            m_RenderValue++;
            result = v.QueuePresentKHR(m_Ctx.queue, &presentInfo);
        }
        if (result == VK_ERROR_OUT_OF_DATE_KHR) {
            m_NeedRecreate = true;
        }

        framesPresented++;
        std::lock_guard<std::mutex> lock(m_StatsLock);
        m_StatsWindow.presented++;
    }

public:
    // The last window's stats, and a fresh window from here. Decode time is PyroWave's
    // GPU time per frame over the last second, or a negative value before the first sample.
    WindowStats takeStats(double& gpuDecodeMs)
    {
        std::lock_guard<std::mutex> lock(m_StatsLock);
        WindowStats taken = m_StatsWindow;
        taken.dropped = framesRejected.load() - m_RejectedAtLastTake;
        taken.partial = framesPartial.load() - m_PartialAtLastTake;
        m_RejectedAtLastTake += taken.dropped;
        m_PartialAtLastTake += taken.partial;
        m_StatsWindow = {};
        gpuDecodeMs = m_GpuDecodeMs.load();
        return taken;
    }

    const std::string& gpuName() const { return m_Ctx.gpuName; }

private:

    VulkanContext m_Ctx;
    ANativeWindow* m_Window = nullptr;
    VkSurfaceKHR m_Surface = VK_NULL_HANDLE;
    int m_Width = 0;
    int m_Height = 0;
    bool m_Chroma444 = false;
    bool m_FragmentPath = false;
    CscConstants m_Csc = {};
    PyroWaveFraming::StreamGeometry m_Geometry = {};
    PyroWaveFraming::Frame m_Parsed;

    pyrowave_decoder m_Decoder = nullptr;
    pyrowave_gpu_buffers m_Buffers = {};
    VkImage m_Planes[3] = {};
    VkDeviceMemory m_PlaneMemory[3] = {};
    VkImageView m_PlaneViews[3] = {};
    uint32_t m_PlaneWidth[3] = {};
    uint32_t m_PlaneHeight[3] = {};

    VkSampler m_Sampler = VK_NULL_HANDLE;
    VkDescriptorSetLayout m_DescriptorSetLayout = VK_NULL_HANDLE;
    VkDescriptorPool m_DescriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet m_DescriptorSet = VK_NULL_HANDLE;
    VkPipelineLayout m_PipelineLayout = VK_NULL_HANDLE;
    VkRenderPass m_RenderPass = VK_NULL_HANDLE;
    VkPipeline m_Pipeline = VK_NULL_HANDLE;

    VkFormat m_SwapchainFormat = VK_FORMAT_UNDEFINED;
    VkSwapchainKHR m_Swapchain = VK_NULL_HANDLE;
    VkExtent2D m_Extent = {};
    std::vector<VkImage> m_SwapchainImages;
    std::vector<VkImageView> m_SwapchainViews;
    std::vector<VkFramebuffer> m_Framebuffers;
    std::vector<VkSemaphore> m_PresentSemaphores;
    bool m_NeedRecreate = false;
    bool m_Broken = false;

    VkCommandPool m_CommandPool = VK_NULL_HANDLE;
    VkCommandBuffer m_CommandBuffer = VK_NULL_HANDLE;
    VkFence m_FrameFence = VK_NULL_HANDLE;
    VkSemaphore m_AcquireSemaphore = VK_NULL_HANDLE;
    VkSemaphore m_DecodeTimeline = VK_NULL_HANDLE;
    VkSemaphore m_RenderTimeline = VK_NULL_HANDLE;
    uint64_t m_DecodeValue = 0;
    uint64_t m_RenderValue = 0;

    std::mutex m_FrameLock;
    std::condition_variable m_FrameCv;
    PendingFrame m_Incoming;
    bool m_Stop = false;
    bool m_ShutDown = false;
    std::thread m_Thread;
    std::chrono::steady_clock::time_point m_LastDropLog;
    std::chrono::steady_clock::time_point m_LastStatsLog;
    std::chrono::steady_clock::time_point m_LastGpuSample;

    std::mutex m_StatsLock;
    WindowStats m_StatsWindow;
    int m_LastFrameNumber = 0;
    uint64_t m_RejectedAtLastTake = 0;
    uint64_t m_PartialAtLastTake = 0;
    std::atomic<double> m_GpuDecodeMs {-1};
};

// The active renderer. submit() runs on moonlight-common-c's video thread;
// start/stop run on Java threads.
std::mutex g_RendererLock;
std::unique_ptr<Renderer> g_Renderer;

// Probe result cache: -1 unknown, 0 unsupported, 1 supported. g_ProbeLock makes
// a caller wait for a probe already running (the app starts one in the
// background) rather than create a second Vulkan device.
std::mutex g_ProbeLock;
int g_Supported = -1;

bool probeSupport()
{
    VulkanContext ctx;
    if (!ctx.createInstance(false) || !ctx.createDevice(VK_NULL_HANDLE) || !ctx.createPyroWaveDevice()) {
        return false;
    }
    // Decoder creation checks the subgroup and storage features the GPU needs
    pyrowave_decoder_create_info info = {};
    info.device = ctx.pwDevice;
    info.width = 256;
    info.height = 256;
    info.chroma = PYROWAVE_CHROMA_SUBSAMPLING_420;
    info.fragment_path = chooseFragmentPath(ctx);
    choosePrecision();
    pyrowave_decoder decoder = nullptr;
    const pyrowave_result result = pyrowave_decoder_create(&info, &decoder);
    if (result != PYROWAVE_SUCCESS) {
        LOGI("PyroWave decoder unavailable: %s", resultString(result));
        return false;
    }
    pyrowave_decoder_destroy(decoder);
    return true;
}

} // namespace

extern "C" {

int PyroWaveRendererSubmitDecodeUnit(PDECODE_UNIT decodeUnit)
{
    std::lock_guard<std::mutex> lock(g_RendererLock);
    if (g_Renderer) {
        g_Renderer->submit(decodeUnit);
    }
    // Never ask for an IDR: every PyroWave frame is one
    return DR_OK;
}

int PyroWaveRendererIsSupported(void)
{
    std::lock_guard<std::mutex> lock(g_ProbeLock);
    if (g_Supported < 0) {
        g_Supported = probeSupport() ? 1 : 0;
        LOGI("PyroWave decoding is %s on this device", g_Supported ? "available" : "unavailable");
    }
    return g_Supported;
}

int PyroWaveRendererStart(ANativeWindow* window, int videoFormat, int width, int height,
                          int colorspace, int colorRange)
{
    // The renderer owns the window from here on, including on failure
    auto renderer = std::make_unique<Renderer>();
    if (!renderer->initialize(window, videoFormat, width, height, colorspace, colorRange)) {
        renderer.reset();
        return -1;
    }

    std::lock_guard<std::mutex> lock(g_RendererLock);
    g_Renderer = std::move(renderer);
    return 0;
}

int PyroWaveRendererTakeStats(PYROWAVE_RENDERER_STATS* stats)
{
    std::lock_guard<std::mutex> lock(g_RendererLock);
    if (!g_Renderer) {
        return -1;
    }
    double gpuDecodeMs = -1;
    const Renderer::WindowStats window = g_Renderer->takeStats(gpuDecodeMs);
    stats->received = window.received;
    stats->presented = window.presented;
    stats->dropped = window.dropped;
    stats->partial = window.partial;
    stats->lost = window.lost;
    stats->hostLatencyFrames = window.hostLatencyFrames;
    stats->hostLatencyTotal = window.hostLatencyTotal;
    stats->hostLatencyMin = window.hostLatencyMin;
    stats->hostLatencyMax = window.hostLatencyMax;
    stats->gpuDecodeMs = gpuDecodeMs;
    snprintf(stats->gpuName, sizeof(stats->gpuName), "%s", g_Renderer->gpuName().c_str());
    return 0;
}

void PyroWaveRendererStop(void)
{
    std::unique_ptr<Renderer> renderer;
    {
        std::lock_guard<std::mutex> lock(g_RendererLock);
        renderer = std::move(g_Renderer);
    }
    if (renderer) {
        LOGI("PyroWave session: %llu frames received, %llu presented, %llu replaced by newer ones, "
             "%llu dropped, %llu decoded with lost packets",
             static_cast<unsigned long long>(renderer->framesSubmitted.load()),
             static_cast<unsigned long long>(renderer->framesPresented.load()),
             static_cast<unsigned long long>(renderer->framesReplaced.load()),
             static_cast<unsigned long long>(renderer->framesRejected.load()),
             static_cast<unsigned long long>(renderer->framesPartial.load()));
        renderer->shutdown();
    }
}

} // extern "C"
