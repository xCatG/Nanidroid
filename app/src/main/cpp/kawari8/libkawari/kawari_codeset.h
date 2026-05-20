//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// 中間コード(集合演算式)
//
//      Programed by Suikyo.
//
//  2002.04.18  Phase 8.0.0   集合演算式中間コード作成
//
//---------------------------------------------------------------------------
#ifndef KAWARI_CODESET_H__
#define KAWARI_CODESET_H__
//---------------------------------------------------------------------------
#include "libkawari/kawari_code.h"
#include "libkawari/kawari_dict.h"
//---------------------------------------------------------------------------
#include <string>
#include <set>
//---------------------------------------------------------------------------
// 集合演算式中間コードの基底クラス
class TKVMSetCode_base : public TKVMCode_base {
public:
	// 実行
	virtual std::string Run(class TKawariVM &vm);
	// 式評価
	virtual void Evaluate(class TKawariVM &vm, std::set<TWordID> &wordcol)=0;
	// デストラクタ
	virtual ~TKVMSetCode_base () {}
};

//---------------------------------------------------------------------------
// 集合演算式二項演算子コードの基底クラス
class TKVMSetBinaryCode_base : public TKVMSetCode_base {
protected:
	TKVMSetCode_base *lhs;
	TKVMSetCode_base *rhs;
	// 演算子文字列を返す
	virtual std::string GetOperator(void)const=0;
public:
	// 序列 同じクラスの場合のみ呼ばれる。
	virtual bool Less(const TKVMCode_base& r_) const;
	// 逆コンパイル
	virtual std::string DisCompile(void) const;
	// デバッグ用ツリー表示
	virtual std::ostream &Debug(std::ostream& os, unsigned int level=0) const;
	// コンストラクタ
	TKVMSetBinaryCode_base(TKVMSetCode_base *l, TKVMSetCode_base *r) : lhs(l), rhs(r) {}
	// デストラクタ
	virtual ~TKVMSetBinaryCode_base(void){
		if (lhs) delete lhs;
		if (rhs) delete rhs;
	}
};

//---------------------------------------------------------------------------
// 和
class TKVMSetCodePLUS : public TKVMSetBinaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "+"; }
public:
	// 式評価
	virtual void Evaluate(class TKawariVM &vm, std::set<TWordID> &wordcol);
	// コンストラクタ
	TKVMSetCodePLUS(TKVMSetCode_base *l, TKVMSetCode_base *r):TKVMSetBinaryCode_base(l, r) {}
};
//---------------------------------------------------------------------------
// 差
class TKVMSetCodeMINUS : public TKVMSetBinaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "-"; }
public:
	// 式評価
	virtual void Evaluate(class TKawariVM &vm, std::set<TWordID> &wordcol);
	// コンストラクタ
	TKVMSetCodeMINUS(TKVMSetCode_base *l, TKVMSetCode_base *r):TKVMSetBinaryCode_base(l, r) {}
};
//---------------------------------------------------------------------------
// 積
class TKVMSetCodeAND : public TKVMSetBinaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "&"; }
public:
	// 式評価
	virtual void Evaluate(class TKawariVM &vm, std::set<TWordID> &wordcol);
	// コンストラクタ
	TKVMSetCodeAND(TKVMSetCode_base *l, TKVMSetCode_base *r):TKVMSetBinaryCode_base(l, r) {}
};
//---------------------------------------------------------------------------
// Set Expression Word
class TKVMSetCodeWord : public TKVMSetCode_base {
	TKVMCode_base *code;
public:
	// 式評価
	virtual void Evaluate(class TKawariVM &vm, std::set<TWordID> &wordcol);
	// 序列 同じクラスの場合のみ呼ばれる。
	virtual bool Less(const TKVMCode_base& r_) const;
	// 逆コンパイル
	virtual std::string DisCompile(void) const{
		return code->DisCompile();
	}
	// デバッグ用ツリー表示
	virtual std::ostream &Debug(std::ostream& os, unsigned int level=0) const{
		return code->Debug(os, level);
	}
	// もしcodeがTKVMCodeIDStringならば、それを返す。
	const TKVMCodeIDString *GetIfPVW(void) const;
	// コンストラクタ
	TKVMSetCodeWord(TKVMCode_base *c) : code(c) {}
	// デストラクタ
	~TKVMSetCodeWord(void){
		if (code) delete code;
	}
};
//---------------------------------------------------------------------------
// エントリ呼び出し簡易版 ( '${' EntryExpr '}' )
// 簡易版done.
class TKVMCodeEntryCall : public TKVMCode_base {
	TKVMSetCode_base *code;
public:
	// 実行
	virtual std::string Run(class TKawariVM &vm);
	// 序列 同じクラスの場合のみ呼ばれる。
	virtual bool Less(const TKVMCode_base& r_) const;
	// 逆コンパイル
	virtual std::string DisCompile(void) const;
	// デバッグ用ツリー表示
	virtual std::ostream &Debug(std::ostream& os, unsigned int level=0) const;
	// コンストラクタ
	TKVMCodeEntryCall(TKVMSetCode_base *c) : code(c) {}
	// デストラクタ
	virtual ~TKVMCodeEntryCall();
};

//---------------------------------------------------------------------------
// 純粋仮想単語(特殊エントリ呼び出し) ( '${' Literal '}' )
class TKVMCodePVW : public TKVMCode_base {
	std::string entry;
public:
	// 参照エントリ名を返す
	virtual std::string Get(void) { return entry; }
	// 実行
	virtual std::string Run(class TKawariVM &vm);
	// 序列 同じクラスの場合のみ呼ばれる。
	virtual bool Less(const TKVMCode_base& r_) const;
	// 逆コンパイル
	virtual std::string DisCompile(void) const;
	// デバッグ用ツリー表示
	virtual std::ostream &Debug(std::ostream& os, unsigned int level=0) const;
	// コンストラクタ
	TKVMCodePVW(const std::string &c) : entry(c) {}
	// デストラクタ
	virtual ~TKVMCodePVW() {}
};

//---------------------------------------------------------------------------
// 履歴参照 ( '${' Integer '}' )
class TKVMCodeHistoryCall : public TKVMCode_base {
	int index;
public:
	// 実行
	virtual std::string Run(class TKawariVM &vm);
	// 序列 同じクラスの場合のみ呼ばれる。
	virtual bool Less(const TKVMCode_base& r_) const;
	// 逆コンパイル
	virtual std::string DisCompile(void) const;
	// デバッグ用ツリー表示
	virtual std::ostream &Debug(std::ostream& os, unsigned int level=0) const;
	// コンストラクタ
	TKVMCodeHistoryCall(int i) : index(i) {}
	// デストラクタ
	virtual ~TKVMCodeHistoryCall() {}
};

//---------------------------------------------------------------------------
#endif // KAWARI_CODESET_H__
