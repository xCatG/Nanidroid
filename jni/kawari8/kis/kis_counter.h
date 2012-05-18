//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// KawariInlineScript -- カウンタ --
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.05.30  Phase 5.1     First version
//
//---------------------------------------------------------------------------
// 関数テーブルへの登録
#ifdef INLINE_SCRIPT_REGIST
INLINE_SCRIPT_REGIST(KIS_inc);
INLINE_SCRIPT_REGIST(KIS_dec);
#else
//---------------------------------------------------------------------------
#ifndef KIS_COUNTER_H
#define KIS_COUNTER_H
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "kis/kis_base.h"
//---------------------------------------------------------------------------
class KIS_inc : public TKisFunction_base {
protected:

	virtual string Function_(const vector<string>& args,bool flag);

public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="inc";
		Format_="inc Index1 increment upperbound";
		Returnval_="(NULL)";
		Information_="increase a value of Index1 in an increment";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args)
	{
		return(Function_(args,false));
	}
};
//---------------------------------------------------------------------------
class KIS_dec : public KIS_inc {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="dec";
		Format_="dec Index1 decrement lowerbound";
		Returnval_="(NULL)";
		Information_="decrease a value of Index1 in a decrement";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args)
	{
		return(Function_(args,true));
	}
};
//---------------------------------------------------------------------------
#endif
//---------------------------------------------------------------------------
#endif

