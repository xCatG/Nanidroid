//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// KawariInlineScript -- file attribute --
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.05.14   Phase 0.50    First version
//  2001.06.17   Phase6.0にあわせて修正 (NAKAUE.T)
//               load
//  2001.12.09  Phase 7.1.2   kis_textfileと統合
//  2002.03.17  Phase 7.9.0   Phase7.9に合わせて変更 (NAKAUE.T)
//                            textload仕様変更/textsave追加
//                            readdirで.と..を除外
//  2002.07.10  Phase 8.1.0   isdir/isexist/isfile追加
//
//---------------------------------------------------------------------------
// 関数テーブルへの登録
#ifdef INLINE_SCRIPT_REGIST
INLINE_SCRIPT_REGIST(KIS_save);
INLINE_SCRIPT_REGIST(KIS_savecrypt);
INLINE_SCRIPT_REGIST(KIS_load);
INLINE_SCRIPT_REGIST(KIS_textload);
INLINE_SCRIPT_REGIST(KIS_textsave);
INLINE_SCRIPT_REGIST(KIS_textappend);
INLINE_SCRIPT_REGIST(KIS_readdir);
INLINE_SCRIPT_REGIST(KIS_cncpath);
INLINE_SCRIPT_REGIST(KIS_dirname);
INLINE_SCRIPT_REGIST(KIS_filename);
INLINE_SCRIPT_REGIST(KIS_isdir);
INLINE_SCRIPT_REGIST(KIS_isfile);
INLINE_SCRIPT_REGIST(KIS_isexist);
#else
//---------------------------------------------------------------------------
#ifndef KIS_FILE_H
#define KIS_FILE_H
//---------------------------------------------------------------------------
#include "kis/kis_base.h"
//---------------------------------------------------------------------------
class KIS_save : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="save";
		Format_="save File1 Entry1 [Entry2...]";
		Returnval_="(NULL)";
		Information_="save entries to File1";

		return(true);
	}

	// インタープリタ
    virtual string Function(const vector<string>& args){
        Run(args, false);
        return "";
    }
protected:
    virtual void Run(const vector<string>& args, bool crypt);
};
//---------------------------------------------------------------------------
class KIS_savecrypt : public KIS_save {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="savecrypt";
		Format_="savecrypt File1 Entry1 [Entry2...]";
		Returnval_="(NULL)";
		Information_="save entries to File1 by encrypt expression";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args)
	{
		Run(args, true);
		return "";
	}
};
//---------------------------------------------------------------------------
class KIS_load : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="load";
		Format_="load File1";
		Returnval_="(NULL)";
		Information_="load dictionary file";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);

};
//---------------------------------------------------------------------------
class KIS_textload : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="textload";
		Format_="textload Entry1 File1";
		Returnval_="(NULL)";
		Information_="load textfile to Entry1";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_textsave : public TKisFunction_base {
public:

	// インタープリタの実体
	string Function_(const vector<string>& args,bool flag);

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="textsave";
		Format_="textsave File1 Entry1 [Entry2...]";
		Returnval_="(NULL)";
		Information_="save textfile by Entries";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args)
	{
		return(Function_(args,true));
	}
};
//---------------------------------------------------------------------------
class KIS_textappend : public KIS_textsave {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="textappend";
		Format_="textappend File1 Entry1 [Entry2...]";
		Returnval_="(NULL)";
		Information_="append textfile by Entries";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args)
	{
		return(Function_(args,false));
	}
};
//---------------------------------------------------------------------------
class KIS_readdir : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="readdir";
		Format_="readdir Entry1 Dir1";
		Returnval_="(NULL)";
		Information_="read directory entries and set to Entry1";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_cncpath : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="cncpath";
		Format_="cncpath path [ expath ]";
		Returnval_="path expression in canonical form";
		Information_="create canonical path expression";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_dirname : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="dirname";
		Format_="dirname path";
		Returnval_="base directory";
		Information_="get directory part of path";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_filename : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="filename";
		Format_="filename path";
		Returnval_="filename";
		Information_="get filename part of path";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_isdir : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="isdir";
		Format_="isdir path";
		Returnval_="true or false";
		Information_="answer if path is directory or not";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_isfile : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="isfile";
		Format_="isfile path";
		Returnval_="true or false";
		Information_="answer if path is file or not";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
class KIS_isexist : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="isexist";
		Format_="isexist path";
		Returnval_="true or false";
		Information_="answer if path is exist or not";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args);
};
//---------------------------------------------------------------------------
#endif	// KIS_FILE_H
//---------------------------------------------------------------------------
#endif	// INLINE_SCRIPT_REGIST

