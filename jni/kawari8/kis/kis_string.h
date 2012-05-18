//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// KawariInlineScript -- 文字列操作 --
//
//      Programed by Sky
//
//  2002.03.25  Phase 8        First version
//                             インデックスは全て0オリジンとなった。
//
//---------------------------------------------------------------------------
// 関数テーブルへの登録
#ifdef INLINE_SCRIPT_REGIST
INLINE_SCRIPT_REGIST(KIS_substr);
INLINE_SCRIPT_REGIST(KIS_length);
INLINE_SCRIPT_REGIST(KIS_match);
INLINE_SCRIPT_REGIST(KIS_match_at);
INLINE_SCRIPT_REGIST(KIS_char_at);
INLINE_SCRIPT_REGIST(KIS_rmatch);
INLINE_SCRIPT_REGIST(KIS_sub);
INLINE_SCRIPT_REGIST(KIS_rsub);
INLINE_SCRIPT_REGIST(KIS_gsub);
INLINE_SCRIPT_REGIST(KIS_reverse);
INLINE_SCRIPT_REGIST(KIS_tr);
INLINE_SCRIPT_REGIST(KIS_compare);
#else
//---------------------------------------------------------------------------
#ifndef KIS_STRING_H
#define KIS_STRING_H
//---------------------------------------------------------------------------
#include "kis/kis_base.h"
//---------------------------------------------------------------------------
// 旧expr系
//---------------------------------------------------------------------------
class KIS_substr : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="substr";
		Format_="substr TARGET START [ LENGTH ]";
		Returnval_="substring";
		Information_="cut TARGET out from START by LENGTH (or end of TARGET)";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_length : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="length";
		Format_="length TARGET";
		Returnval_="length of argument string.";
		Information_="returns length of TARGET.";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_match : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="match";
		Format_="match STRING PATTERN [ INDEX ]";
		Returnval_="position of PATTERN in STRING or -1 if not found";
		Information_="search PATTERN in STRING";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
// 新規
//---------------------------------------------------------------------------
class KIS_match_at : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="match_at";
		Format_="match_at STRING PATTERN [ INDEX ]";
		Returnval_="\"1\" if match, \"\" if not match.";
		Information_="returns if PATTERN matches with STRING at INDEX";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_rmatch : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="rmatch";
		Format_="rmatch STRING PATTERN [ INDEX ]";
		Returnval_="last position of PATTERN in STRING or -1 if not found";
		Information_="search last PATTERN in STRING";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_char_at : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="char_at";
		Format_="char_at STRING INDEX";
		Returnval_="character in STRING at specified INDEX";
		Information_="get character at specified index of string";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_sub : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="sub";
		Format_="sub STRING PATTERN REPLACE";
		Returnval_="replaced STRING";
		Information_="substitute PATTERN found first in STRING with REPLACE";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_rsub : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="rsub";
		Format_="rsub STRING PATTERN REPLACE";
		Returnval_="replaced STRING";
		Information_="substitute PATTERN found last in STRING with REPLACE";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_gsub : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="gsub";
		Format_="gsub STRING PATTERN REPLACE";
		Returnval_="replaced STRING";
		Information_="substitute all PATTERNs found in STRING with REPLACE";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_reverse : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="reverse";
		Format_="reverse STRING";
		Returnval_="reversed STRING";
		Information_="reverse order of characters in STRING";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_tr : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="tr";
		Format_="tr STRING SEARCH REPLACE";
		Returnval_="transliterated STRING";
		Information_="do perl tr///d action";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_compare : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="compare";
		Format_="compare STRING1 STRING2";
		Returnval_="1 if STRING1 is greater than STRING2. 0 if equqals. -1 if STRING2 is greater than STRING1";
		Information_="compare two STRINGs";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
#endif // KIS_STRING_H
//---------------------------------------------------------------------------
#endif // INLINE_SCRIPT_REGIST
