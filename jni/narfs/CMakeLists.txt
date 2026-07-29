cmake_minimum_required(VERSION 3.6)
project(narfs_jni_candidate LANGUAGES C)

option(NANIDROID_BUILD_NARFS_JNI_CANDIDATE "Build private JNI candidate" OFF)
option(NANIDROID_BUILD_NARFS_FULL_JNI_CANDIDATE
  "Build private full-profile JNI candidate" OFF)
if(NANIDROID_BUILD_NARFS_JNI_CANDIDATE
    AND NANIDROID_BUILD_NARFS_FULL_JNI_CANDIDATE)
  message(FATAL_ERROR "full and inspector JNI candidates are exclusive")
endif()
add_library(narfs_core STATIC narfs_core.c)
target_include_directories(narfs_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(narfs_core PRIVATE -std=c99 -Wall -Wextra -Werror)

option(NANIDROID_BUILD_NARFS_SHA256_CANDIDATE "Build SHA-256 candidate" OFF)
if(NANIDROID_BUILD_NARFS_SHA256_CANDIDATE)
  add_subdirectory(sha256)
endif()

option(NANIDROID_BUILD_NARFS_STAGE_CANDIDATE "Build staging candidate" OFF)
if(NANIDROID_BUILD_NARFS_STAGE_CANDIDATE)
  if(NOT TARGET narfs_sha256)
    message(FATAL_ERROR "staging candidate requires SHA-256 candidate")
  endif()
  add_subdirectory(stage)
endif()

if(NANIDROID_BUILD_NARFS_JNI_CANDIDATE)
  add_library(narfs SHARED narfs_jni.c narfs_utf.c)
  target_compile_options(
    narfs PRIVATE -std=c99 -Wall -Wextra -Werror -fvisibility=hidden)
  target_link_libraries(narfs narfs_core)
  set_target_properties(
    narfs PROPERTIES LINKER_LANGUAGE C LINK_FLAGS
    "-Wl,--as-needed -Wl,--no-undefined -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/narfs_jni.map")
endif()

if(NANIDROID_BUILD_NARFS_FULL_JNI_CANDIDATE)
  if(NOT TARGET narfs_stage)
    message(FATAL_ERROR "full JNI candidate requires staging candidate")
  endif()
  add_library(
    narfs_full SHARED narfs_jni.c narfs_stage_jni.c narfs_utf.c)
  target_compile_options(
    narfs_full PRIVATE -std=c99 -Wall -Wextra -Werror -fvisibility=hidden)
  target_link_libraries(
    narfs_full narfs_stage narfs_core narfs_sha256)
  set_target_properties(
    narfs_full PROPERTIES OUTPUT_NAME narfs LINKER_LANGUAGE C LINK_FLAGS
    "-Wl,--as-needed -Wl,--no-undefined -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/narfs_full_jni.map")
endif()
