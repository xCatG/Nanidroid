//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// SAORI インターフェース(nativeモジュール)
//
//      Programed by Suikyo.
//
//  2003.02.25  Phase 8.1.0   導入
//
//---------------------------------------------------------------------------
#ifndef SAORI_NATIVE_H
#define SAORI_NATIVE_H
//---------------------------------------------------------------------------
#include "config.h"
#include "saori/saori_module.h"
#include "include/shiori.h"
//---------------------------------------------------------------------------
#include <string>
#include <map>
//---------------------------------------------------------------------------
namespace saori{
//---------------------------------------------------------------------------
class TModuleFactoryNative : public IModuleFactory{
public:
	// モジュールの検索と生成
	// 戻り値: 生成に成功した場合、インスタンス。失敗した場合、NULL。
	virtual TModule *CreateModule(const std::string &path);

	// モジュールの完全破棄
	virtual void DeleteModule(TModule *module);

	// コンストラクタ
	TModuleFactoryNative(class TKawariLogger &lgr);

	// デストラクタ
	virtual ~TModuleFactoryNative(void);
};
//---------------------------------------------------------------------------
class TModuleNative : public TModule{
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
	TModuleNative(TModuleFactoryNative &fac, const std::string &p, SAORI_HANDLE handle)
		 : TModule(fac, p, handle) {}

	BOOL	(SHIORI_CALL *func_load)(MEMORY_HANDLE, long);
	BOOL	(SHIORI_CALL *func_unload)(void);
	MEMORY_HANDLE	(SHIORI_CALL *func_request)(MEMORY_HANDLE, long *);

	friend class TModuleFactoryNative;
};
//---------------------------------------------------------------------------
} // namespace saori
//---------------------------------------------------------------------------
#endif // SAORI_NATIVE_H
//---------------------------------------------------------------------------
