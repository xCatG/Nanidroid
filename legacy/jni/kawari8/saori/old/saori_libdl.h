//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// SAORI インターフェース(libdl)
//
//      Programed by Suikyo.
//
//  2002.09.05  Phase 8.1.0   導入
//
//---------------------------------------------------------------------------
#ifndef SAORI_LIBDL_H
#define SAORI_LIBDL_H
//---------------------------------------------------------------------------
#include "config.h"
#include "libkawari/kawari_log.h"
#include "saori/saori_module.h"
//---------------------------------------------------------------------------
#include <string>
#include <map>
//---------------------------------------------------------------------------
namespace saori{
//---------------------------------------------------------------------------
class TModuleFactoryLdl : public IModuleFactory{
public:
	// モジュールの検索と生成
	// 戻り値: 生成に成功した場合、インスタンス。失敗した場合、NULL。
	virtual TModule *CreateModule(const std::string &path);

	// モジュールの完全破棄
	virtual void DeleteModule(TModule *module);

	// コンストラクタ
	TModuleFactoryLdl(TKawariLogger &lgr);

	// デストラクタ
	virtual ~TModuleFactoryLdl(void);
};
//---------------------------------------------------------------------------
class TModuleLdl : public TModule{
public:
	// 初期化
	virtual bool Initialize(void);
	// SAORI/1.0 Load
	virtual bool Load(void);
	// SAORI/1.0 Unload
	virtual bool Unload(void);
	// SAORI/1.0 Request
	virtual std::string Request(const std::string &reqstr);

protected:
	TModuleLdl(TModuleFactoryLdl &fac, const std::string &p, SAORI_HANDLE handle)
		 : TModule(fac, p, handle) {}

	int (*func_load)(const char *datapath);
	char *(*func_request)(const char *requeststr);
	int (*func_unload)(void);

	friend class TModuleFactoryLdl;
};
//---------------------------------------------------------------------------
} // namespace saori
//---------------------------------------------------------------------------
#endif // SAORI_LIBDL_H
//---------------------------------------------------------------------------
