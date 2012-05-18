//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// KawariInlineScript -- SHIORI/2.5 URLリスト --
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.08.08  Phase 6.2     First version
//  2001.08.12  Phase 6.2.1   末尾に[2]追加
//                            仕様書バグにやられた(;_;)
//  2001.08.25  Phase 6.3     chr追加・・・華和梨らしい？
//
//---------------------------------------------------------------------------
// 関数テーブルへの登録
#ifdef INLINE_SCRIPT_REGIST
INLINE_SCRIPT_REGIST(KIS_urllist);
INLINE_SCRIPT_REGIST(KIS_chr);
#else
//---------------------------------------------------------------------------
#ifndef KIS_URLLIST_H
#define KIS_URLLIST_H
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "kis/kis_base.h"
//---------------------------------------------------------------------------
class KIS_urllist : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="urllist";
		Format_="urllist SITE1 URL1 BannerURL1 ... SITEn URLn BannerURLn";
		Returnval_="SITE1[1]URL1[1]BannerURL1[2]...SITEn[1]URLn[1]BannerURLn[2]";
		Information_="return a recommend list for SHIORI/2.5 standard";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_chr : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="chr";
		Format_="chr CharCode1";
		Returnval_="a character";
		Information_="return a character which character code is CharCode1";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
#endif
//---------------------------------------------------------------------------
#endif

