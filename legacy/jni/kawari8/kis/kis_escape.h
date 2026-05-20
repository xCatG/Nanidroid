//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// KawariInlineScript -- エスケープ --
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.07.14  Phase 6.1     First version
//
//---------------------------------------------------------------------------
// 関数テーブルへの登録
#ifdef INLINE_SCRIPT_REGIST
INLINE_SCRIPT_REGIST(KIS_escape);
#else
//---------------------------------------------------------------------------
#ifndef KIS_ESCAPE_H
#define KIS_ESCAPE_H
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "kis/kis_base.h"
//---------------------------------------------------------------------------
class KIS_escape : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="escape";
		Format_="escape Word1 ...";
		Returnval_="Word1 ...";
		Information_="replace \\ into \\\\ in all arguments";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
#endif
//---------------------------------------------------------------------------
#endif

