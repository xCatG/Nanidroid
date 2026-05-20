//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// KawariInlineScript -- 引数展開実行 --
//
//      Programed by NAKAUE.T (Meister)
//
//  2002.03.18  Phase 7.9.0   新コミュニケート機構補助
//
//---------------------------------------------------------------------------
// 関数テーブルへの登録
#ifdef INLINE_SCRIPT_REGIST
INLINE_SCRIPT_REGIST(KIS_xargs);
#else
//---------------------------------------------------------------------------
#ifndef KIS_XARGS_H
#define KIS_XARGS_H
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "kis/kis_base.h"
//---------------------------------------------------------------------------
class KIS_xargs : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="xargs";
		Format_="xargs Index1 Arg1 ...";
		Returnval_="result";
		Information_="expand Index1 and run function Arg1";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
#endif
//---------------------------------------------------------------------------
#endif

