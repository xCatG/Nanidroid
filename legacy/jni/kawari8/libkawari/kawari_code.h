//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// 中間コード
//
//      Programed by NAKAUE.T (Meister) / Suikyo.
//
//  2001.05.27  Phase 5.1     インタープリタ・コンパイラ化
//  2001.06.12  Phase 5.3.2   純粋仮想エントリにおけるコンテキストのバグ修正
//  2001.06.17  Phase 5.4     インラインスクリプト内の単語切り出しのバグ修正
//                            インラインスクリプト内の履歴参照のバグ修正
//                            逆コンパイラ
//  2002.03.10  Phase 7.9.0   辞書の直接アクセス禁止
//  2002.03.17                KIUに合わせてTKisEngineからTKawariVMに名称変更
//                            同じくTKawariCode召靴�ら�KVMCode召靴北松諒儿//  2002.03.18                KIUに合わせてTKawariCompiler分離
//  2002.04.18  Phase 8.0.0   中間コードクラス全取っ替え。
//
//---------------------------------------------------------------------------
#ifndef CODE_H__
#define CODE_H__
//---------------------------------------------------------------------------
#include <string>
#include <vector>
#include <iostream>
#include <typeinfo>
//---------------------------------------------------------------------------
class TKawariVM;			// VM

class TKVMCode_base;		// 中間コードのインターフェース
class TKVMCodeList_base;	// 中間コードのインターフェース(基底クラス)
class TKVMCodeList;			// 中間コード片

class TKVMCodeString;		// 文字列
class TKVMCodeIDString;		// ID文字列(逆コンパイル結果が異なる)
class TKVMCodeInlineScript;	// インラインスクリプト
class TKVMCodeScriptStatment;	// スクリプト文
class TKVMCodeEntryIndex;	// エントリ配列アクセス
//--------------------------------------------------------------------------
// class TKVMCode_base;
//
// Interface class.
// 中間コードのインターフェース
class TKVMCode_base {
public:
	// 実行
	virtual std::string Run(TKawariVM &vm)=0;
	// 逆コンパイル
	virtual std::string DisCompile(void) const { return ""; }
	// Debugで出力をそろえる
	virtual std::ostream &DebugIndent(std::ostream& os, unsigned int level=0) const;
	// デバッグ用ツリー表示
	virtual std::ostream &Debug(std::ostream& os, unsigned int level=0) const=0;
	// 序列 同じクラスの場合のみ呼ばれる。
	virtual bool Less(const TKVMCode_base& R_) const =0;
	// デストラクタ
	virtual ~TKVMCode_base () {};
};
//---------------------------------------------------------------------------
// Comparator (for STL)
class TKVMCode_baseP_Less {
public:
	bool operator()(const TKVMCode_base *L,const TKVMCode_base *R) const;
};
//--------------------------------------------------------------------------
typedef std::vector<TKVMCode_base *> TCodePVector;
//--------------------------------------------------------------------------
// class TKVMCodeList_base : public TKVMCode_base;
//
// Interface class.
// リストを持つ中間コードのインターフェース
// (良くない抽象化の例)
class TKVMCodeList_base : public TKVMCode_base{
public:
	std::vector<TKVMCode_base *> list;

	virtual bool Less(const TKVMCode_base &R_) const;
	virtual std::ostream &Debug(std::ostream& os, unsigned int level=0) const;
	virtual ~TKVMCodeList_base();
	TKVMCodeList_base (const std::vector<TKVMCode_base *> &tmplist);
protected:
	virtual const std::string GetName(void) const=0;
};

//--------------------------------------------------------------------------
// 中間コードリスト
// 持っているCodeを連続的に実行し、全ての結果を結合して出力
class TKVMCodeList : public TKVMCodeList_base {
public:
	virtual std::string Run(class TKawariVM &vm);
	virtual std::string DisCompile(void) const;
	virtual ~TKVMCodeList() {}
	TKVMCodeList(const std::vector<TKVMCode_base *> &tmplist) : TKVMCodeList_base(tmplist) {}
protected:
	virtual const std::string GetName(void) const { return ""; }
};

//--------------------------------------------------------------------------
// 文字列 (Literal)
// done.
class TKVMCodeString : public TKVMCode_base {
public:
	std::string s;

	virtual std::string Run(class TKawariVM &vm) { return s; }
	virtual std::string DisCompile(void) const;
	virtual bool Less(const TKVMCode_base &r_) const{
		const TKVMCodeString& r=dynamic_cast<const TKVMCodeString&>(r_);
		return(s<r.s);
	}
	virtual std::ostream &Debug(std::ostream& os, unsigned int level=0) const {
		return DebugIndent(os, level) << "S(" << s << ")" << std::endl;
	}
	virtual ~TKVMCodeString() {}
	TKVMCodeString(const std::string &str);
};

//--------------------------------------------------------------------------
// ID文字列 (IdLiteral)
class TKVMCodeIDString : public TKVMCodeString {
public:
	virtual std::string DisCompile(void) const {
		return s;
	}
	virtual std::ostream &Debug(std::ostream& os, unsigned int level=0) const {
		return DebugIndent(os, level) << "ID(" << s << ")" << std::endl;
	}
	TKVMCodeIDString(const std::string &str) : TKVMCodeString(str) {}
};

//--------------------------------------------------------------------------
// スクリプト文 ( WS ( Word WS ) * )
// 基本的にStatementと同じ。
class TKVMCodeScriptStatement : public TKVMCodeList_base {
public:
	virtual std::string GetArg0(void);
	virtual std::string Run(class TKawariVM &vm);
	virtual std::string DisCompile(void) const;
	virtual ~TKVMCodeScriptStatement() {}
	TKVMCodeScriptStatement(const std::vector<TKVMCode_base *> &tmplist):TKVMCodeList_base(tmplist) {}
protected:
	virtual const std::string GetName(void) const { return "ScriptStatement"; }
};

//--------------------------------------------------------------------------
// インラインスクリプト ( '$(' ScriptStatementSeq ') )
class TKVMCodeInlineScript : public TKVMCodeList_base {
public:
	virtual std::string Run(class TKawariVM &vm);
	virtual std::string DisCompile(void) const;
	virtual ~TKVMCodeInlineScript() {}
	TKVMCodeInlineScript(const std::vector<TKVMCode_base *> &tmplist):TKVMCodeList_base(tmplist) {}
protected:
	virtual const std::string GetName(void) const { return "InlineScriptSubst"; }
};

//--------------------------------------------------------------------------
// 添え字付きエントリ呼び出し
// ( '$' EntryWord '[' WS Expr WS ']' )
class TKVMCodeEntryIndex : public TKVMCode_base {
public:
	TKVMCode_base *entry_id;
	TKVMCode_base *expr;	// 本来TKawariExprが入る

	virtual std::string Run(class TKawariVM &vm);
	virtual std::string DisCompile(void) const;
	virtual bool Less(const TKVMCode_base &R_) const;
	virtual std::ostream &Debug(std::ostream& os, unsigned int level=0) const;
	virtual ~TKVMCodeEntryIndex();
	TKVMCodeEntryIndex(TKVMCode_base *eid, TKVMCode_base *e):entry_id(eid), expr(e) {}
};
//--------------------------------------------------------------------------
#endif // CODE_H__
