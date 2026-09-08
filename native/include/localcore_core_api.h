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

// Progress phases: "context" (text prefill), "image" (visual encoding),
// "image_context" (image embedding prefill). Each is accumulated over this request.
// .7 reports done/total as token-weighted compute-node completion (basis points),
// not completed-token counts or elapsed-time estimates. *_prepare has total=0.
// Progress phases: "context" (text prefill), "image" (visual encoding),
// "image_context" (image embedding prefill). Each is accumulated over this request.
// .7 reports done/total as token-weighted compute-node completion (basis points),
// not completed-token counts or elapsed-time estimates. *_prepare has total=0.
// ABI remains 1: the callback signature and the infer2 symbol are unchanged.
typedef void (*localcore_progress_callback)(const char * phase, int32_t done, int32_t total, void * user_data);

// v2 progress: real completed/total TOKENS plus this phase's accumulated
// execution milliseconds (clock pauses while other phases run). Additive API:
// ABI stays 1; loaders that only know infer/infer2 keep working unchanged.
typedef void (*localcore_progress_callback2)(const char * phase, int32_t done_tokens, int32_t total_tokens,
        int64_t elapsed_ms, void * user_data);

LOCALCORE_EXPORT uint32_t localcore_core_abi_version(void);
LOCALCORE_EXPORT const char * localcore_core_version(void);
LOCALCORE_EXPORT void * localcore_core_create(char ** error);
LOCALCORE_EXPORT void localcore_core_destroy(void * instance);
LOCALCORE_EXPORT int localcore_core_load_model(
        void * instance, const char * request_json, char ** result_json, char ** error);
// Additive ABI 1 capability. Simulates allocations without replacing the loaded model.
// Returns model/context/compute/mmproj/total bytes and the effective context parameters.
LOCALCORE_EXPORT int localcore_core_estimate_memory(
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
LOCALCORE_EXPORT int localcore_core_infer3(
        void * instance,
        const char * request_json,
        localcore_token_callback token_callback,
        void * token_user_data,
        localcore_progress_callback2 progress_callback,
        void * progress_user_data,
        char ** result_json,
        char ** error);
// Additive ABI 1: callback contains UTF-8 JSON assistant deltas (content,
// reasoning_content, indexed tool_calls). Result includes finishReason.
// Return codes: 0 success, 1 core failure, 2 invalid request, 3 cancellation.
// Existing infer/infer2/infer3 retain plain content callbacks.
LOCALCORE_EXPORT int localcore_core_infer4(
        void * instance, const char * request_json,
        localcore_token_callback event_callback, void * event_user_data,
        localcore_progress_callback2 progress_callback, void * progress_user_data,
        char ** result_json, char ** error);
LOCALCORE_EXPORT void localcore_core_cancel(void * instance);
LOCALCORE_EXPORT void localcore_core_free_string(char * value);

#ifdef __cplusplus
}
#endif

#endif
