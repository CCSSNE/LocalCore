cmake_minimum_required(VERSION 3.22.1)
project(localcore_core LANGUAGES C CXX)

if(NOT DEFINED LLAMA_CPP_DIR)
    message(FATAL_ERROR "LLAMA_CPP_DIR must point to a llama.cpp checkout")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_LLGUIDANCE OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_CPP_DIR} llama.cpp-build EXCLUDE_FROM_ALL)

add_library(localcore_core SHARED localcore_core.cpp)
target_compile_features(localcore_core PRIVATE cxx_std_17)
target_compile_options(localcore_core PRIVATE -Wall -Wextra -fvisibility=hidden)
target_include_directories(localcore_core PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../include
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/common
    ${LLAMA_CPP_DIR}/tools/mtmd)
target_link_libraries(localcore_core PRIVATE llama-common mtmd)
target_link_options(localcore_core PRIVATE "LINKER:--exclude-libs,ALL")
target_compile_definitions(localcore_core PRIVATE
    LOCALCORE_CORE_VERSION="${LOCALCORE_CORE_VERSION}"
    LOCALCORE_LLAMA_VERSION="${LOCALCORE_LLAMA_VERSION}")
set_target_properties(localcore_core PROPERTIES
    OUTPUT_NAME localcore_core
    NO_SONAME OFF
    SOVERSION "${LOCALCORE_CORE_ABI_VERSION}")
