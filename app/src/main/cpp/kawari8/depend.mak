./config.h :

./include/shiori.h :

./include/shiori_object.h : ./include/shiori.h

./kis/kis_base.h : ./config.h ./libkawari/kawari_engine.h ./libkawari/kawari_log.h

./kis/kis_communicate.cpp : ./config.h ./kis/kis_communicate.h ./libkawari/kawari_engine.h ./libkawari/kawari_rc.h ./misc/l10n.h ./misc/misc.h

./kis/kis_communicate.h : ./config.h ./kis/kis_base.h

./kis/kis_config.h : ./kis/kis_echo.h ./kis/kis_dict.h ./kis/kis_date.h ./kis/kis_counter.h ./kis/kis_math.h ./kis/kis_file.h ./kis/kis_escape.h ./kis/kis_help.h ./kis/kis_substitute.h ./kis/kis_split.h ./kis/kis_string.h ./kis/kis_communicate.h ./kis/kis_xargs.h ./kis/kis_saori.h ./kis/kis_system.h ./kis/kis_urllist.h

./kis/kis_counter.cpp : ./config.h ./kis/kis_counter.h ./libkawari/kawari_engine.h ./libkawari/kawari_rc.h ./misc/misc.h

./kis/kis_counter.h : ./config.h ./kis/kis_base.h

./kis/kis_date.cpp : ./config.h ./kis/kis_date.h ./misc/misc.h

./kis/kis_date.h : ./config.h ./kis/kis_base.h

./kis/kis_dict.cpp : ./config.h ./kis/kis_dict.h ./libkawari/kawari_engine.h ./libkawari/kawari_rc.h ./misc/misc.h

./kis/kis_dict.h : ./config.h ./kis/kis_base.h

./kis/kis_echo.cpp : ./config.h ./kis/kis_echo.h ./misc/misc.h ./libkawari/kawari_engine.h ./libkawari/kawari_log.h

./kis/kis_echo.h : ./config.h ./kis/kis_base.h

./kis/kis_escape.cpp : ./config.h ./kis/kis_escape.h ./misc/l10n.h

./kis/kis_escape.h : ./config.h ./kis/kis_base.h

./kis/kis_file.cpp : ./config.h ./kis/kis_file.h ./libkawari/kawari_engine.h ./libkawari/kawari_rc.h ./misc/misc.h ./misc/l10n.h ./misc/_dirent.h

./kis/kis_file.h : ./kis/kis_base.h

./kis/kis_help.cpp : ./config.h ./kis/kis_help.h ./libkawari/kawari_vm.h ./libkawari/kawari_engine.h ./libkawari/kawari_version.h ./libkawari/kawari_log.h

./kis/kis_help.h : ./config.h ./kis/kis_base.h

./kis/kis_math.h : ./config.h ./misc/misc.h ./kis/kis_base.h

./kis/kis_saori.cpp : ./config.h ./kis/kis_saori.h ./libkawari/kawari_engine.h ./libkawari/kawari_log.h ./libkawari/kawari_rc.h ./misc/misc.h ./misc/phttp.h

./kis/kis_saori.h : ./config.h ./kis/kis_base.h ./misc/phttp.h

./kis/kis_split.cpp : ./config.h ./kis/kis_split.h ./libkawari/kawari_engine.h ./misc/l10n.h

./kis/kis_split.h : ./config.h ./kis/kis_base.h

./kis/kis_string.cpp : ./config.h ./kis/kis_string.h ./libkawari/kawari_engine.h ./misc/misc.h ./misc/l10n.h

./kis/kis_string.h : ./kis/kis_base.h

./kis/kis_substitute.cpp : ./config.h ./kis/kis_substitute.h ./misc/l10n.h

./kis/kis_substitute.h : ./config.h ./kis/kis_base.h

./kis/kis_system.cpp : ./config.h ./kis/kis_system.h ./libkawari/kawari_engine.h ./libkawari/kawari_log.h ./misc/misc.h

./kis/kis_system.h : ./config.h ./kis/kis_base.h

./kis/kis_urllist.cpp : ./config.h ./kis/kis_urllist.h

./kis/kis_urllist.h : ./config.h ./kis/kis_base.h

./kis/kis_xargs.cpp : ./config.h ./kis/kis_xargs.h ./libkawari/kawari_rc.h ./libkawari/kawari_engine.h

./kis/kis_xargs.h : ./config.h ./kis/kis_base.h

./libkawari/kawari_code.cpp : ./config.h ./libkawari/kawari_code.h ./libkawari/kawari_codeexpr.h ./libkawari/kawari_vm.h ./libkawari/kawari_engine.h ./misc/l10n.h ./misc/misc.h

./libkawari/kawari_code.h :

./libkawari/kawari_codeexpr.cpp : ./config.h ./libkawari/kawari_codeexpr.h ./libkawari/kawari_engine.h ./libkawari/kawari_vm.h ./libkawari/kawari_log.h ./libkawari/kawari_rc.h ./misc/l10n.h ./misc/misc.h

./libkawari/kawari_codeexpr.h : ./libkawari/kawari_code.h

./libkawari/kawari_codekis.cpp : ./config.h ./libkawari/kawari_vm.h ./libkawari/kawari_codekis.h ./misc/misc.h

./libkawari/kawari_codekis.h : ./libkawari/kawari_code.h

./libkawari/kawari_codeset.cpp : ./config.h ./libkawari/kawari_codeset.h ./libkawari/kawari_engine.h ./libkawari/kawari_vm.h ./misc/misc.h

./libkawari/kawari_codeset.h : ./libkawari/kawari_code.h ./libkawari/kawari_dict.h

./libkawari/kawari_compiler.cpp : ./config.h ./libkawari/kawari_compiler.h ./libkawari/kawari_lexer.h ./libkawari/kawari_code.h ./libkawari/kawari_codeexpr.h ./libkawari/kawari_codeset.h ./libkawari/kawari_codekis.h ./libkawari/kawari_dict.h ./libkawari/kawari_log.h ./libkawari/kawari_rc.h ./misc/misc.h

./libkawari/kawari_compiler.h :

./libkawari/kawari_crypt.cpp : ./config.h ./libkawari/kawari_crypt.h ./misc/base64.h

./libkawari/kawari_crypt.h : ./config.h

./libkawari/kawari_dict.cpp : ./config.h ./libkawari/kawari_dict.h ./libkawari/kawari_compiler.h ./libkawari/kawari_codeset.h ./libkawari/kawari_log.h ./libkawari/kawari_rc.h ./misc/misc.h

./libkawari/kawari_dict.h : ./config.h ./libkawari/kawari_ns.h ./libkawari/kawari_code.h ./libkawari/wordcollection.h

./libkawari/kawari_engine.cpp : ./config.h ./libkawari/kawari_engine.h ./libkawari/kawari_dict.h ./libkawari/kawari_compiler.h ./libkawari/kawari_vm.h ./libkawari/kawari_crypt.h ./libkawari/kawari_codeset.h ./libkawari/kawari_rc.h ./misc/misc.h ./saori/saori.h

./libkawari/kawari_engine.h : ./config.h ./libkawari/kawari_compiler.h ./libkawari/kawari_dict.h ./libkawari/kawari_code.h ./libkawari/kawari_vm.h ./libkawari/kawari_log.h ./saori/saori.h

./libkawari/kawari_lexer.cpp : ./config.h ./libkawari/kawari_lexer.h ./misc/misc.h ./misc/l10n.h ./libkawari/kawari_crypt.h ./libkawari/kawari_log.h ./libkawari/kawari_rc.h

./libkawari/kawari_lexer.h : ./config.h ./libkawari/kawari_log.h

./libkawari/kawari_log.cpp : ./libkawari/kawari_log.h

./libkawari/kawari_log.h : ./config.h

./libkawari/kawari_ns.cpp : ./config.h ./libkawari/kawari_ns.h ./libkawari/kawari_log.h ./misc/misc.h

./libkawari/kawari_ns.h : ./config.h ./libkawari/wordcollection.h ./libkawari/kawari_log.h ./libkawari/kawari_rc.h

./libkawari/kawari_rc.cpp : ./config.h ./libkawari/kawari_rc.h ./libkawari/kawari_rc_sjis_encoded.h

./libkawari/kawari_rc.h : ./config.h

./libkawari/kawari_rc_sjis_encoded.h : ./config.h ./libkawari/kawari_rc.h

./libkawari/kawari_version.h :

./libkawari/kawari_vm.cpp : ./config.h ./libkawari/kawari_vm.h ./libkawari/kawari_engine.h ./libkawari/kawari_compiler.h ./libkawari/kawari_code.h ./libkawari/kawari_dict.h ./libkawari/kawari_rc.h ./kis/kis_config.h ./kis/kis_base.h ./misc/misc.h ./kis/kis_config.h

./libkawari/kawari_vm.h : ./config.h ./libkawari/kawari_code.h ./libkawari/wordcollection.h ./libkawari/kawari_dict.h

./libkawari/wordcollection.h : ./config.h

./misc/base64.cpp : ./config.h ./misc/base64.h

./misc/base64.h : ./config.h

./misc/l10n.cpp : ./config.h ./misc/l10n.h ./misc/misc.h

./misc/l10n.h : ./config.h

./misc/misc.cpp : ./config.h ./misc/misc.h ./misc/l10n.h

./misc/misc.h : ./config.h ./misc/mt19937ar.h ./libkawari/kawari_log.h

./misc/mmap.h :

./misc/mt19937ar.cpp : ./misc/mt19937ar.h

./misc/mt19937ar.h : ./config.h

./misc/phttp.cpp : ./config.h ./misc/phttp.h

./misc/phttp.h : ./config.h ./misc/mmap.h

./misc/_dirent.cpp : ./misc/_dirent.h

./misc/_dirent.h :

./saori/saori.cpp : ./config.h ./saori/saori.h ./saori/saori_module.h ./libkawari/kawari_log.h

./saori/saori.h : ./config.h ./misc/phttp.h ./libkawari/kawari_log.h

./saori/saori_java.cpp : ./config.h ./saori/saori_java.h ./libkawari/kawari_log.h ./misc/misc.h

./saori/saori_java.h : ./config.h ./saori/saori_module.h

./saori/saori_module.cpp : ./config.h ./saori/saori_module.h ./saori/saori_unique.h ./saori/saori_native.h ./saori/saori_java.h ./saori/saori_python.h

./saori/saori_module.h : ./config.h ./libkawari/kawari_log.h

./saori/saori_native.cpp : ./config.h ./saori/saori_native.h ./libkawari/kawari_log.h ./misc/misc.h

./saori/saori_native.h : ./config.h ./saori/saori_module.h ./include/shiori.h

./saori/saori_python.cpp : ./config.h ./shiori/kawari_shiori.h ./saori/saori_python.h ./libkawari/kawari_log.h ./misc/misc.h

./saori/saori_python.h : ./config.h ./include/shiori.h ./saori/saori_module.h

./saori/saori_unique.cpp : ./config.h ./saori/saori_unique.h ./libkawari/kawari_log.h

./saori/saori_unique.h : ./config.h ./saori/saori_module.h

./shiori/kawari_shiori.cpp : ./config.h ./shiori/kawari_shiori.h ./libkawari/kawari_log.h ./misc/misc.h

./shiori/kawari_shiori.h : ./config.h ./libkawari/kawari_engine.h ./misc/phttp.h ./libkawari/kawari_version.h

./shiori/py_shiori.cpp : ./config.h ./include/shiori.h ./shiori/py_shiori.h ./shiori/kawari_shiori.h ./saori/saori_python.h

./shiori/py_shiori.h : ./config.h ./include/shiori.h

./shiori/shiori.cpp : ./config.h ./shiori/kawari_shiori.h ./include/shiori.h

./shiori/shiori_object.cpp : ./config.h ./include/shiori_object.h ./shiori/kawari_shiori.h

./tool/kawari_decode2.cpp : ./libkawari/kawari_crypt.h

./tool/kawari_encode.cpp : ./libkawari/kawari_crypt.h

./tool/kawari_encode2.cpp : ./libkawari/kawari_crypt.h

./tool/kawari_kosui.h : ./config.h ./tool/kosui_base.h ./libkawari/kawari_engine.h ./libkawari/kawari_log.h ./libkawari/kawari_version.h ./misc/misc.h

./tool/kdb.cpp : ./config.h ./tool/kdb.h

./tool/kdb.h : ./config.h ./tool/kosui_base.h

./tool/kosui.cpp : ./config.h ./tool/kosui_base.h ./tool/kawari_kosui.h ./misc/l10n.h ./tool/kdb.h ./tool/kosui_dsstp.h

./tool/kosui_base.h : ./config.h

./tool/kosui_dsstp.cpp : ./config.h ./tool/kosui_base.h ./tool/kosui_dsstp.h ./misc/phttp.h

./tool/kosui_dsstp.h : ./config.h

./tool/logserver.cpp :

