#ifndef LOCALCORE_RUNTIME_API_H
#define LOCALCORE_RUNTIME_API_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#if defined(__cplusplus)
extern "C" {
#endif

#define LOCALCORE_RUNTIME_ABI_VERSION 1u

typedef struct lc_chat_message {
    const char * role;
    const char * content;
} lc_chat_message;

typedef struct lc_load_params {
    uint32_t struct_size;
    uint32_t context_size;
    uint32_t batch_size;
    int32_t threads;
    int32_t gpu_layers;
} lc_load_params;

typedef struct lc_generation_params {
    uint32_t struct_size;
    int32_t max_tokens;
    float temperature;
    float top_p;
    int32_t top_k;
    uint32_t seed;
    const char * const * stop;
    size_t stop_count;
} lc_generation_params;

typedef bool (*lc_token_callback)(const char * utf8, size_t length, void * user_data);
typedef void (*lc_log_callback)(int level, const char * message, void * user_data);

typedef struct lc_runtime_api_v1 {
    uint32_t struct_size;
    uint32_t abi_version;
    const char * (*runtime_version)(void);
    void * (*create)(lc_log_callback callback, void * user_data);
    void (*destroy)(void * runtime);
    const char * (*last_error)(void * runtime);
    int32_t (*load_model)(void * runtime, const char * model_path, const lc_load_params * params);
    void (*unload_model)(void * runtime);
    int32_t (*apply_chat_template)(void * runtime, const char * jinja_template,
            const lc_chat_message * messages, size_t message_count, bool add_assistant,
            char * output, int32_t output_size);
    int32_t (*token_count)(void * runtime, const char * utf8);
    int32_t (*generate)(void * runtime, const char * prompt,
            const lc_generation_params * params, lc_token_callback callback, void * user_data);
    void (*cancel)(void * runtime);
} lc_runtime_api_v1;

typedef const lc_runtime_api_v1 * (*lc_get_runtime_api_v1_fn)(void);

#if defined(_WIN32)
#define LOCALCORE_RUNTIME_EXPORT __declspec(dllexport)
#else
#define LOCALCORE_RUNTIME_EXPORT __attribute__((visibility("default")))
#endif

LOCALCORE_RUNTIME_EXPORT const lc_runtime_api_v1 * localcore_runtime_api_v1(void);

#if defined(__cplusplus)
}
#endif

#endif

