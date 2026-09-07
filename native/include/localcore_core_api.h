#ifndef LOCALCORE_CORE_API_H
#define LOCALCORE_CORE_API_H

#include <stddef.h>
#include <stdint.h>

#if defined(__GNUC__)
#define LOCALCORE_EXPORT __attribute__((visibility("default")))
#else
#define LOCALCORE_EXPORT
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define LOCALCORE_CORE_ABI_VERSION 1u

typedef int (*localcore_token_callback)(const char * utf8, size_t size, void * user_data);

// Progress phases: "image" (image chunks done of total), "context" (prompt tokens done of total).
// total <= 0 means indeterminate. Additive API: ABI stays 1, old loaders keep using infer.
typedef void (*localcore_progress_callback)(const char * phase, int32_t done, int32_t total, void * user_data);

LOCALCORE_EXPORT uint32_t localcore_core_abi_version(void);
LOCALCORE_EXPORT const char * localcore_core_version(void);
LOCALCORE_EXPORT void * localcore_core_create(char ** error);
LOCALCORE_EXPORT void localcore_core_destroy(void * instance);
LOCALCORE_EXPORT int localcore_core_load_model(
        void * instance, const char * request_json, char ** result_json, char ** error);
LOCALCORE_EXPORT int localcore_core_unload_model(void * instance, char ** error);
LOCALCORE_EXPORT int localcore_core_infer(
        void * instance,
        const char * request_json,
        localcore_token_callback callback,
        void * user_data,
        char ** result_json,
        char ** error);
LOCALCORE_EXPORT int localcore_core_infer2(
        void * instance,
        const char * request_json,
        localcore_token_callback token_callback,
        void * token_user_data,
        localcore_progress_callback progress_callback,
        void * progress_user_data,
        char ** result_json,
        char ** error);
LOCALCORE_EXPORT void localcore_core_cancel(void * instance);
LOCALCORE_EXPORT void localcore_core_free_string(char * value);

#ifdef __cplusplus
}
#endif

#endif
