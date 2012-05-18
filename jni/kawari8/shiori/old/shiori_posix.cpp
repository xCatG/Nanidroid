//---------------------------------------------------------------------------
//
// あれ以外の何か以外の何か
// SharedObjectエントリ
// (ここだけ環境依存)
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.05.22  Phase 5.10    SHIORI/2.3対応
//  2001.07.10  Phase 6.0     getmoduleversion追加
//  2003.02.20  Phase 8.1.0   SHIORI Object対応
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include <string>
using namespace std;
//---------------------------------------------------------------------------
#include "shiori/kawari_shiori.h"
//---------------------------------------------------------------------------
#define DLL_API extern "C"
//---------------------------------------------------------------------------
namespace {
	unsigned int handle=0;
	inline char *string2ca(const string &s){
		char *reth=(char*)malloc(s.size());
		memcpy(reth,s.c_str(),s.size());
		return reth;
	}
}
//---------------------------------------------------------------------------
// 偽AIモジュールのバージョン番号を返す
DLL_API const char *getmoduleversion(void)
{
	static string verstr;

	verstr=TKawariShioriFactory::GetModuleVersion();

	return(verstr.c_str());
}
//---------------------------------------------------------------------------
// モジュール読み込み時に呼ばれる
DLL_API int load(const char *datapath)
{
	handle=TKawariShioriFactory::GetFactory().CreateInstance(string(datapath));
	return handle;
}
//---------------------------------------------------------------------------
// モジュール切り離し時に呼ばれる
DLL_API int unload(void)
{
	TKawariShioriFactory::DisposeFactory();
	return TRUE;
}
//---------------------------------------------------------------------------
// 偽AIリクエスト
DLL_API char *request(const char *requeststr)
{
	string aistr=TKawariShioriFactory::GetFactory().RequestInstance(
		handle, string(requeststr));

	char *reth=(char*)malloc(aistr.size());
	memcpy(reth,aistr.c_str(),aistr.size());

	return(reth);
}
//---------------------------------------------------------------------------
