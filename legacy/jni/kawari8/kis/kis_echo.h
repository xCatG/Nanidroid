//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// KawariInlineScript -- エコー --
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.05.25  Phase 5.1     First version
//  2002.03.17  Phase 7.9.0   stdout
//
//---------------------------------------------------------------------------
// 関数テーブルへの登録
#ifdef INLINE_SCRIPT_REGIST
INLINE_SCRIPT_REGIST(KIS_echo);
INLINE_SCRIPT_REGIST(KIS_logprint);
#else
//---------------------------------------------------------------------------
#ifndef KIS_ECHO_H
#define KIS_ECHO_H
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include <fstream>
//---------------------------------------------------------------------------
#include "kis/kis_base.h"
//---------------------------------------------------------------------------
class KIS_echo : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="echo";
		Format_="echo Word1 ...";
		Returnval_="Word1 ...";
		Information_="return all arguments";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_logprint : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="logprint";
		Format_="logprint Word1 ...";
		Returnval_="(NULL)";
		Information_="output all arguments to logging stream";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
#endif
//---------------------------------------------------------------------------
#endif

