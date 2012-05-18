# Microsoft Developer Studio Project File - Name="vc_kosui" - Package Owner=<4>
# Microsoft Developer Studio Generated Build File, Format Version 6.00
# ** 編集しないでください **

# TARGTYPE "Win32 (x86) Console Application" 0x0103

CFG=vc_kosui - Win32 Debug
!MESSAGE これは有効なﾒｲｸﾌｧｲﾙではありません。 このﾌﾟﾛｼﾞｪｸﾄをﾋﾞﾙﾄﾞするためには NMAKE を使用してください。
!MESSAGE [ﾒｲｸﾌｧｲﾙのｴｸｽﾎﾟｰﾄ] ｺﾏﾝﾄﾞを使用して実行してください
!MESSAGE 
!MESSAGE NMAKE /f "vc_kosui.mak".
!MESSAGE 
!MESSAGE NMAKE の実行時に構成を指定できます
!MESSAGE ｺﾏﾝﾄﾞ ﾗｲﾝ上でﾏｸﾛの設定を定義します。例:
!MESSAGE 
!MESSAGE NMAKE /f "vc_kosui.mak" CFG="vc_kosui - Win32 Debug"
!MESSAGE 
!MESSAGE 選択可能なﾋﾞﾙﾄﾞ ﾓｰﾄﾞ:
!MESSAGE 
!MESSAGE "vc_kosui - Win32 Release" ("Win32 (x86) Console Application" 用)
!MESSAGE "vc_kosui - Win32 Debug" ("Win32 (x86) Console Application" 用)
!MESSAGE 

# Begin Project
# PROP AllowPerConfigDependencies 0
# PROP Scc_ProjName ""
# PROP Scc_LocalPath ""
CPP=cl.exe
RSC=rc.exe

!IF  "$(CFG)" == "vc_kosui - Win32 Release"

# PROP BASE Use_MFC 0
# PROP BASE Use_Debug_Libraries 0
# PROP BASE Output_Dir "Release"
# PROP BASE Intermediate_Dir "Release"
# PROP BASE Target_Dir ""
# PROP Use_MFC 0
# PROP Use_Debug_Libraries 0
# PROP Output_Dir "Release"
# PROP Intermediate_Dir "Release"
# PROP Target_Dir ""
# ADD BASE CPP /nologo /W3 /GX /O2 /D "WIN32" /D "NDEBUG" /D "_CONSOLE" /D "_MBCS" /YX /FD /c
# ADD CPP /nologo /MT /W3 /GX /O2 /D "WIN32" /D "NDEBUG" /D "_CONSOLE" /D "_MBCS" /YX /FD /c
# ADD BASE RSC /l 0x411 /d "NDEBUG"
# ADD RSC /l 0x411 /d "NDEBUG"
BSC32=bscmake.exe
# ADD BASE BSC32 /nologo
# ADD BSC32 /nologo
LINK32=link.exe
# ADD BASE LINK32 kernel32.lib user32.lib gdi32.lib winspool.lib comdlg32.lib advapi32.lib shell32.lib ole32.lib oleaut32.lib uuid.lib odbc32.lib odbccp32.lib kernel32.lib user32.lib gdi32.lib winspool.lib comdlg32.lib advapi32.lib shell32.lib ole32.lib oleaut32.lib uuid.lib odbc32.lib odbccp32.lib /nologo /subsystem:console /machine:I386
# ADD LINK32 kernel32.lib user32.lib gdi32.lib winspool.lib comdlg32.lib advapi32.lib shell32.lib ole32.lib oleaut32.lib uuid.lib odbc32.lib odbccp32.lib kernel32.lib user32.lib gdi32.lib winspool.lib comdlg32.lib advapi32.lib shell32.lib ole32.lib oleaut32.lib uuid.lib odbc32.lib odbccp32.lib /nologo /subsystem:console /machine:I386

!ELSEIF  "$(CFG)" == "vc_kosui - Win32 Debug"

# PROP BASE Use_MFC 0
# PROP BASE Use_Debug_Libraries 1
# PROP BASE Output_Dir "Debug"
# PROP BASE Intermediate_Dir "Debug"
# PROP BASE Target_Dir ""
# PROP Use_MFC 0
# PROP Use_Debug_Libraries 1
# PROP Output_Dir "Debug"
# PROP Intermediate_Dir "Debug"
# PROP Ignore_Export_Lib 0
# PROP Target_Dir ""
# ADD BASE CPP /nologo /W3 /Gm /GX /ZI /Od /D "WIN32" /D "_DEBUG" /D "_CONSOLE" /D "_MBCS" /YX /FD /GZ /c
# ADD CPP /nologo /MTd /W3 /Gm /GR /GX /ZI /Od /I "../" /D "WIN32" /D "_DEBUG" /D "_CONSOLE" /D "_MBCS" /D "USEKDB" /D "ENABLE_SAORI_WIN32" /YX /FD /GZ /c
# ADD BASE RSC /l 0x411 /d "_DEBUG"
# ADD RSC /l 0x411 /d "_DEBUG"
BSC32=bscmake.exe
# ADD BASE BSC32 /nologo
# ADD BSC32 /nologo
LINK32=link.exe
# ADD BASE LINK32 kernel32.lib user32.lib gdi32.lib winspool.lib comdlg32.lib advapi32.lib shell32.lib ole32.lib oleaut32.lib uuid.lib odbc32.lib odbccp32.lib kernel32.lib user32.lib gdi32.lib winspool.lib comdlg32.lib advapi32.lib shell32.lib ole32.lib oleaut32.lib uuid.lib odbc32.lib odbccp32.lib /nologo /subsystem:console /debug /machine:I386 /pdbtype:sept
# ADD LINK32 kernel32.lib user32.lib gdi32.lib winspool.lib comdlg32.lib advapi32.lib shell32.lib ole32.lib oleaut32.lib uuid.lib odbc32.lib odbccp32.lib kernel32.lib user32.lib gdi32.lib winspool.lib comdlg32.lib advapi32.lib shell32.lib ole32.lib oleaut32.lib uuid.lib odbc32.lib odbccp32.lib /nologo /subsystem:console /debug /machine:I386 /pdbtype:sept

!ENDIF 

# Begin Target

# Name "vc_kosui - Win32 Release"
# Name "vc_kosui - Win32 Debug"
# Begin Group "Source Files"

# PROP Default_Filter "cpp;c;cxx;rc;def;r;odl;idl;hpj;bat"
# Begin Source File

SOURCE=..\misc\_dirent.cpp
# End Source File
# Begin Source File

SOURCE=..\misc\base64.cpp
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_code.cpp
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_codeexpr.cpp
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_codekis.cpp
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_codeset.cpp
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_compiler.cpp
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_crypt.cpp
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_dict.cpp
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_engine.cpp
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_lexer.cpp
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_log.cpp
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_ns.cpp
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_rc.cpp
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_vm.cpp
# End Source File
# Begin Source File

SOURCE=..\tool\kdb.cpp
# End Source File
# Begin Source File

SOURCE=..\kis\kis_communicate.cpp
# End Source File
# Begin Source File

SOURCE=..\kis\kis_counter.cpp
# End Source File
# Begin Source File

SOURCE=..\kis\kis_date.cpp
# End Source File
# Begin Source File

SOURCE=..\kis\kis_dict.cpp
# End Source File
# Begin Source File

SOURCE=..\kis\kis_echo.cpp
# End Source File
# Begin Source File

SOURCE=..\kis\kis_escape.cpp
# End Source File
# Begin Source File

SOURCE=..\kis\kis_file.cpp
# End Source File
# Begin Source File

SOURCE=..\kis\kis_help.cpp
# End Source File
# Begin Source File

SOURCE=..\kis\kis_saori.cpp
# End Source File
# Begin Source File

SOURCE=..\kis\kis_split.cpp
# End Source File
# Begin Source File

SOURCE=..\kis\kis_string.cpp
# End Source File
# Begin Source File

SOURCE=..\kis\kis_substitute.cpp
# End Source File
# Begin Source File

SOURCE=..\kis\kis_system.cpp
# End Source File
# Begin Source File

SOURCE=..\kis\kis_urllist.cpp
# End Source File
# Begin Source File

SOURCE=..\kis\kis_xargs.cpp
# End Source File
# Begin Source File

SOURCE=..\tool\kosui.cpp
# End Source File
# Begin Source File

SOURCE=..\tool\kosui_dsstp.cpp
# End Source File
# Begin Source File

SOURCE=..\misc\l10n.cpp
# End Source File
# Begin Source File

SOURCE=..\misc\misc.cpp
# End Source File
# Begin Source File

SOURCE=..\misc\mt19937ar.cpp
# End Source File
# Begin Source File

SOURCE=..\misc\phttp.cpp
# End Source File
# Begin Source File

SOURCE=..\saori\saori.cpp
# End Source File
# Begin Source File

SOURCE=..\saori\saori_module.cpp
# End Source File
# Begin Source File

SOURCE=..\saori\saori_unique.cpp
# End Source File
# Begin Source File

SOURCE=..\saori\saori_win32.cpp
# End Source File
# End Group
# Begin Group "Header Files"

# PROP Default_Filter "h;hpp;hxx;hm;inl"
# Begin Source File

SOURCE=..\misc\_dirent.h
# End Source File
# Begin Source File

SOURCE=..\misc\base64.h
# End Source File
# Begin Source File

SOURCE=..\config.h
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_code.h
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_codeexpr.h
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_codekis.h
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_codeset.h
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_compiler.h
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_crypt.h
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_dict.h
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_engine.h
# End Source File
# Begin Source File

SOURCE=..\tool\kawari_kosui.h
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_lexer.h
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_log.h
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_ns.h
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_rc.h
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_version.h
# End Source File
# Begin Source File

SOURCE=..\libkawari\kawari_vm.h
# End Source File
# Begin Source File

SOURCE=..\tool\kdb.h
# End Source File
# Begin Source File

SOURCE=..\kis\kis_base.h
# End Source File
# Begin Source File

SOURCE=..\kis\kis_communicate.h
# End Source File
# Begin Source File

SOURCE=..\kis\kis_config.h
# End Source File
# Begin Source File

SOURCE=..\kis\kis_counter.h
# End Source File
# Begin Source File

SOURCE=..\kis\kis_date.h
# End Source File
# Begin Source File

SOURCE=..\kis\kis_dict.h
# End Source File
# Begin Source File

SOURCE=..\kis\kis_echo.h
# End Source File
# Begin Source File

SOURCE=..\kis\kis_escape.h
# End Source File
# Begin Source File

SOURCE=..\kis\kis_file.h
# End Source File
# Begin Source File

SOURCE=..\kis\kis_help.h
# End Source File
# Begin Source File

SOURCE=..\kis\kis_math.h
# End Source File
# Begin Source File

SOURCE=..\kis\kis_saori.h
# End Source File
# Begin Source File

SOURCE=..\kis\kis_split.h
# End Source File
# Begin Source File

SOURCE=..\kis\kis_string.h
# End Source File
# Begin Source File

SOURCE=..\kis\kis_substitute.h
# End Source File
# Begin Source File

SOURCE=..\kis\kis_system.h
# End Source File
# Begin Source File

SOURCE=..\kis\kis_urllist.h
# End Source File
# Begin Source File

SOURCE=..\kis\kis_xargs.h
# End Source File
# Begin Source File

SOURCE=..\tool\kosui_base.h
# End Source File
# Begin Source File

SOURCE=..\tool\kosui_dsstp.h
# End Source File
# Begin Source File

SOURCE=..\misc\l10n.h
# End Source File
# Begin Source File

SOURCE=..\misc\misc.h
# End Source File
# Begin Source File

SOURCE=..\misc\mmap.h
# End Source File
# Begin Source File

SOURCE=..\misc\mt19937ar.h
# End Source File
# Begin Source File

SOURCE=..\misc\phttp.h
# End Source File
# Begin Source File

SOURCE=..\saori\saori.h
# End Source File
# Begin Source File

SOURCE=..\saori\saori_module.h
# End Source File
# Begin Source File

SOURCE=..\saori\saori_unique.h
# End Source File
# Begin Source File

SOURCE=..\saori\saori_win32.h
# End Source File
# Begin Source File

SOURCE=..\libkawari\wordcollection.h
# End Source File
# End Group
# Begin Group "Resource Files"

# PROP Default_Filter "ico;cur;bmp;dlg;rc2;rct;bin;rgs;gif;jpg;jpeg;jpe"
# End Group
# End Target
# End Project
