//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// KawariInlineScript基本クラス
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.05.14  Phase 0.50    インラインスクリプト整理
//  2001.05.24  Phase 5.1     インタープリタ・コンパイラ化
//  2002.03.17  Phase 7.9.0   TKisEngineからKIUに合わせてTKawariVMに名称変更
//  2002.03.19  Phase 8.0     今度はTKawariEngineに変更
//
//---------------------------------------------------------------------------
#ifndef KIS_BASE_H
#define KIS_BASE_H
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "libkawari/kawari_engine.h"
#include "libkawari/kawari_log.h"
//---------------------------------------------------------------------------
#include <cstdlib>
#include <string>
#include <vector>
using namespace std;
//---------------------------------------------------------------------------
// KIS関数の情報を表す構造体
struct TKisFunctionInfo {
	string name;			// 関数名
	string format;			// 文法
	string returnval;		// 戻り値
	string information;	// 解説
	TKisFunctionInfo (void) {}
	TKisFunctionInfo (const char *n, const char *f, const char *r, const char *i)
		: name(string(n)), format(string(f)),
		  returnval(string(r)), information(string(i)) {}
	TKisFunctionInfo &operator = (const TKisFunctionInfo &r){
		name=r.name;
		format=r.format;
		returnval=r.returnval;
		information=r.information;
		return (*this);
	}
};
//---------------------------------------------------------------------------
// インラインスクリプト関数の基本クラス
// (Th!rdのアイデアをリファイン)
class TKisFunction_base {
protected:

	// 関数情報
	char *Name_;
	char *Format_;
	char *Returnval_;
	char *Information_;

	// 華和梨エンジン
	class TKawariEngine *Engine;

	inline TKawariLogger &GetLogger(void) { return Engine->GetLogger(); }
public:

	TKisFunction_base(void)
	{
		Name_="none.";
		Format_="none.";
		Returnval_="none.";
		Information_="none.";
	}

	// ですとらくた
	virtual ~TKisFunction_base() { }

	// 関数情報取得
	const TKisFunctionInfo GetInformation (void) const {
		return TKisFunctionInfo(Name_, Format_, Returnval_, Information_);
	}

	const char* Name(void) const {return(Name_);}
	const char* Format(void) const {return(Format_);}
	const char* Returnval(void) const {return(Returnval_);}
	const char* Information(void) const {return(Information_);}

	// 華和梨エンジン設定
	void NotifyEngine(class TKawariEngine *engine)
	{
		Engine=engine;
	}

	// 初期化
	virtual bool Init(void)=0;

	// インタープリタ
	virtual string Function(const vector<string>& args)=0;

	// 引数指定エラーのログ出力
	bool AssertArgument(const vector<string>& args, const unsigned int min){
		using namespace kawari_log;
		bool ret=true;
		if(args.size()<min){
			if(GetLogger().Check(LOG_WARNING))
				GetLogger().GetStream() << "KIS[" << args[0] << "] error : too few arguments." << endl;
			ret=false;
		}
		if ((!ret)&&(GetLogger().Check(LOG_INFO)))
			GetLogger().GetStream() << "usage> " << Format() << endl;
		return ret;
	}

	// 引数指定エラーのログ出力
	bool AssertArgument(const vector<string>& args, const unsigned int min, const unsigned int max){
		using namespace kawari_log;
		bool ret=true;
		if(args.size()<min){
			if(GetLogger().Check(LOG_WARNING))
				GetLogger().GetStream() << "KIS[" << args[0] << "] error : too few arguments." << endl;
			ret=false;
		}else if(args.size()>max){
			if(GetLogger().Check(LOG_WARNING))
				GetLogger().GetStream() << "KIS[" << args[0] << "] error : too many arguments." << endl;
			ret=false;
		}
		if ((!ret)&&(GetLogger().Check(LOG_INFO)))
			GetLogger().GetStream() << "usage> " << Format() << endl;
		return ret;
	}
};
//---------------------------------------------------------------------------
#if 0
class KIS_SampleFunction : public TKisFunction_base {
public:

	// Initで名前その他の情報を設定してください
	virtual bool Init(void)
	{
		Name_="SampleFunction";
		Format_="SampleFunction [Arg1 ...]";
		Returnval_="string which join all argument";
		Information_="KawariInlineScript sample";

		return(true);
	}

	// インタープリタ
	virtual string Function(const vector<string>& args)
	{
		string ret;
		for(unsigned int i=0;i<args;i++) ret=ret+args[i]+" ";
		return(ret);
	}
};
// これで登録
INLINE_SCRIPT_REGIST(KIS_SampleFunction);
#endif
//---------------------------------------------------------------------------
#endif

