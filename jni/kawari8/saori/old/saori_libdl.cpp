//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// SAORI インターフェース(libdl)
//
//      Programed by Suikyo.
//
//  2003.01.09  Phase 8.1.0   導入
//
//---------------------------------------------------------------------------
#include "config.h"
#include "saori/saori_libdl.h"
#include "libkawari/kawari_log.h"
//---------------------------------------------------------------------------
#include <dlfcn.h>
#include <iostream>
#include <map>
using namespace std;
using namespace saori;
using namespace kawari_log;
//---------------------------------------------------------------------------
namespace saori{
//---------------------------------------------------------------------------
TModule *TModuleFactoryLdl::CreateModule(const string &path){
	GetLogger().GetStream(LOG_INFO) << "[SAORI Libdl] CreateModule" << endl;
	string fn(path);
	for (unsigned int i=0; i<fn.length(); i++)
		if(fn[i]=='\\') fn[i]='/';

	SAORI_HANDLE handle=(SAORI_HANDLE)::dlopen(fn.c_str(), RTLD_GLOBAL|RTLD_NOW);
	if (!handle){
		fn+=".so";
		handle=(SAORI_HANDLE)::dlopen(fn.c_str(), RTLD_GLOBAL|RTLD_NOW);
	}
	if (handle){
		TModuleLdl *ret=new TModuleLdl((*this), fn, handle);
		if (!ret->Initialize()){
			ret->Unload();
			delete ret;
			return NULL;
		}else{
			return ret;
		}
	}else{
		GetLogger().GetStream(LOG_ERROR) << "[SAORI Libdl] shared library ("+fn+") load failed." << endl;
		return NULL;
	}
}
//---------------------------------------------------------------------------
// モジュールの完全破棄
void TModuleFactoryLdl::DeleteModule(TModule *module){
	if (module){
		GetLogger().GetStream(LOG_INFO) << "[SAORI Libdl] FreeLibrary" << endl;
		::dlclose((void *)module->GetHandle());
		delete module;
	}
}
//---------------------------------------------------------------------------
// コンストラクタ
TModuleFactoryLdl::TModuleFactoryLdl(TKawariLogger &lgr)
	: IModuleFactory(lgr) {
}
//---------------------------------------------------------------------------
// デストラクタ
TModuleFactoryLdl::~TModuleFactoryLdl(){
}
//---------------------------------------------------------------------------
// 初期化
bool TModuleLdl::Initialize(void){
	func_load=(int (*)(const char *))dlsym((void *)handle, "load");
	func_request=(char *(*)(const char *))dlsym((void *)handle, "request");
	func_unload=(int (*)(void))dlsym((void *)handle, "unload");

	if (func_request==NULL){
		GetFactory().GetLogger().GetStream(LOG_ERROR) << "[SAORI Libdl] importing 'request' from ("+path+") failed." << endl;
		return false;
	}
	return true;
}
//---------------------------------------------------------------------------
// SAORI/1.0 Load
bool TModuleLdl::Load(void){
	if (!func_load) return true;

	string basepath;
	unsigned int pos=path.find_last_of('/');
	if (pos==string::npos){
		basepath=path+"/";
	}else{
		basepath=path.substr(0, pos+1);
	}

	GetFactory().GetLogger().GetStream(LOG_INFO) << "[SAORI Libdl] load(" << basepath << ")." << endl;
	return (0!=(func_load)(basepath.c_str()));
}
//---------------------------------------------------------------------------
// SAORI/1.0 Unload
bool TModuleLdl::Unload(void){
	if (!func_unload) return true;

	GetFactory().GetLogger().GetStream(LOG_INFO) << "[SAORI Libdl] unload()" << endl;
	(func_unload)();
	return true;
}
//---------------------------------------------------------------------------
// SAORI/1.0 Request
string TModuleLdl::Request(const string &req){
	if (!func_request) return ("");

	char *response=func_request(req.c_str());
	string ret=response;
	free(response);

	return ret;
}
//---------------------------------------------------------------------------
}// namespace saori
//---------------------------------------------------------------------------
