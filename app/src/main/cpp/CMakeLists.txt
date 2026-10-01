cmake_minimum_required(VERSION 3.22.1)
project(opencode_term C)

add_library(opencode_term SHARED NativeTerminalJNI.c)

target_include_directories(opencode_term PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(opencode_term
    log
    android
)

target_compile_options(opencode_term PRIVATE
    -Wall
    -Wextra
    -fvisibility=hidden
    -Os
)
