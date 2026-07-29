//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// KawariInlineScript -- 数学 --
//
//      Programed by Kouji.U (SUIKYO)
//
//  2001.06.17  Phase 5.4     
//
//---------------------------------------------------------------------------
// 関数テーブルへの登録
#ifdef INLINE_SCRIPT_REGIST
INLINE_SCRIPT_REGIST(KIS_rand);
INLINE_SCRIPT_REGIST(KIS_srand);
#else
//---------------------------------------------------------------------------
#ifndef KIS_MATH_H
#define KIS_MATH_H
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include <string>
#include <cstdlib>
using namespace std;
//---------------------------------------------------------------------------
#include "misc/misc.h"
#include "kis/kis_base.h"
//---------------------------------------------------------------------------
class KIS_rand : public TKisFunction_base {
public:
    // Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="rand";
		Format_="rand MAX";
		Returnval_="(NULL)";
		Information_="return a random number (0 to MAX-1)";

		return(true);
	}

	// インタープリタ
    virtual string Function(const vector<string>& args){
        if (args.size()>=2) 
            return (IntToString(Random(atoi(args[1].c_str()))));
        else
            return ("");
    }

};
//---------------------------------------------------------------------------
class KIS_srand : public TKisFunction_base {
public:
    // Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="srand";
		Format_="srand SEED";
		Returnval_="(NULL)";
		Information_="give a seed to random number generator";

		return(true);
	}

	// インタープリタ
    virtual string Function(const vector<string>& args){
		if (args.size()>=2) 
			SRandom(atoi(args[1].c_str()));
		return ("");
    }

};
//---------------------------------------------------------------------------
#endif
//---------------------------------------------------------------------------
#endif
