CXX_cygwin = g++
CXX_mingw =  g++
CXX_linux =  g++
CXX_freebsd =g++
CXX_darwin = g++

EXEEXT_cygwin = .exe
EXEEXT_mingw =  .exe
EXEEXT_linux =
EXEEXT_freebsd =
EXEEXT_darwin =

DYNLIBPREFIX_cygwin = 
DYNLIBPREFIX_mingw =
DYNLIBPREFIX_linux =   lib
DYNLIBPREFIX_freebsd = lib
DYNLIBPREFIX_darwin =  lib

DYNLIBEXT_cygwin =  .dll
DYNLIBEXT_mingw =   .dll
DYNLIBEXT_linux =   .so
DYNLIBEXT_freebsd = .so
DYNLIBEXT_darwin =  .bundle

LIBEXT_cygwin = .lib
LIBEXT_mingw = .lib
LIBEXT_linux = .l
LIBEXT_freebsd = .l
LIBEXT_darwin = .l

CFLAGS_cygwin =  -DUSEKDB
CFLAGS_mingw =   -DUSEKDB
CFLAGS_linux =
CFLAGS_freebsd =
CFLAGS_darwin =  

LDFLAGS_cygwin = -Wl,--enable-auto-import -Wl,--enable-stdcall-fixup
LDFLAGS_mingw = -Wl,--enable-auto-import -Wl,--enable-stdcall-fixup
LDFLAGS_linux = 
LDFLAGS_freebsd = 
LDFLAGS_darwin = 

SHARED_cygwin = -shared
SHARED_mingw = -shared
SHARED_linux = -shared
SHARED_freebsd = -shared
SHARED_darwin = -bundle

LIBS_cygwin = 
LIBS_mingw =
LIBS_linux = -ldl
LIBS_freebsd =
LIBS_darwin = -lstdc++
