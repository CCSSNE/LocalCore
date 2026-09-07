cmake_minimum_required(VERSION 3.22.1)
project(localcore_host LANGUAGES CXX)

add_library(localcore_host SHARED native_bridge.cpp)
target_compile_features(localcore_host PRIVATE cxx_std_17)
target_compile_options(localcore_host PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_include_directories(localcore_host PRIVATE ../../../../../native-api)
target_link_libraries(localcore_host PRIVATE android log dl)

