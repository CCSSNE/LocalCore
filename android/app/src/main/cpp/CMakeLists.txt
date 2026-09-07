cmake_minimum_required(VERSION 3.22.1)
project(localcore_loader LANGUAGES CXX)

add_library(localcore_loader SHARED localcore_loader.cpp)
target_compile_features(localcore_loader PRIVATE cxx_std_17)
target_compile_options(localcore_loader PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_include_directories(localcore_loader PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../native/include)
target_link_libraries(localcore_loader PRIVATE android log dl)
