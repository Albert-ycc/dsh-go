cmake_minimum_required(VERSION 3.22.1)
project(zytprobe C)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O1 -Wall -Wno-unused-function")

add_library(zytprobe SHARED jni.c common.c)
target_link_libraries(zytprobe log)

# 可执行文件伪装成 lib*.so 才会被 AGP 打进 nativeLibraryDir
add_executable(probeexec probeexec.c common.c)
set_target_properties(probeexec PROPERTIES PREFIX "lib" SUFFIX ".so" OUTPUT_NAME "probeexec")
