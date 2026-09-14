# 票 04：llama.cpp NDK 编译进 Gradle。
# add_subdirectory 指向仓库根的 third_party/llama.cpp（codeload tarball 解包，见 .gitignore）。
# 相对深度：app/src/main/cpp -> repo root = 4 层（cpp→main→src→app→root）
cmake_minimum_required(VERSION 3.22.1)
project(photoledger-native)

# 作为子项目引入时 LLAMA_BUILD_COMMON 默认 OFF（跟随 LLAMA_STANDALONE），
# 而 JNI 侧用 common_tokenize/common_batch_add——显式开启；
# common 的模型下载功能要 CURL，端侧用不上，关掉（与桌面 engine-llamacpp 同款 -DLLAMA_CURL=OFF）
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
# Android 必须关 OpenMP：NDK 27 带 libomp，find_package(OpenMP) 会意外成功，
# ggml-cpu 走 OpenMP 分支后在真机 prefill 段 SIGSEGV（memcpy 崩在 mul_mat，
# 实测 2026-09-14）。llama.cpp CPU 后端原生 pthread 线程池设计，Android 上
# 官方指引也是关闭 OpenMP（whisper.cpp/llama.cpp Android 集成均如此）。
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# CPU 内核按目标 SoC 指定指令集：主目标机天玑 9000（armv8.2，支持 dotprod）。
# 不指定时 ARM 分支零优化 flags（无 NEON dotprod），0.6B 只有 0.5 tok/s（实测）。
# ALL_VARIANTS 需要 GGML_BACKEND_DL（.so 动态加载），Android 集成复杂，弃用。
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
# Android 必须关 OpenMP：NDK 27 带 libomp，find_package(OpenMP) 会意外成功，
# ggml-cpu 走 OpenMP 分支后在真机 prefill 段 SIGSEGV（memcpy 崩在 mul_mat，
# 实测 2026-09-14）。llama.cpp CPU 后端原生 pthread 线程池设计，Android 上
# 官方指引也是关闭 OpenMP（whisper.cpp/llama.cpp Android 集成均如此）。
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp llama-build)

add_library(photoledger SHARED photoledger-jni.cpp)

# common.h 在 llama.cpp/common/，其 include 路径未随 common 库 target 传导，显式补上
target_include_directories(photoledger PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp/common)

target_link_libraries(photoledger PRIVATE llama common android log)

# llama.cpp 全量源码编译警告多，关掉无关告警保持日志干净
target_compile_options(photoledger PRIVATE -Wall -Wextra)
