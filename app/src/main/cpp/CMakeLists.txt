cmake_minimum_required(VERSION 3.22.1)
project("bandwidth_native")

add_library(
    bandwidth_native
    SHARED
    session_shaper.cpp
    session_shaper_jni.cpp
)

target_compile_options(bandwidth_native PRIVATE -Wall -Wextra -O2)

find_library(log-lib log)
target_link_libraries(bandwidth_native ${log-lib})
