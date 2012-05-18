//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// SAORI インターフェース Python版
//
//              ABE, Suikyo.
//
//  2003.03.07  Phase 8.1.0   あべさんのパッチを取り込み
//  2004.09.04  Phase 8.2.1   あべさんのパッチを取り込み
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "shiori/kawari_shiori.h"
#include "saori/saori_python.h"
#include "libkawari/kawari_log.h"
#include "misc/misc.h"
//---------------------------------------------------------------------------
#include <string>
#include <iostream>
#include <map>
using namespace kawari_log;
using namespace saori;
using namespace std;
//---------------------------------------------------------------------------
static int py_saori_exist(const char *saori);
static int py_saori_load(const char *saori, const char *path);
static int py_saori_unload(const char *saori);
static char *py_saori_request(const char *saori, const char *req);
//---------------------------------------------------------------------------
TModule *TModuleFactoryPython::CreateModule(const string &path){
	GetLogger().GetStream(LOG_INFO) << "[SAORI Python] CreateModule" << endl;
	string fn=CanonicalPath(path);

	SAORI_HANDLE handle=(SAORI_HANDLE)py_saori_exist(path.c_str());
	if (!handle){
		GetLogger().GetStream(LOG_ERROR) << "[SAORI Python] Module ("+fn+") load failed." << endl;
		return NULL;
	}
	TModulePython *ret=new TModulePython((*this), fn, handle);
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
void TModuleFactoryPython::DeleteModule(TModule *module){
	if (module){
		GetLogger().GetStream(LOG_INFO) << "[SAORI Python] Free Module" << endl;
		delete module;
	}
}
//---------------------------------------------------------------------------
// コンストラクタ
TModuleFactoryPython::TModuleFactoryPython(TKawariLogger &lgr)
	: IModuleFactory(lgr) {
}
//---------------------------------------------------------------------------
// デストラクタ
TModuleFactoryPython::~TModuleFactoryPython(){
}
//---------------------------------------------------------------------------
// 初期化
bool TModulePython::Initialize(void){
	return true;
}
//---------------------------------------------------------------------------
// SAORI/1.0 Load
bool TModulePython::Load(void){
	string basepath;
	unsigned int pos=path.find_last_of(FILE_SEPARATOR);
	if (pos==string::npos){
		basepath=path+FILE_SEPARATOR;
	}else{
		basepath=path.substr(0, pos+1);
	}

	GetFactory().GetLogger().GetStream(LOG_INFO) << "[SAORI Python] load(" << basepath << ")." << endl;
	return (py_saori_load(path.c_str(), basepath.c_str()));
}
//---------------------------------------------------------------------------
// SAORI/1.0 Unload
bool TModulePython::Unload(void){
	GetFactory().GetLogger().GetStream(LOG_INFO) << "[SAORI Python] unload()" << endl;
	py_saori_unload(path.c_str());
	return true;
}
//---------------------------------------------------------------------------
// SAORI/1.0 Request
string TModulePython::Request(const string &req){
	char *response = py_saori_request(path.c_str(), req.c_str());
	string ret = response;
	free(response);

	return ret;
}
//---------------------------------------------------------------------------
// Python I/F

PyObject *saori_exist = NULL;
PyObject *saori_load = NULL;
PyObject *saori_unload = NULL;
PyObject *saori_request = NULL;
//---------------------------------------------------------------------------
PyObject *wrap_setcallback(PyObject *self, PyObject *args)
{
	Py_XDECREF(saori_exist);
	Py_XDECREF(saori_load);
	Py_XDECREF(saori_unload);
	Py_XDECREF(saori_request);

	if (!PyArg_ParseTuple(args, "OOOO",
		&saori_exist, &saori_load, &saori_unload, &saori_request))
		return NULL;

	if (!PyCallable_Check(saori_exist) ||
		!PyCallable_Check(saori_load) ||
		!PyCallable_Check(saori_unload) ||
		!PyCallable_Check(saori_request)) {
		PyErr_SetString(PyExc_TypeError, "parameter must be callable");
		return NULL;
	}

	Py_XINCREF(saori_exist);
	Py_XINCREF(saori_load);
	Py_XINCREF(saori_unload);
	Py_XINCREF(saori_request);

	Py_INCREF(Py_None);
	return Py_None;
}
//---------------------------------------------------------------------------
static int py_saori_exist(const char *saori)
{
	if (saori_exist == NULL) {
		cout << "exist result err" << endl;
		return 0;
	}

	PyObject *arglist = Py_BuildValue("(s)", saori);
	PyObject *result = PyEval_CallObject(saori_exist, arglist);
	Py_XDECREF(arglist);
	if (result == NULL) {
		cout << "exist result err" << endl;
		return 0;
	}

	int ret = 0;
	PyArg_Parse(result, "i", &ret);
	Py_XDECREF(result);
	return ret;
}
//---------------------------------------------------------------------------
static int py_saori_load(const char *saori, const char *path)
{
	if (saori_load == NULL) {
		cout << "load result err" << endl;
		return 0;
	}

	PyObject *arglist = Py_BuildValue("(ss)", saori, path);
	PyObject *result = PyEval_CallObject(saori_load, arglist);
	Py_XDECREF(arglist);
	if (result == NULL) {
		cout << "load result err" << endl;
		return 0;
	}

	int ret = 0;
	PyArg_Parse(result, "i", &ret);
	Py_XDECREF(result);
	return ret;
}
//---------------------------------------------------------------------------
static int py_saori_unload(const char *saori)
{
	if (saori_unload == NULL) {
		cout << "unload result err" << endl;
		return 0;
	}

	PyObject *arglist = Py_BuildValue("(s)", saori);
	PyObject *result = PyEval_CallObject(saori_unload, arglist);
	Py_XDECREF(arglist);
	if (result == NULL) {
		cout << "unload result err" << endl;
		return 0;
	}

	int ret = 0;
	PyArg_Parse(result, "i", &ret);
	Py_XDECREF(result);
	return ret;
}
//---------------------------------------------------------------------------
static char *py_saori_request(const char *saori, const char *req)
{
	if (saori_request == NULL) {
		cout << "request result err" << endl;
		return "";
	}

	PyObject *arglist = Py_BuildValue("(ss)", saori, req);
	PyObject *result = PyEval_CallObject(saori_request, arglist);
	Py_XDECREF(arglist);
	if (result == NULL) {
		cout << "request result err" << endl;
		return "";
	}

	char *res = NULL;
	PyArg_Parse(result, "s", &res);
	res = strdup(res);
	Py_XDECREF(result);
	return res;
}
//---------------------------------------------------------------------------
