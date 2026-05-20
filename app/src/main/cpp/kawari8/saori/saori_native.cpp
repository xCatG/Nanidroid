//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// SAORI インターフェース(Native)
//
//      Programed by Suikyo.
//
//  2002.04.15  Phase 8.0.0   えびさわさんバージョンを参考に導入
//  2004.02.13  Phase 8.2.0   (phonohawk) libdlを使う場合には代替ライブラリも探す
//                  参考:  http://ccm.sherry.jp/ninni/pa_spec/saori-posix.html
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "saori/saori_native.h"
#include "libkawari/kawari_log.h"
#include "misc/misc.h"
//---------------------------------------------------------------------------
#ifndef KAWARI_MS
#	include <dlfcn.h>
#       include <stdlib.h>
#       include <sys/types.h>
#       include <sys/stat.h>
#       include <unistd.h>
#endif
#include <iostream>
#include <string>
#include <vector>
#include <map>
using namespace std;
using namespace saori;
using namespace kawari_log;
//---------------------------------------------------------------------------
#ifndef KAWARI_MS
static string str_getenv(const string& name) {
    char* var = getenv(name.c_str());
    if (var == NULL) {
	return string();
    }
    else {
	return string(var);
    }
}
static vector<string> posix_dll_search_path;
static bool posix_dll_search_path_is_ready = false;
static string posix_search_fallback_dll(const string& dllfile) {
    // dllfileは探したいファイルDLL名。パス区切り文字は/。
    // 代替ライブラリが見付かればその絶対パスを、
    // 見付けられなければ空文字列を返す。
    
    if (!posix_dll_search_path_is_ready) {
	// SAORI_FALLBACK_PATHを見る。
	string path = str_getenv("SAORI_FALLBACK_PATH");
	if (path.length() > 0) {
	    while (true) {
		string::size_type colon_pos = path.find(':');
		if (colon_pos == string::npos) {
		    posix_dll_search_path.push_back(path);
		    break;
		}
		else {
		    posix_dll_search_path.push_back(path.substr(0, colon_pos));
		    path.erase(0, colon_pos+1);
		}
	    }
	}
	posix_dll_search_path_is_ready = true;
    }

    string::size_type pos_slash = dllfile.rfind('/');
    string fname(
	dllfile.begin() + (pos_slash == string::npos ? 0 : pos_slash),
	dllfile.end());

    for (vector<string>::const_iterator ite = posix_dll_search_path.begin();
	 ite != posix_dll_search_path.end(); ite++ ) {
	string fpath = *ite + '/' + fname;
	struct stat sb;
	if (stat(fpath.c_str(), &sb) == 0) {
	    // 代替ライブラリが存在するようだ。これ以上のチェックは省略。
	    return fpath;
	}
    }
    return string();
}
#endif
//---------------------------------------------------------------------------
namespace {
//---------------------------------------------------------------------------
// prototypes
SAORI_HANDLE load_library(const string &file);
void unload_library(SAORI_HANDLE handle);
void *get_symbol(SAORI_HANDLE handle, const std::string &name);

#if defined(WIN32)||defined(_WIN32)||defined(_Windows)||defined(__CYGWIN__)
// Win32
SAORI_HANDLE load_library(const string &file){
	return (SAORI_HANDLE)::LoadLibrary(file.c_str());
}
void unload_library(SAORI_HANDLE handle){
	::FreeLibrary((HMODULE)handle);
}
void *get_symbol(SAORI_HANDLE handle, const string &name){
	FARPROC lpfn=::GetProcAddress((HMODULE)handle, name.c_str());
	if (!lpfn){
		string _name="_"+name;
		lpfn = ::GetProcAddress((HMODULE)handle, _name.c_str());
	}
	return (void *)lpfn;
}
#else
// libdl
SAORI_HANDLE load_library(const string &file){
	bool fallback_always = false;
	string env_fallback_always = str_getenv("SAORI_FALLBACK_ALWAYS");
	if (env_fallback_always.length() > 0 && env_fallback_always != "0") {
	    fallback_always = true;
	}
	
	bool do_fallback = true;
	if (!fallback_always) {
	    // SAORI_FALLBACK_ALWAYSが空でも0でもなければ、まずは試しにdlopenしてみる。
	    void* handle = ::dlopen(file.c_str(), RTLD_LAZY);
	    if (handle != NULL) {
		// load, unload, requestを取り出してみる。
		void* sym_load = ::dlsym(handle, "load");
		void* sym_unload = ::dlsym(handle, "unload");
		void* sym_request = ::dlsym(handle, "request");
		if (sym_load != NULL && sym_unload != NULL && sym_request != NULL) {
		    do_fallback = false;
		}
	    }
	    ::dlclose(handle);
	}
	
	if (do_fallback) {
	    // 代替ライブラリを探す。
	    string fallback_lib = posix_search_fallback_dll(file);
	    if (fallback_lib.length() == 0) {
		// 無い
		// Loggerを使うには、この関数がIModuleFactoryのメソッドでなければならない…
		return NULL;
	    }
	    else {
		// あったので、これを使う。
		return (SAORI_HANDLE)::dlopen(fallback_lib.c_str(), RTLD_LAZY);
	    }
	}
	else {
	    return (SAORI_HANDLE)::dlopen(file.c_str(), RTLD_LAZY);
	}
}
void unload_library(SAORI_HANDLE handle){
	::dlclose((void *)handle);
}
void *get_symbol(SAORI_HANDLE handle, const std::string &name){
	return (void *)::dlsym((void *)handle, name.c_str());
}
#endif
//---------------------------------------------------------------------------
} // namespace
//---------------------------------------------------------------------------
namespace saori{
//---------------------------------------------------------------------------
TModule *TModuleFactoryNative::CreateModule(const string &path){
	GetLogger().GetStream(LOG_INFO) << "[SAORI Native] CreateModule" << endl;
	string fn=CanonicalPath(path);

	SAORI_HANDLE handle=load_library(fn);
	if (!handle){
		GetLogger().GetStream(LOG_ERROR) << "[SAORI Native] Library ("+fn+") load failed." << endl;
		return NULL;
	}
	TModuleNative *ret=new TModuleNative((*this), fn, handle);
	if (ret->Initialize()){
		return ret;
	}else{
		ret->Unload();
		DeleteModule(ret);
		return NULL;
	}
}
//---------------------------------------------------------------------------
// モジュールの完全破棄
void TModuleFactoryNative::DeleteModule(TModule *module){
	if (module){
		GetLogger().GetStream(LOG_INFO) << "[SAORI Native] FreeLibrary" << endl;
		unload_library(module->GetHandle());
		delete module;
	}
}
//---------------------------------------------------------------------------
// コンストラクタ
TModuleFactoryNative::TModuleFactoryNative(TKawariLogger &lgr)
	: IModuleFactory(lgr) {
}
//---------------------------------------------------------------------------
// デストラクタ
TModuleFactoryNative::~TModuleFactoryNative(){
}
//---------------------------------------------------------------------------
// 初期化
bool TModuleNative::Initialize(void){
	func_load=(BOOL (SHIORI_CALL *)(MEMORY_HANDLE, long))get_symbol(handle, "load");
	func_unload=(BOOL (SHIORI_CALL *)(void))get_symbol(handle, "unload");
	func_request=(MEMORY_HANDLE (SHIORI_CALL *)(MEMORY_HANDLE, long *))get_symbol(handle, "request");

	if (func_request==NULL){
		GetFactory().GetLogger().GetStream(LOG_ERROR) << "[SAORI Native] importing 'request' from ("+path+") failed." << endl;
		return false;
	}
	return true;
}
//---------------------------------------------------------------------------
// SAORI/1.0 Load
bool TModuleNative::Load(void){
	if (!func_load) return TRUE;

	string basepath;
	unsigned int pos=path.find_last_of(FILE_SEPARATOR);
	if (pos==string::npos){
		basepath=path+FILE_SEPARATOR;
	}else{
		basepath=path.substr(0, pos+1);
	}

	long len=basepath.size();
	MEMORY_HANDLE h=(MEMORY_HANDLE)SHIORI_MALLOC(len);
	if (!h) return false;
	basepath.copy((char *)h, len);
	GetFactory().GetLogger().GetStream(LOG_INFO) << "[SAORI Native] load(" << basepath << ")." << endl;
	return (0!=(func_load)(h,len));
}
//---------------------------------------------------------------------------
// SAORI/1.0 Unload
bool TModuleNative::Unload(void){
	if (!func_unload) return true;

	GetFactory().GetLogger().GetStream(LOG_INFO) << "[SAORI Native] unload()" << endl;
	(func_unload)();
	return true;
}
//---------------------------------------------------------------------------
// SAORI/1.0 Request
string TModuleNative::Request(const string &req){
	if (!func_request) return ("");

	long len = (long)(req.size());
	MEMORY_HANDLE h=(MEMORY_HANDLE)SHIORI_MALLOC(len);
	if (!h) return ("");
	req.copy((char *)h, len);

	h=func_request(h, &len);

	if (h) {
		string res((const char *)h, len);
		SHIORI_FREE(h);
		return res;
	}else{
		return ("");
	}
}
//---------------------------------------------------------------------------
}// namespace saori
//---------------------------------------------------------------------------
