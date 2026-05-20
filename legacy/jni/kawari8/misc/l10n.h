//---------------------------------------------------------------------------
//
// Localization支援
//
//      Programed by Kouji.U (sky) / NAKAUE.T
//
//  2002.03.17  Phase 7.9.0   ctow()をexprから移動
//
//---------------------------------------------------------------------------
#ifndef L10N_H
#define L10N_H
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include <string>
#ifdef __GNUC__
	#if (__GNUC__ < 3)
		#include <cctype>
	#else
		#include <cctype>
		#include <cwchar>
	#endif
	namespace std {
		typedef basic_string<wchar_t> wstring;
	}
#else
	#include <cwctype>
	#include <cwchar>
#endif
//---------------------------------------------------------------------------
// 2バイト文字の1バイト目か?
inline bool iskanji1st(char c)
{
#if 1
	// SJIS
	// 0x00-0x7f ASCII
	// 0x80-0x9f,0xe0-0xfc いわゆる全角1バイト目
	// 0xa0-0xdf いわゆる半角カナ
	return((unsigned char)((c^0x20)-0xa1)<=0x3b);
	// ちなみに2バイト目は0x40-0xfc
#else
	// EUC
	return(c<0);
#endif
}
//---------------------------------------------------------------------------
// SJIS Wide Char String -> SJIS Char String
std::string wtoc(const std::wstring& ws);
//---------------------------------------------------------------------------
// SJIS Char String -> SJIS Wide Char String
std::wstring ctow(const std::string& s);
//---------------------------------------------------------------------------
#endif
