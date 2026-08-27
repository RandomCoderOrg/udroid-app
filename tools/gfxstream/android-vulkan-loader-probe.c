/* SPDX-License-Identifier: Apache-2.0 */

#include <dlfcn.h>
#include <stdio.h>

#include <vulkan/vulkan.h>

int main(void) {
    const char *path = "/system/lib64/libvulkan.so";
    void *library = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        fprintf(stderr, "[fail] dlopen %s: %s\n", path, dlerror());
        return 1;
    }

    PFN_vkGetInstanceProcAddr get_instance_proc =
        (PFN_vkGetInstanceProcAddr)dlsym(library, "vkGetInstanceProcAddr");
    PFN_vkCreateInstance create_instance =
        (PFN_vkCreateInstance)dlsym(library, "vkCreateInstance");
    PFN_vkEnumerateInstanceExtensionProperties enumerate_extensions =
        (PFN_vkEnumerateInstanceExtensionProperties)dlsym(
            library, "vkEnumerateInstanceExtensionProperties");
    printf("[probe] gipa=%p create=%p enumerate=%p\n", get_instance_proc, create_instance,
           enumerate_extensions);
    if (!get_instance_proc || !create_instance || !enumerate_extensions) return 2;

    const VkApplicationInfo app_info = {
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "uDroid Android Vulkan loader probe",
        .apiVersion = VK_API_VERSION_1_1,
    };
    const VkInstanceCreateInfo create_info = {
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &app_info,
    };
    VkInstance instance = VK_NULL_HANDLE;
    VkResult result = create_instance(&create_info, NULL, &instance);
    printf("[probe] vkCreateInstance=%d instance=%p\n", result, instance);
    if (result != VK_SUCCESS) return 3;

    PFN_vkDestroyInstance destroy_instance =
        (PFN_vkDestroyInstance)get_instance_proc(instance, "vkDestroyInstance");
    if (!destroy_instance) return 4;
    destroy_instance(instance, NULL);
    return 0;
}
