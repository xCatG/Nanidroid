//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// KawariInlineScript -- split --
//
//      Programed by さとー/NAKAUE.T
//
//  2002.01.07  Phase 7.3     findposサブコマンド追加 (さとー)
//                            splitコマンド追加(さとー)
//  2002.03.17  Phase 7.9.0   7.9の仕様に合わせた (NAKAUE.T)
//                            splitがなぜexprとまとめられているか理解に苦しむので移動
//                            split仕様変更
//
//---------------------------------------------------------------------------
// 関数テーブルへの登録
#ifdef INLINE_SCRIPT_REGIST
INLINE_SCRIPT_REGIST(KIS_split);
INLINE_SCRIPT_REGIST(KIS_join);
#else
//---------------------------------------------------------------------------
#ifndef KIS_SPLIT_H__
#define KIS_SPLIT_H__
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "kis/kis_base.h"
//---------------------------------------------------------------------------
class KIS_split : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void){
		Name_="split";
		Format_="split Entry1 string delimiter";
		Returnval_="(NULL)";
		Information_="split a string by delimiter";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_join : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void){
		Name_="join";
		Format_="join Entry1 delimiter";
		Returnval_="(NULL)";
		Information_="join all strings in Entry1 by delimiter";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
#endif // KIS_SPLIT_H__
//---------------------------------------------------------------------------
#endif

