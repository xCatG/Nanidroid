//=========================================================================
/**
  * @file shiori_object.h
  * @brief SHIORI/SAORI Object Interface
  *
  * this file is PDS, contributed 'as is'.
  *
  * 01/31/2003 Suikyo
  *
  * <H2>LIFE CYCLE / ライフサイクル</H2>
  * LOAD LIBRARY
  *
  *   so_library_init();
  *   so_getmoduleversion();	// can be called BEFORE so_library_init()
  *   so_free();
  *   foreach (so_create()){
  *     loop {
  *       so_request();
  *       so_free();
  *     }
  *     so_dispose();
  *   }
  *   so_library_cleanup();
  *
  * UNLOAD LIBRARY
  *
  */
//=========================================================================
#ifndef SHIORI_OBJECT_H
#define SHIORI_OBJECT_H
//-------------------------------------------------------------------------
#include "include/shiori.h"
//-------------------------------------------------------------------------
/**
  * @brief			handle of object. 0 is invalid.
  * 				オブジェクトハンドル。0は無効。
  */
typedef unsigned int SO_HANDLE;
//-------------------------------------------------------------------------
/**
  * @brief			invalid handle.
  */
#define SO_INVALID ((SO_HANDLE)0)
//-------------------------------------------------------------------------
/**
  * @brief			initialize library / ライブラリの初期化
  * @return			returns 0 if failed, non 0 if success.
  * 				0は失敗、それ以外は成功。
  */
SHIORI_EXPORT int SHIORI_CALL so_library_init(void);
//-------------------------------------------------------------------------
/**
  * @brief			clean up library / ライブラリの終了処理
  * @return			returns 0 if failed, non 0 if success.
  *					0は失敗、それ以外は成功。
  */
SHIORI_EXPORT int SHIORI_CALL so_library_cleanup(void);
//-------------------------------------------------------------------------
/**
 * @brief			get version string of module.
 * @param length	(O) a pointer to store length of return value in bytes.
 * 					戻り値のbyte長を格納するintへのポインタ。
 * @return			version string in following format.
 *					"NAME[.SUBNAME[.SUBNAME]]/VERSION"
 *					or NULL if failed.
 *					this must be free using so_free().
 *					次の形式を持った0終端文字列。
 *					"基本名称[.補助名称[.補助名称]]/バージョン番号"。
 *					失敗時はNULL. so_free()を使ってメモリを解放すること。
 *					ex) "KAWARI.kdt/8.1.0"
 */
SHIORI_EXPORT const char * SHIORI_CALL so_getmoduleversion(long *length);
//-------------------------------------------------------------------------
/**
 * @brief			create new instance
 * @param argument	(I) module specific argument. such as directory path to library.
 * @param length	(I) byte length of argument parameter.
 * @return			returns handle of the instance. 0 if library is not ready.
 */
SHIORI_EXPORT SO_HANDLE SHIORI_CALL so_create(const char *argument, long length);
//-------------------------------------------------------------------------
/**
 * @brief			delete instance
 * @param h		handle of the instance.
 * @return			returns 0 if failed, non 0 if success.
 */
SHIORI_EXPORT int SHIORI_CALL so_dispose(SO_HANDLE h);
//-------------------------------------------------------------------------
/**
 * @brief			'pseudo AI' request function
 * @param h			(I) handle of the instance.
 * @param request	(I) request message in module specific format.
 * @param length	(I/O) in input, byte length of reqstr.
 *					in output, byte length of return value.
 * @return			response message. memory area is kept by module.
 *					and caller must free the memory using so_free() as soon
 *					as possible. returns null if library is not ready.
 */
SHIORI_EXPORT const char *SHIORI_CALL so_request(SO_HANDLE h, const char *reqstr, long *length);
//-------------------------------------------------------------------------
/**
 * @brief			frees memory which is allocated by so_request().
 * @param h			(I) handle of the instance
 * @param mes		(I) pointer of area sould be free.
 */
SHIORI_EXPORT void SHIORI_CALL so_free(SO_HANDLE h, const char *ptr);
//-------------------------------------------------------------------------
#endif // SHIORI_OBJECT
//-------------------------------------------------------------------------
