//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// SAORI インターフェース(唯一性保証)
//
//      Programed by Suikyo.
//
//  2002.09.05  Phase 8.1.0   導入
//
//---------------------------------------------------------------------------
#ifndef SAORI_UNIQUE_H
#define SAORI_UNIQUE_H
//---------------------------------------------------------------------------
#include "config.h"
#include "saori/saori_module.h"
//---------------------------------------------------------------------------
#include <string>
#include <map>
//---------------------------------------------------------------------------
namespace saori{
//---------------------------------------------------------------------------
// 参照カウントを管理し、Load/Unloadなどを自動で呼び出す仲介クラス
// DarwinなどのObjectModelなシステムでは、bipassされなければならない。
class TUniqueModuleFactory : public IModuleFactory{
public:
	TUniqueModuleFactory(IModuleFactory *fac)
		: IModuleFactory(fac->GetLogger()), parent(fac) {}
	virtual ~TUniqueModuleFactory();

	// モジュールの検索と生成
	// 戻り値: 生成に成功した場合、モジュール。失敗した場合、NULL。
	virtual TModule *CreateModule(const std::string &path);

	// モジュールの完全破棄
	// ライブラリの場合はFreeLibraryすること。
	virtual void DeleteModule(TModule *module);

protected:
	IModuleFactory *parent;
	std::map<SAORI_HANDLE, class TUniqueModule *> modules;
};
//---------------------------------------------------------------------------
class TUniqueModule : public TModule{
public:
	// 初期化
	virtual bool Initialize(void) { return module->Initialize(); }
	// SAORI/1.0 Load
	virtual bool Load(void) { return true; }
	// SAORI/1.0 Unload
	virtual bool Unload(void) { return true; }
	// SAORI/1.0 Request
	virtual std::string Request(const std::string &reqstr) {
		return module->Request(reqstr);
	}
	saori::SAORI_HANDLE GetHandle(void) {
		return module->GetHandle();
	}

	unsigned int GetLoadCount(void){
		return loadcount;
	}
	TUniqueModule(class IModuleFactory &fac, const std::string &p, TModule *m)
		 : TModule(fac, p, m->GetHandle()), module(m), loadcount(1) {}

	virtual ~TUniqueModule () {}
protected:
	TModule *module;
	unsigned int loadcount;
	friend class TUniqueModuleFactory;
};
//---------------------------------------------------------------------------
}
//---------------------------------------------------------------------------
#endif // SAORI_MODULE_H
//---------------------------------------------------------------------------
