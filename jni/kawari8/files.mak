#
# KAWARI Source Files
#

KISSRC =   kis/kis_echo.cpp \
           kis/kis_dict.cpp \
           kis/kis_date.cpp \
           kis/kis_counter.cpp \
           kis/kis_file.cpp \
           kis/kis_escape.cpp \
           kis/kis_urllist.cpp \
           kis/kis_substitute.cpp \
           kis/kis_split.cpp \
           kis/kis_communicate.cpp \
           kis/kis_xargs.cpp \
           kis/kis_string.cpp \
           kis/kis_help.cpp \
           kis/kis_saori.cpp \
           kis/kis_system.cpp

CRYPTSRC = libkawari/kawari_crypt.cpp \
           misc/base64.cpp

CORESRC =  libkawari/kawari_engine.cpp \
           libkawari/kawari_ns.cpp \
           libkawari/kawari_dict.cpp \
           libkawari/kawari_code.cpp \
           libkawari/kawari_codeset.cpp \
           libkawari/kawari_codeexpr.cpp \
           libkawari/kawari_codekis.cpp \
           libkawari/kawari_vm.cpp \
           libkawari/kawari_lexer.cpp \
           libkawari/kawari_compiler.cpp \
           libkawari/kawari_log.cpp \
           libkawari/kawari_rc.cpp \
           misc/misc.cpp \
           misc/mt19937ar.cpp \
           misc/l10n.cpp \
           misc/phttp.cpp \
           saori/saori.cpp \
           saori/saori_module.cpp \
           saori/saori_unique.cpp \
           $(KISSRC) \
           $(CRYPTSRC)

SHIOSRC =  shiori/kawari_shiori.cpp \
           shiori/shiori_object.cpp \
           shiori/shiori.cpp

KOSUISRC = tool/kosui.cpp

TOOLSRC =  tool/kawari_encode.cpp \
           tool/kawari_encode2.cpp \
           tool/kawari_decode2.cpp

#==========================================================================
# Platform dependant sources
#==========================================================================

CORESRC_JAVA = saori/saori_java.cpp

CORESRC_PYTHON = saori/saori_python.cpp

CORESRC_NATIVE = saori/saori_native.cpp

SHIOSRC_PYTHON = shiori/py_shiori.cpp

KOSUISRC_cygwin = tool/kosui_dsstp.cpp \
                  tool/kdb.cpp

KOSUISRC_mingw = tool/kosui_dsstp.cpp \
                 tool/kdb.cpp

