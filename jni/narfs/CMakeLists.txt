cmake_minimum_required(VERSION 3.6)
project(narfs_jni_candidate LANGUAGES C)

option(NANIDROID_BUILD_NARFS_JNI_CANDIDATE "Build private JNI candidate" OFF)
add_library(narfs_core STATIC narfs_core.c)
target_include_directories(narfs_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(narfs_core PRIVATE -std=c99 -Wall -Wextra -Werror)

if(NANIDROID_BUILD_NARFS_JNI_CANDIDATE)
  add_library(narfs SHARED narfs_jni.c narfs_utf.c)
  target_compile_options(
    narfs PRIVATE -std=c99 -Wall -Wextra -Werror -fvisibility=hidden)
  target_link_libraries(narfs narfs_core)
  set_target_properties(
    narfs PROPERTIES LINKER_LANGUAGE C LINK_FLAGS
    "-Wl,--as-needed -Wl,--no-undefined -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/narfs_jni.map")
endif()
