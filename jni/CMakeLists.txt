cmake_minimum_required(VERSION 3.22.1)
project(nanidroid_native LANGUAGES C CXX)

# Satori is a retained native SHIORI engine.  It is built as a regular shared
# library (rather than being loaded from a ghost directory) so Android's
# linker namespace and AGP can package it for every supported ABI.
add_library(satoriya SHARED
    _/Sender.cpp
    _/Utilities.cpp
    _/calc.cpp
    _/calc_float.cpp
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

add_library(ssu SHARED
    _/Sender.cpp
    _/Utilities.cpp
    _/calc.cpp
    _/calc_float.cpp
    _/stltool.cpp
    satori/SakuraCS.cpp
    satori/SakuraDLLHost.cpp
    satori/SaoriHost.cpp
    satori/ssu.cpp
    satori/ssu_anchor.cpp
)

# This source predates the C++11 standard library names `std::byte` and
# `std::stoi`, which conflict with its compatibility helpers. Keep the engine
# on its original language level; the Android JNI ABI does not require newer
# C++ features here.
set_target_properties(satoriya PROPERTIES CXX_STANDARD 98 CXX_STANDARD_REQUIRED YES)
set_target_properties(ssu PROPERTIES CXX_STANDARD 98 CXX_STANDARD_REQUIRED YES)
target_compile_options(satoriya PRIVATE
    "-include${CMAKE_CURRENT_SOURCE_DIR}/satori_compat.h"
)
target_compile_options(ssu PRIVATE
    "-include${CMAKE_CURRENT_SOURCE_DIR}/satori_compat.h"
)
target_compile_definitions(satoriya PRIVATE POSIX SATORI_DLL)
target_compile_definitions(ssu PRIVATE POSIX SATORI_DLL)
target_include_directories(satoriya PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/_
    ${CMAKE_CURRENT_SOURCE_DIR}/satori
)
target_include_directories(ssu PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/_
    ${CMAKE_CURRENT_SOURCE_DIR}/satori
)
target_link_libraries(ssu PRIVATE android log)
target_link_libraries(satoriya PRIVATE android log dl ssu)
# Both DSOs retain SakuraDLLHost's legacy C ABI.  Bind internal references so
# libssu cannot interpose on Satori's host singleton (or vice versa).
target_link_options(satoriya PRIVATE "-Wl,-Bsymbolic")
target_link_options(ssu PRIVATE "-Wl,-Bsymbolic")

# Kawari 8 was also maintained as a POSIX Android port.  Keep its original
# source selection (the old Android.mk list) rather than compiling its tools
# and optional Java/Python SAORI adapters.
add_library(kawari8 SHARED
    kawari8/kawari_jni.cpp
    kawari8/kis/kis_echo.cpp kawari8/kis/kis_dict.cpp kawari8/kis/kis_date.cpp
    kawari8/kis/kis_counter.cpp kawari8/kis/kis_file.cpp kawari8/kis/kis_escape.cpp
    kawari8/kis/kis_urllist.cpp kawari8/kis/kis_substitute.cpp kawari8/kis/kis_split.cpp
    kawari8/kis/kis_communicate.cpp kawari8/kis/kis_xargs.cpp kawari8/kis/kis_string.cpp
    kawari8/kis/kis_help.cpp kawari8/kis/kis_saori.cpp kawari8/kis/kis_system.cpp
    kawari8/libkawari/kawari_crypt.cpp kawari8/misc/base64.cpp
    kawari8/libkawari/kawari_engine.cpp kawari8/libkawari/kawari_ns.cpp
    kawari8/libkawari/kawari_dict.cpp kawari8/libkawari/kawari_code.cpp
    kawari8/libkawari/kawari_codeset.cpp kawari8/libkawari/kawari_codeexpr.cpp
    kawari8/libkawari/kawari_codekis.cpp kawari8/libkawari/kawari_vm.cpp
    kawari8/libkawari/kawari_lexer.cpp kawari8/libkawari/kawari_compiler.cpp
    kawari8/libkawari/kawari_log.cpp kawari8/libkawari/kawari_rc.cpp
    kawari8/misc/misc.cpp kawari8/misc/mt19937ar.cpp kawari8/misc/l10n.cpp kawari8/misc/phttp.cpp
    kawari8/saori/saori.cpp kawari8/saori/saori_module.cpp kawari8/saori/saori_unique.cpp
    kawari8/shiori/kawari_shiori.cpp kawari8/shiori/shiori_object.cpp kawari8/shiori/shiori.cpp
)
set_target_properties(kawari8 PROPERTIES CXX_STANDARD 98 CXX_STANDARD_REQUIRED YES)
target_compile_definitions(kawari8 PRIVATE POSIX NDEBUG)
target_compile_options(kawari8 PRIVATE -Wno-write-strings)
target_include_directories(kawari8 PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/kawari8 ${CMAKE_CURRENT_SOURCE_DIR}/kawari8/include
    ${CMAKE_CURRENT_SOURCE_DIR}/kawari8/libkawari ${CMAKE_CURRENT_SOURCE_DIR}/kawari8/kis
    ${CMAKE_CURRENT_SOURCE_DIR}/kawari8/misc ${CMAKE_CURRENT_SOURCE_DIR}/kawari8/saori
    ${CMAKE_CURRENT_SOURCE_DIR}/kawari8/shiori
)
target_link_libraries(kawari8 PRIVATE android log dl)

# YAYA is maintained upstream as a POSIX-capable C++ SHIORI implementation.
# All root-level sources form the engine; Windows-only code is conditional.
file(GLOB YAYA_SOURCES CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/yaya/*.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/yaya/*.c"
)
list(REMOVE_ITEM YAYA_SOURCES
    "${CMAKE_CURRENT_SOURCE_DIR}/yaya/stdafx.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/yaya/aya_profile.cpp"
)
add_library(yaya SHARED ${YAYA_SOURCES})
set_target_properties(yaya PROPERTIES CXX_STANDARD 14 CXX_STANDARD_REQUIRED YES)
target_compile_definitions(yaya PRIVATE POSIX)
target_include_directories(yaya PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/yaya)
target_link_libraries(yaya PRIVATE android log dl ssu)
target_link_options(yaya PRIVATE "-Wl,-Bsymbolic")
