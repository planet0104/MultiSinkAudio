#
# multisink-alsa 的 CMake 配置
#
# 同时编译：
#   1) tinyalsa (静态库)         —— 来自 google/tinyalsa 的 src/*.c
#   2) libmultisinkalsa.so (JNI) —— 暴露给 Java 的 nativeOpen/Write/Close 接口
#

cmake_minimum_required(VERSION 3.18.1)
project(multisinkalsa LANGUAGES C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------- tinyalsa ----------
# 把 google/tinyalsa 的源码作为静态库直接编进 .so，
# 不依赖系统的 libtinyalsa.so（Android NDK 不暴露这个）。
add_library(tinyalsa STATIC
        tinyalsa/src/pcm.c
        tinyalsa/src/pcm_hw.c
        tinyalsa/src/pcm_plugin.c
        tinyalsa/src/snd_card_plugin.c
        tinyalsa/src/mixer.c
        tinyalsa/src/mixer_hw.c
        tinyalsa/src/mixer_plugin.c
        tinyalsa/src/limits.c)

target_include_directories(tinyalsa PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/tinyalsa/include)

target_compile_definitions(tinyalsa
        PUBLIC
        _POSIX_C_SOURCE=200809L)
#       PRIVATE
#       TINYALSA_USES_PLUGINS)
# 注意：故意不开 TINYALSA_USES_PLUGINS。
#   开了之后 tinyalsa 会在 hw_open 失败时悄悄走 plugin 模式，
#   把真正的 errno（如 EACCES / ENOENT）淹没在 "no device (hw/plugin)" 这种
#   含糊错误里，调起来非常折腾。
#   Android 上也根本没有 alsa-lib 的 plugin 配置，开了也没用。

# tinyalsa 的代码里有不少未使用参数 / 类型转换警告，
# 不希望它们因 -Werror 把工程编挂掉。
target_compile_options(tinyalsa PRIVATE
        -Wno-unused-parameter
        -Wno-unused-result
        -Wno-incompatible-pointer-types
        -Wno-sign-compare
        -Wno-implicit-fallthrough
        -Wno-pointer-sign)

# ---------- libmultisinkalsa.so ----------
add_library(multisinkalsa SHARED
        multisinkalsa_jni.cpp)

target_link_libraries(multisinkalsa
        PRIVATE tinyalsa
        PRIVATE log
        PRIVATE dl
        PRIVATE android)
