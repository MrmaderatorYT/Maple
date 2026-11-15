cmake_minimum_required(VERSION 3.22.1)
project(maple C)

add_library(maple SHARED maple_jni.c)
target_compile_options(maple PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(maple jnigraphics log)
target_link_options(maple PRIVATE -Wl,-z,max-page-size=16384)
