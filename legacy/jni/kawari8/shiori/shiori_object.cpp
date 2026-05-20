//---------------------------------------------------------------------------
//
// あれ以外の何か以外の何か
// OBJECT SHIORI
//
//      Programed by Suikyo.
//
//  2003.01.24  Phase 8.1.0   偽林檎を参考に
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "include/shiori_object.h"
#include "shiori/kawari_shiori.h"
//---------------------------------------------------------------------------
using namespace std;
//---------------------------------------------------------------------------
namespace {
	// utility(pointer must be free)
	inline const char *string2cstr(const string &s, long *length){
		*length=s.length();
		char *d=new char[(int)*length];
		s.copy(d, (int)*length);
		return d;
	}
}
//---------------------------------------------------------------------------
// ライブラリの初期化処理
int so_library_init(void){
	return 1;
}
//---------------------------------------------------------------------------
// ライブラリの終了処理
int so_library_cleanup(void){
	TKawariShioriFactory::DisposeFactory();
	return 1;
}
//---------------------------------------------------------------------------
// モジュール番号を返す
const char *so_getmoduleversion(long *length){
	string modver=TKawariShioriFactory::GetModuleVersion();
	return string2cstr(modver, length);
}
//---------------------------------------------------------------------------
// 新規インスタンスの生成
SO_HANDLE so_create(const char *argument, long length){
	return TKawariShioriFactory::GetFactory().CreateInstance(
		string(argument, length));
}
//---------------------------------------------------------------------------
// インスタンスの削除
int so_dispose(SO_HANDLE h){
	return (int)TKawariShioriFactory::GetFactory().DisposeInstance((int)h);
}
//---------------------------------------------------------------------------
// 偽AIリクエスト
//   戻り値のポインタはなるべく早くso_free()で解放すること。
//   requeststr : 「何か。(仮)」SHIORI/2.x, SAORI/1.0規格に基づき
//                 エンコードされた引数
//
//   returns NULL if library is not ready.
//
const char *so_request(SO_HANDLE h, const char *reqstr, long *length){
	string res=TKawariShioriFactory::GetFactory().RequestInstance(
		(int)h, string(reqstr, *length));

	return string2cstr(res, length);
}
//---------------------------------------------------------------------------
// Free Memory
void so_free(SO_HANDLE h, const char *ptr){
	char *p=(char *)ptr;
	// 'delete NULL;' is safe.
	delete[] p;
}
//---------------------------------------------------------------------------
