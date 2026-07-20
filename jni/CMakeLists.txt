cmake_minimum_required(VERSION 3.6)
project(nanidroid_native LANGUAGES C CXX)

# Preserve the toolchain facts selected by the frozen Android toolchain file in
# CMakeCache.txt. CMAKE_CXX_COMPILER itself is not a public cache entry in this
# CMake/NDK pairing, so the parity inspector reads these internal provenance
# entries rather than trusting a command-line label.
set(NANIDROID_CXX_COMPILER "${CMAKE_CXX_COMPILER}" CACHE INTERNAL "")
set(NANIDROID_CXX_COMPILER_ID "${CMAKE_CXX_COMPILER_ID}" CACHE INTERNAL "")
set(NANIDROID_CXX_COMPILER_VERSION "${CMAKE_CXX_COMPILER_VERSION}" CACHE INTERNAL "")

# These declarations intentionally mirror the effective Android.mk contract.
# tools/inspect_native_contract.py normalizes both build descriptions and fails
# before compilation if a source, definition, flag, include, library, or module
# name drifts.
set(NANIDROID_KAWARI8_MODULE kawari8)
set(NANIDROID_KAWARI8_SOURCES
  kawari8/libkawari/kawari_engine.cpp
  kawari8/libkawari/kawari_ns.cpp
  kawari8/libkawari/kawari_dict.cpp
  kawari8/libkawari/kawari_code.cpp
  kawari8/libkawari/kawari_codeset.cpp
  kawari8/libkawari/kawari_codeexpr.cpp
  kawari8/libkawari/kawari_codekis.cpp
  kawari8/libkawari/kawari_vm.cpp
  kawari8/libkawari/kawari_lexer.cpp
  kawari8/libkawari/kawari_compiler.cpp
  kawari8/libkawari/kawari_log.cpp
  kawari8/libkawari/kawari_rc.cpp
  kawari8/misc/misc.cpp
  kawari8/misc/mt19937ar.cpp
  kawari8/misc/l10n.cpp
  kawari8/misc/phttp.cpp
  kawari8/saori/saori.cpp
  kawari8/saori/saori_module.cpp
  kawari8/saori/saori_unique.cpp
  kawari8/kis/kis_echo.cpp
  kawari8/kis/kis_dict.cpp
  kawari8/kis/kis_date.cpp
  kawari8/kis/kis_counter.cpp
  kawari8/kis/kis_file.cpp
  kawari8/kis/kis_escape.cpp
  kawari8/kis/kis_urllist.cpp
  kawari8/kis/kis_substitute.cpp
  kawari8/kis/kis_split.cpp
  kawari8/kis/kis_communicate.cpp
  kawari8/kis/kis_xargs.cpp
  kawari8/kis/kis_string.cpp
  kawari8/kis/kis_help.cpp
  kawari8/kis/kis_saori.cpp
  kawari8/kis/kis_system.cpp
  kawari8/libkawari/kawari_crypt.cpp
  kawari8/misc/base64.cpp
  kawari8/shiori/kawari_shiori.cpp
  kawari8/shiori/shiori_object.cpp
  kawari8/shiori/shiori.cpp
  kawari8/kawari_jni.cpp
)
set(NANIDROID_KAWARI8_DEFINITIONS POSIX NDEBUG)
set(NANIDROID_KAWARI8_FLAGS
  -frtti
  -fexceptions
  -fpermissive
  -Wno-write-strings
  -Os
)
set(NANIDROID_KAWARI8_INCLUDES
  kawari8
)
set(NANIDROID_KAWARI8_LINK_LIBRARIES log dl)

add_library(${NANIDROID_KAWARI8_MODULE} SHARED ${NANIDROID_KAWARI8_SOURCES})
target_include_directories(${NANIDROID_KAWARI8_MODULE} PRIVATE ${NANIDROID_KAWARI8_INCLUDES})
target_compile_definitions(${NANIDROID_KAWARI8_MODULE} PRIVATE ${NANIDROID_KAWARI8_DEFINITIONS})
target_compile_options(${NANIDROID_KAWARI8_MODULE} PRIVATE ${NANIDROID_KAWARI8_FLAGS})
target_link_libraries(${NANIDROID_KAWARI8_MODULE} ${NANIDROID_KAWARI8_LINK_LIBRARIES})
set_target_properties(${NANIDROID_KAWARI8_MODULE} PROPERTIES DEFINE_SYMBOL "")

set(NANIDROID_SATORIYA_MODULE satoriya)
set(NANIDROID_SATORIYA_SOURCES
  _/Sender.cpp
  _/Utilities.cpp
  _/calc.cpp
  _/stltool.cpp
  satori/SakuraCS.cpp
  satori/SakuraClient.cpp
  satori/SakuraDLLClient.cpp
  satori/SakuraDLLHost.cpp
  satori/SaoriClient.cpp
  satori/satori.cpp
  satori/satoriTranslate.cpp
  satori/satori_AnalyzeRequest.cpp
  satori/satori_CreateResponce.cpp
  satori/satori_EventOperation.cpp
  satori/satori_Kakko.cpp
  satori/satori_load_dict.cpp
  satori/satori_load_unload.cpp
  satori/satori_sentence.cpp
  satori/satori_tool.cpp
  satori/shiori_plugin.cpp
  satori/satori_jni.cpp
)
set(NANIDROID_SATORIYA_DEFINITIONS POSIX SATORI_DLL NDEBUG)
set(NANIDROID_SATORIYA_FLAGS
  -frtti
  -fexceptions
  -fpermissive
  -g
  -ggdb
  -O0
)
set(NANIDROID_SATORIYA_INCLUDES
  satori
  _
)
set(NANIDROID_SATORIYA_LINK_LIBRARIES log dl)

add_library(${NANIDROID_SATORIYA_MODULE} SHARED ${NANIDROID_SATORIYA_SOURCES})
target_include_directories(${NANIDROID_SATORIYA_MODULE} PRIVATE ${NANIDROID_SATORIYA_INCLUDES})
target_compile_definitions(${NANIDROID_SATORIYA_MODULE} PRIVATE ${NANIDROID_SATORIYA_DEFINITIONS})
target_compile_options(${NANIDROID_SATORIYA_MODULE} PRIVATE ${NANIDROID_SATORIYA_FLAGS})
target_link_libraries(${NANIDROID_SATORIYA_MODULE} ${NANIDROID_SATORIYA_LINK_LIBRARIES})
set_target_properties(${NANIDROID_SATORIYA_MODULE} PROPERTIES DEFINE_SYMBOL "")
