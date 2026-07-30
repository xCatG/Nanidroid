//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// SAORI インターフェース(Win32)
//
//      Programed by Suikyo.
//
//  2002.09.05  Phase 8.1.0   導入
//
//---------------------------------------------------------------------------
#ifndef SAORI_WIN32_H
#define SAORI_WIN32_H
//---------------------------------------------------------------------------
#include <windows.h>
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
class TModuleFactoryWin32 : public IModuleFactory{
public:
	// モジュールの検索と生成
	// 戻り値: 生成に成功した場合、インスタンス。失敗した場合、NULL。
	virtual TModule *CreateModule(const std::string &path);

	// モジュールの完全破棄
	virtual void DeleteModule(TModule *module);

	// コンストラクタ
	TModuleFactoryWin32(TKawariLogger &lgr);

	// デストラクタ
	virtual ~TModuleFactoryWin32(void);
};
//---------------------------------------------------------------------------
class TModuleWin32 : public TModule{
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
	TModuleWin32(TModuleFactoryWin32 &fac, const std::string &p, SAORI_HANDLE handle)
		 : TModule(fac, p, handle) {}

	BOOL	(__cdecl *func_load)(HGLOBAL, long);
	HGLOBAL	(__cdecl *func_request)(HGLOBAL, long *);
	BOOL	(__cdecl *func_unload)();

	friend class TModuleFactoryWin32;
};
//---------------------------------------------------------------------------
} // namespace saori
//---------------------------------------------------------------------------
#endif // SAORI_WIN32_H
//---------------------------------------------------------------------------
