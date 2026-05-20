//---------------------------------------------------------------------------
/**
  * @brief		SHIORI/2.0 偽AIモジュール インポートヘッダファイル
  *				このファイルはPDS(Public Domain Software)とする。
  *
  * 「栞」とは、「伺か。」用偽AIモジュール規格の名称である。
  * 栞規格は、HTTPに酷似したリクエスト・レスポンス型メッセージングを軸とした
  * 疎結合モデルであり、偽AIモジュール側を「栞サーバ」、それを利用する側を
  * 「栞クライアント」と呼ぶ。
  *
  * API中、受け渡しに用いられるメモリ領域は、プラットフォーム依存の領域確保
  * 関数で確保され、受け取り側が解放する。
  * 領域確保、解放に用いる関数は以下。
  * Win32プラットフォーム : GlobalAlloc(GMEM_FIXED, len), GlobalFree()
  * POSIXプラットフォーム : malloc(len), free()
  *	                        (ただし、クライアント固有Cライブラリ依存)
  */
//
//  2003.02.25                作成 <suikyo@yk.rim.or.jp>
//
//---------------------------------------------------------------------------
#ifndef SHIORI_H__
#define SHIORI_H__
//-------------------------------------------------------------------------
#if defined(WIN32)||defined(_WIN32)||defined(_Windows)||defined(__CYGWIN__)
// Win32
#	include <windows.h>
#	ifdef __BORLANDC__
// 		Borland C++
#		define SHIORI_EXPORT	extern "C"
#	else
// 		Visual C++ / Cygwin32 / Mingw32
#		define SHIORI_EXPORT	extern "C" __declspec(dllexport)
#	endif
#	define SHIORI_CALL			__cdecl
#	define MEMORY_HANDLE		HGLOBAL
#	define SHIORI_MALLOC(len)	::GlobalAlloc(GMEM_FIXED, len)
#	define SHIORI_FREE(ptr)		::GlobalFree((HGLOBAL)ptr)
#else
// Other Platform
#	define SHIORI_EXPORT		extern "C"
#	define SHIORI_CALL
#	define MEMORY_HANDLE		char *
#	define SHIORI_MALLOC(len)	malloc(len)
#	define SHIORI_FREE(ptr)		free((void *)ptr)
#endif
#ifndef BOOL
#	define BOOL					int
#	define TRUE					1
#	define FALSE				0
#endif
//---------------------------------------------------------------------------
/**
  * @brief		モジュール読み込み直後に呼ばれる
  *
  * @param h	(I) DLLファイルが存在するディレクトリパスを表す文字列への
  *				ポインタ。メモリ領域は栞サーバが解放すること。
  * @param len	(I) hが示す文字列の長さ
  * @return		成功のとき、TRUE。失敗のとき、FALSE。
  */
SHIORI_EXPORT BOOL SHIORI_CALL load(const MEMORY_HANDLE h,long len);
//---------------------------------------------------------------------------
/**
  * @brief		モジュール切り離し直前に呼ばれる
  *
  * @return		成功のとき、TRUE。失敗のとき、FALSE。
  */
SHIORI_EXPORT BOOL SHIORI_CALL unload(void);
//---------------------------------------------------------------------------
/**
  * @brief		偽AIリクエスト
  *
  * @param h	(I) 要求文字列へのポインタ。栞サーバが解放すること。
  * @param len	(IO) 呼び出し時は要求文字列のbyte長が格納され、返答文字列の
  *				byte長が格納されて返る。
  * @return		返答文字列へのポインタ。栞サーバが確保し、栞クライアントが
  *				解放すること。
  */
SHIORI_EXPORT MEMORY_HANDLE SHIORI_CALL request(const MEMORY_HANDLE h,long *len);
//---------------------------------------------------------------------------
#endif // SHIORI_H__
//---------------------------------------------------------------------------
