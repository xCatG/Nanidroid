//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "include/shiori.h"
#include "shiori/py_shiori.h"
#include "shiori/kawari_shiori.h"
#include "saori/saori_python.h"
//---------------------------------------------------------------------------
#include <string>
#include <Python.h>
//---------------------------------------------------------------------------
using namespace std;
//---------------------------------------------------------------------------
#if 0
static unsigned int shiori_handle = 0;
#endif

extern "C" PyObject *saori_exist;
extern "C" PyObject *saori_load;
extern "C" PyObject *saori_unload;
extern "C" PyObject *saori_request;

static PyObject *wrap_getmoduleversion(PyObject *self, PyObject *args)
{
	static string verstr;

	if(!PyArg_ParseTuple(args, ""))
		return NULL;

	verstr = TKawariShioriFactory::GetModuleVersion();

	return Py_BuildValue("s", verstr.c_str());
}

static PyObject *wrap_load(PyObject *self, PyObject *args)
{
	char *datapath;
	unsigned int shiori_handle = 0;

	if(!PyArg_ParseTuple(args, "s", &datapath))
		return NULL;

	shiori_handle = TKawariShioriFactory::GetFactory().CreateInstance(
		string(datapath));
#if 0
	if (shiori_handle == 0) {
		Py_INCREF(Py_False);
		return Py_False;
	}

	Py_INCREF(Py_True);
	return Py_True;
#else
	return Py_BuildValue("d", shiori_handle);
#endif
}

static PyObject *wrap_unload(PyObject *self, PyObject *args)
{
#if 0
	if(!PyArg_ParseTuple(args, ""))
#else
	unsigned int shiori_handle = 0;

	if(!PyArg_ParseTuple(args, "d", &shiori_handle))
#endif
		return NULL;

	if (!TKawariShioriFactory::GetFactory().DisposeInstance(shiori_handle)){
		Py_INCREF(Py_False);
		return Py_False;
	}
#if 0
	shiori_handle=0;
	TKawariShioriFactory::DisposeFactory();
#else
	// For multi-ghost/single-process system, it's important not to
	//dispose TKawariShioriFactory.
	//TKawariShioriFactory::DisposeFactory();
#endif

	Py_XDECREF(saori_exist);
	Py_XDECREF(saori_load);
	Py_XDECREF(saori_unload);
	Py_XDECREF(saori_request);
	saori_exist = saori_load = saori_unload = saori_request = NULL;

	Py_INCREF(Py_True);
	return Py_True;
}

static PyObject *wrap_request(PyObject *self, PyObject *args)
{
	char *requeststr;
	string aistr;
#if 0

	if(!PyArg_ParseTuple(args, "s", &requeststr))
#else
	unsigned int shiori_handle = 0;

	if(!PyArg_ParseTuple(args, "ds", &shiori_handle, &requeststr))
#endif
		return NULL;

	aistr = TKawariShioriFactory::GetFactory().RequestInstance(
		shiori_handle, string(requeststr));

	return Py_BuildValue("s", aistr.c_str());
}

static struct PyMethodDef functions[] = {
	{"getmoduleversion", wrap_getmoduleversion, METH_VARARGS},
	{"load", wrap_load, METH_VARARGS},
	{"unload", wrap_unload, METH_VARARGS},
	{"request", wrap_request, METH_VARARGS},
	{"setcallback", wrap_setcallback, METH_VARARGS},
	{NULL, NULL}
};
//---------------------------------------------------------------------------
// exported function
void init_kawari8()
{
	Py_InitModule("_kawari8", functions);
}
//---------------------------------------------------------------------------
