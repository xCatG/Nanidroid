.autodepend

!include files.mak

MACH = ../mach/bcc
SHIORI = shiori

MACHSRC = $(SAORI_WIN32SRC)
SHIO_MACHSRC = $(SHIO_WIN32SRC)
KOSUI_MACHSRC = $(KOSUI_WIN32SRC)
TEMPORAL_FILES = 

CXX = bcc32
OBJEXT = .obj
EXEEXT = .exe
DYNLIBEXT = .dll

CFLAGS = -O2 -I. -w-8027 -tWM -DUSEKDB -DENABLE_SAORI_WIN32 -DNDEBUG
LIBS   = 
KOSUILIBS = -lgdi32

LIBOBJ =   $(LIBSRC:.cpp=$(OBJEXT))
SHIOOBJ =  $(SHIOSRC:.cpp=$(OBJEXT))
CRYPTOBJ = $(CRYPTSRC:.cpp=$(OBJEXT))
KOSUIOBJ = $(KOSUISRC:.cpp=$(OBJEXT)) $(KOSUISRC_win32:.cpp=$(OBJEXT))
TOOLOBJ =  $(TOOLSRC:.cpp=$(OBJEXT))

ALLTARGET = $(MACH)/$(SHIORI)$(DYNLIBEXT) \
            $(MACH)/kosui$(EXEEXT) \
            $(MACH)/kawari_encode$(EXEEXT) \
            $(MACH)/kawari_encode2$(EXEEXT) \
            $(MACH)/kawari_decode2$(EXEEXT) \
            $(EXTRA_TARGET)


## TARGETS ##

all : $(ALLTARGET) upx

$(MACH)/shiori$(DYNLIBEXT) : $(SHIOOBJ) $(LIBOBJ)
	bcc32 -tWD -tWM -e$@ $(SHIOOBJ) $(LIBOBJ) $(LIBS)
#	brc32 -32 info.rc shiori_dll\shiori.dll

$(MACH)/kosui$(EXEEXT) : $(KOSUIOBJ) $(LIBOBJ)
	bcc32 -e$@ -tWM $(KOSUIOBJ) $(LIBOBJ) $(LIBS) $(KOSUILIBS)

$(MACH)/kawari_encode$(EXEEXT) : ./tool/kawari_encode.obj $(CRYPTOBJ)
	bcc32 -e$@ -tWM $** $(LIBS)

$(MACH)/kawari_encode2$(EXEEXT) : ./tool/kawari_encode2.obj $(CRYPTOBJ)
	bcc32 -e$@ -tWM $** $(LIBS)

$(MACH)/kawari_decode2$(EXEEXT) : ./tool/kawari_decode2.obj $(CRYPTOBJ)
	bcc32 -e$@ -tWM $** $(LIBS)

.cpp.obj:
	bcc32 $(CFLAGS) $(SHIORIVER) -n$: -c $<

clean :
	-for %f in ( $(LIBOBJ:/=\) $(SHIOOBJ:/=\) $(KOSUIOBJ:/=\) $(TOOLOBJ:/=\) $(TEMPORAL_FILES:/=\) ) do del %f
	-del $(MACH:/=\)\*.tds $(MACH:/=\)\*.obj

cleanall : clean
	-for %f in ( $(ALLTARGET:/=\) ) do del %f
	-del info.res

#
# sub rules for upx
#

UPX = upx
#UPX = rem
# upxは実行ファイル圧縮ツール
# http://wildsau.idv.uni-linz.ac.at/mfx/upx.html
# を参照してください

upx : $(ALLTARGET)
	$(UPX) $(ALLTARGET)
