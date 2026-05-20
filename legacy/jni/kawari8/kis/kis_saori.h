//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// KawariInlineScript -- SAORI --
//
//  2002.03.28  created
//  2002.03.29  add callsaorix
//
//---------------------------------------------------------------------------
// 関数テーブルへの登録
#ifdef INLINE_SCRIPT_REGIST
INLINE_SCRIPT_REGIST(KIS_saoriregist);
INLINE_SCRIPT_REGIST(KIS_saorierase);
INLINE_SCRIPT_REGIST(KIS_saorilist);
INLINE_SCRIPT_REGIST(KIS_callsaori);
INLINE_SCRIPT_REGIST(KIS_callsaorix);
#else
//---------------------------------------------------------------------------
#ifndef KIS_SAORI_H
#define KIS_SAORI_H
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "kis/kis_base.h"
#include "misc/phttp.h"
//---------------------------------------------------------------------------
#include <vector>
#include <map>
using namespace std;
//---------------------------------------------------------------------------
class KIS_saoriregist : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="saoriregist";
		Format_="saoriregist dllname aliasname [ loadoption ]";
		Returnval_="(NULL)";
		Information_="register saorimodule";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_saorierase : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="saorierase";
		Format_="saorierase aliasname";
		Returnval_="(NULL)";
		Information_="unregister saorimodule";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_saorilist : public TKisFunction_base {
public:
	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="saorilist";
		Format_="saorilist stem";
		Returnval_="list of registered aliases";
		Information_="returns a list of registered aliases, ether loaded or not";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_callsaori : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="callsaori";
		Format_="callsaori saorimodulename [param0] ...";
		Returnval_="(dll specific)";
		Information_="invoke request to SAORI/1.0 module";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);

protected:
	virtual bool CallSaori(
		const string& saoriname, const vector<string>& args,
		TPHMessage &response);
};
//---------------------------------------------------------------------------
class KIS_callsaorix : public KIS_callsaori {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="callsaorix";
		Format_="callsaorix stem saorimodulename [param0] ...";
		Returnval_="(dll specific)";
		Information_="invoke request to SAORI/1.0 module, and store values to stem";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
#endif
//---------------------------------------------------------------------------
#endif
