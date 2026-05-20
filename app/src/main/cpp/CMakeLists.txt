cmake_minimum_required(VERSION 3.22.1)

project("nanidroid-native")

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Locate Android logging and dynamic linking libraries
find_library(log-lib log)

# ==========================================
# 1. compile kawari8 shared library
# ==========================================

set(KAWARI_KIS_SRC
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
)

set(KAWARI_CRYPT_SRC
    kawari8/libkawari/kawari_crypt.cpp
    kawari8/misc/base64.cpp
)

set(KAWARI_CORE_SRC
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
    ${KAWARI_KIS_SRC}
    ${KAWARI_CRYPT_SRC}
)

set(KAWARI_SHIO_SRC
    kawari8/shiori/kawari_shiori.cpp
    kawari8/shiori/shiori_object.cpp
    kawari8/shiori/shiori.cpp
)

add_library(kawari8 SHARED
    ${KAWARI_CORE_SRC}
    ${KAWARI_SHIO_SRC}
    kawari8/kawari_jni.cpp
)

target_include_directories(kawari8 PRIVATE
    kawari8
    kawari8/include
    kawari8/libkawari
    kawari8/kis
    kawari8/misc
    kawari8/saori
    kawari8/shiori
)

target_compile_definitions(kawari8 PRIVATE
    POSIX
    NDEBUG
)

target_compile_options(kawari8 PRIVATE
    -Wno-write-strings
    -fpermissive
)

target_link_libraries(kawari8
    ${log-lib}
    dl
)

# ==========================================
# 2. compile satoriya shared library
# ==========================================

set(SATORI_COMMON_SRC
    _/Sender.cpp
    _/Utilities.cpp
    _/calc.cpp
    _/stltool.cpp
)

add_library(satoriya SHARED
    ${SATORI_COMMON_SRC}
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

target_include_directories(satoriya PRIVATE
    satori
    _
)

target_compile_definitions(satoriya PRIVATE
    POSIX
    SATORI_DLL
)

target_compile_options(satoriya PRIVATE
    -fpermissive
)

target_link_libraries(satoriya
    ${log-lib}
    dl
)
