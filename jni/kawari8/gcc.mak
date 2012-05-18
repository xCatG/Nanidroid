#==========================================================================
# manualconf
#==========================================================================
include files.mak

#==========================================================================
# Targets and Features
#==========================================================================

## choose your target: cygwin, mingw, linux, freebsd, darwin
MACH_TYPE = mingw

## output name
SHIORI = shiori

## if you have 'upx' and want to use it
# UPX = upx
UPX = upx

## if you want to use STLport, set STLport=yes
# STLport = yes

## if you want to use native SAORI, set SAORI_NATIVE=yes (default)
SAORI_NATIVE = yes

## if you want to use Python SAORI, set SAORI_PYTHON=yes
# SAORI_PYTHON = yes

## if you want to use Java SAORI, set SAORI_JAVA=yes
# SAORI_JAVA = yes

## if you want to make a Python SHIORI, set SHIORI_PYTHON=yes
# SHIORI_PYTHON = yes

## Global options
CFLAGS  = -O1 -I. -DNDEBUG -Wall -fomit-frame-pointer
LDFLAGS = -s

#==========================================================================
# Directories
#==========================================================================

## STLport specific options
CFLAGS_STLP  = -I/MinGW/include/stlport
LDFLAGS_STLP = 
LIBS_STLP = -lstlport_r50_static

## Python specific options. These are only for Win32.
# CFLAGS_PYTHON  = -I/python23/include
# LDFLAGS_PYTHON = -L/python23/lib
# LIBS_PYTHON = -lpython23

## Java specific options
JAVA_HOME  = /usr/java/j2sdk
CFLAGS_JAVA  = -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/win32
LDFLAGS_JAVA = 

#==========================================================================
# Do not modify below
#==========================================================================
include gcc-mach.mak

MACH = ../mach/$(MACH_TYPE)
EXEEXT = $(EXEEXT_$(MACH_TYPE))
DYNLIBPREFIX = $(DYNLIBPREFIX_$(MACH_TYPE))
DYNLIBEXT = $(DYNLIBEXT_$(MACH_TYPE))
OBJEXT = .o
DYNIMPLIB = ../mach/$(MACH_TYPE)/$(SHIORI)$(LIBEXT_$(MACH_TYPE))

ifeq ($(STLport),yes)
	CFLAGS  := $(CFLAGS_STLP) -DHAVE_SSTREAM $(CFLAGS)
	LDFLAGS := $(LDFLAGS) $(LDFLAGS_STLP)
	LIBS    := $(LIBS) $(LIBS_STLP)
endif

ifeq ($(SAORI_NATIVE),yes)
	CFLAGS  := -DENABLE_SAORI_NATIVE $(CFLAGS)
	CORESRC  := $(CORESRC) $(CORESRC_NATIVE)
endif

ifeq ($(SAORI_JAVA),yes)
	CORESRC := $(CORESRC) $(CORESRC_JAVA)
	CFLAGS  := $(CFLAGS) $(CFLAGS_JAVA) -DENABLE_SAORI_JAVA
	ifeq ($(MACH_TYPE),mingw)
		LDFLAGS := $(LDFLAGS) -L$(MACH)
		LIBS    := $(LIBS) -ljvm
		DEPLIB  := $(MACH)/libjvm.dll.a
	endif
endif

ifeq ($(SAORI_PYTHON),yes)
	_LINK_PYTHON_ = 1
	CORESRC := $(CORESRC) $(CORESRC_PYTHON)
	CFLAGS := -DENABLE_SAORI_PYTHON $(CFLAGS)
endif

ifeq ($(SHIORI_PYTHON),yes)
	_LINK_PYTHON = 1
	SHIOSRC := $(SHIOSRC) $(SHIOSRC_PYTHON)
endif

ifdef _LINK_PYTHON_
	PYTHON_VER = $(shell python -c "import sys; print sys.version[:3]")
	ifndef CFLAGS_PYTHON
		CFLAGS_PYTHON = -I$(shell python -c "import sys; print sys.prefix+'/include/python'+sys.version[:3]")
		CFLAGS  := $(CFLAGS_PYTHON) $(CFLAGS)
		LIBS    := $(LIBS) -lpython$(PYTHON_VER)
	endif
endif


# SHIOSRC

# KOSUISRC
KOSUISRC := $(KOSUISRC) $(KOSUISRC_$(MACH_TYPE))

# flags
CFLAGS  := $(CFLAGS_$(MACH_TYPE)) $(CFLAGS)
LDFLAGS := $(LDFLAGS_$(MACH_TYPE)) $(LDFLAGS)
LIBS    := $(LIBS) $(LIBS_$(MACH_TYPE))
DYNLIBDEF := shiori_$(MACH_TYPE).def

# commands
ifndef CXX
	CXX := $(CXX_$(MACH_TYPE))
endif
STRIP = strip
ifndef RUBY
	RUBY := ruby
endif

# object files
COREOBJ =  $(CORESRC:.cpp=$(OBJEXT))
SHIOOBJ =  $(SHIOSRC:.cpp=$(OBJEXT))
CRYPTOBJ = $(CRYPTSRC:.cpp=$(OBJEXT))
KOSUIOBJ = $(KOSUISRC:.cpp=$(OBJEXT))
TOOLOBJ =  $(TOOLSRC:.cpp=$(OBJEXT))

ALLTARGET = $(MACH)/$(DYNLIBPREFIX)$(SHIORI)$(DYNLIBEXT) \
            $(MACH)/kosui$(EXEEXT) \
            $(MACH)/kawari_encode$(EXEEXT) \
            $(MACH)/kawari_encode2$(EXEEXT) \
            $(MACH)/kawari_decode2$(EXEEXT)

## TARGETS ##
.PHONY: clean cleanall depend upx

all : $(ALLTARGET) upx

$(MACH)/$(DYNLIBPREFIX)$(SHIORI)$(DYNLIBEXT) : $(SHIOOBJ) $(COREOBJ) $(DEPLIB)
	$(CXX) -o$@ $(SHARED_$(MACH_TYPE)) $(LDFLAGS) $(SHIOOBJ) $(COREOBJ) $(LIBS)

$(MACH)/kosui$(EXEEXT) : $(KOSUIOBJ) $(COREOBJ) $(DEPLIB)
	$(CXX) -o$@ $(LDFLAGS) $(KOSUIOBJ) $(COREOBJ) $(LIBS)

$(MACH)/kawari_encode$(EXEEXT) : tool/kawari_encode$(OBJEXT) $(CRYPTOBJ)
	$(CXX) -o$@ tool/kawari_encode$(OBJEXT) $(CRYPTOBJ) $(LDFLAGS)

$(MACH)/kawari_encode2$(EXEEXT) : tool/kawari_encode2$(OBJEXT) $(CRYPTOBJ)
	$(CXX) -o$@ tool/kawari_encode2$(OBJEXT) $(CRYPTOBJ) $(LDFLAGS)

$(MACH)/kawari_decode2$(EXEEXT) : tool/kawari_decode2$(OBJEXT) $(CRYPTOBJ)
	$(CXX) -o$@ tool/kawari_decode2$(OBJEXT) $(CRYPTOBJ) $(LDFLAGS)

$(MACH)/libjvm.dll.a : win32jvm.def
	dlltool --def win32jvm.def -l $@ --dllname jvm.dll -k -C -a

.SUFFIXES : .cpp .h .a .def

%.o : %.cpp
	$(CXX) $(CFLAGS) -o$@ -c $<

clean :
	-rm $(COREOBJ) $(SHIOOBJ) $(KOSUIOBJ) $(TOOLOBJ) $(TEMPORARY_FILES)

cleanall : clean
	-rm $(ALLTARGET)

depend : clean
	$(RUBY) ./makedepend.rb > depend.mak

upx : $(ALLTARGET)
	$(UPX) $(ALLTARGET)

include depend.mak
