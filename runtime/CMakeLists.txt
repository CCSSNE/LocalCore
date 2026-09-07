cmake_minimum_required(VERSION 3.22.1)
project(localcore_runtime LANGUAGES C CXX)

if(NOT DEFINED LLAMA_CPP_SOURCE_DIR)
    message(FATAL_ERROR "LLAMA_CPP_SOURCE_DIR must point to the pinned llama.cpp checkout")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_CPP_SOURCE_DIR}" llama.cpp EXCLUDE_FROM_ALL)

add_library(localcore_runtime SHARED src/runtime.cpp)
target_compile_features(localcore_runtime PRIVATE cxx_std_17)
target_compile_definitions(localcore_runtime PRIVATE LOCALCORE_RUNTIME_VERSION="llama-b10256-lc1")
target_compile_options(localcore_runtime PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_include_directories(localcore_runtime PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/../native-api"
)
target_include_directories(localcore_runtime SYSTEM PRIVATE
    "${LLAMA_CPP_SOURCE_DIR}/include"
    "${LLAMA_CPP_SOURCE_DIR}/common"
    "${LLAMA_CPP_SOURCE_DIR}/vendor"
)
target_link_libraries(localcore_runtime PRIVATE common llama android log)
set_target_properties(localcore_runtime PROPERTIES
    OUTPUT_NAME "localcore_runtime"
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)
