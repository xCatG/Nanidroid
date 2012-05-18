//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// SAORI インターフェース(pythonモジュール)
//
//              ABE, Suikyo
//
//  2003.03.07  Phase 8.1.0   導入
//
//---------------------------------------------------------------------------
#ifndef SAORI_PYTHON_H
#define SAORI_PYTHON_H
//---------------------------------------------------------------------------
#include "config.h"
#include "include/shiori.h"
#include "saori/saori_module.h"
//---------------------------------------------------------------------------
#include <Python.h>
#include <string>
#include <map>
//---------------------------------------------------------------------------
// 初期化
PyObject *wrap_setcallback(PyObject *self, PyObject *args);
//---------------------------------------------------------------------------
namespace saori{
//---------------------------------------------------------------------------
class TModuleFactoryPython : public IModuleFactory{
public:
	// モジュールの検索と生成
	// 戻り値: 生成に成功した場合、インスタンス。失敗した場合、NULL。
	virtual TModule *CreateModule(const std::string &path);

	// モジュールの完全破棄
	virtual void DeleteModule(TModule *module);

	// コンストラクタ
	TModuleFactoryPython(class TKawariLogger &lgr);

	// デストラクタ
	virtual ~TModuleFactoryPython(void);
};
//---------------------------------------------------------------------------
class TModulePython : public TModule{
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
	TModulePython(TModuleFactoryPython &fac, const std::string &p, SAORI_HANDLE handle)
		 : TModule(fac, p, handle) {}

	friend class TModuleFactoryPython;
};
//---------------------------------------------------------------------------
} // namespace saori
//---------------------------------------------------------------------------
#endif // SAORI_PYTHON_H
//---------------------------------------------------------------------------
