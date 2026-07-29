//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// 中間コード(演算式)
//
//      Programed by Suikyo.
//
//  2002.04.18  Phase 8.0.0   演算式中間コード作成
//
//---------------------------------------------------------------------------
#ifndef CODE_EXPR_H__
#define CODE_EXPR_H__
//---------------------------------------------------------------------------
#include "libkawari/kawari_code.h"
//---------------------------------------------------------------------------
#include <string>
//---------------------------------------------------------------------------
// 値(内容変更不可)
class TValue {
private:
	std::string str;
	int i; bool b;
	enum { vSTRING, vINTEGER, vBOOL, vERROR } state;
	TValue(void) : str(""), i(0), b(true), state(vERROR) {}
	TValue &operator = (const TValue &val);
public:
	inline const std::string &AsString(void) { return str; }
	inline int AsInteger(void);
	inline bool CanInteger(void);
	inline bool IsTrue(void);
	inline bool IsError(void) { return (state==vERROR); }
	inline TValue(const std::string &orgsen):str(orgsen), i(0), b(true), state(vSTRING){}
	inline TValue(const int orgi);
	inline TValue(const TValue &val):str(val.str), i(val.i), b(val.b), state(val.state) {}
	inline TValue(bool bv){
		if(bv){ str="true"; b=true; } else { str="false"; b=false; }
		i=0; state=vBOOL;
	}
	static inline TValue Error(void);
};
//--------------------------------------------------------------------------
// class TKVMExprCode_base;
// 演算式中間コードの基底クラス
class TKVMExprCode_base : public TKVMCode_base {
public:
	// 実行
	virtual std::string Run(class TKawariVM &vm);
	// 式評価
	virtual class TValue Evaluate(class TKawariVM &vm)=0;
	// デストラクタ
	virtual ~TKVMExprCode_base () {}
};

//--------------------------------------------------------------------------
// class TKVMExprBinaryCode_base;
// 二項演算子の基底クラス
class TKVMExprBinaryCode_base : public TKVMExprCode_base {
protected:
	TKVMExprCode_base *lhs;
	TKVMExprCode_base *rhs;
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
	TKVMExprBinaryCode_base(TKVMExprCode_base *l, TKVMExprCode_base *r) : lhs(l), rhs(r) {}
	// デストラクタ
	virtual ~TKVMExprBinaryCode_base(void){
		if (lhs) delete lhs;
		if (rhs) delete rhs;
	}
};

//--------------------------------------------------------------------------
// class TKVMExprBinaryCode_base;
// 単項演算子の基底クラス
class TKVMExprUnaryCode_base : public TKVMExprCode_base {
protected:
	TKVMExprCode_base *code;
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
	TKVMExprUnaryCode_base(TKVMExprCode_base *c) : code(c) {}
	// デストラクタ
	virtual ~TKVMExprUnaryCode_base(void){
		if (code) delete code;
	}
};

//--------------------------------------------------------------------------
// 式
class TKVMCodeExpression : public TKVMCode_base {
	TKVMExprCode_base *code;
public:
	// 実行
	virtual std::string Run(TKawariVM &vm);
	// 序列 同じクラスの場合のみ呼ばれる。
	virtual bool Less(const TKVMCode_base& r_) const;
	// 逆コンパイル
	virtual std::string DisCompile(void) const{
		return "$["+code->DisCompile()+"]";
	}
	// 演算式のみ逆コンパイル
	virtual std::string DisCompileExpression(void) const{
		return code->DisCompile();
	}
	// デバッグ用ツリー表示
	virtual std::ostream &Debug(std::ostream& os, unsigned int level=0) const;
	// コンストラクタ
	TKVMCodeExpression(TKVMExprCode_base *c) : code(c) {}
	// デストラクタ
	virtual ~TKVMCodeExpression(void){
		if (code) delete code;
	}
};

// 演算子の優先度
// [高い]
//    **(累乗)
//    -(単項) +(単項) !(not) ~(補数)
//    * / %
//    + -
//    &(ビットAND)
//    |(ビットOR) ^(ビットXOR)
//    > >= < <=
//    = == != =~ !~
//    &&
//    ||
//    := : (代入: 考慮中)
// [低い]
//--------------------------------------------------------------------------
// Logical OR
class TKVMExprCodeLOR : public TKVMExprBinaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "||"; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodeLOR(TKVMExprCode_base *l, TKVMExprCode_base *r):TKVMExprBinaryCode_base(l, r) {}
};

//--------------------------------------------------------------------------
// Logical AND
class TKVMExprCodeLAND : public TKVMExprBinaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "&&"; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodeLAND(TKVMExprCode_base *l, TKVMExprCode_base *r):TKVMExprBinaryCode_base(l, r) {}
};

//--------------------------------------------------------------------------
// EQUAL
class TKVMExprCodeEQ : public TKVMExprBinaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "=="; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodeEQ(TKVMExprCode_base *l, TKVMExprCode_base *r):TKVMExprBinaryCode_base(l, r) {}
};

//--------------------------------------------------------------------------
// NOT EQUAL
class TKVMExprCodeNEQ : public TKVMExprBinaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "!="; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodeNEQ(TKVMExprCode_base *l, TKVMExprCode_base *r):TKVMExprBinaryCode_base(l, r) {}
};

//--------------------------------------------------------------------------
// MATCH
class TKVMExprCodeMATCH : public TKVMExprBinaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "=~"; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodeMATCH(TKVMExprCode_base *l, TKVMExprCode_base *r):TKVMExprBinaryCode_base(l, r) {}
};

//--------------------------------------------------------------------------
// NOT MATCH
class TKVMExprCodeNMATCH : public TKVMExprBinaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "!~"; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodeNMATCH(TKVMExprCode_base *l, TKVMExprCode_base *r):TKVMExprBinaryCode_base(l, r) {}
};

//--------------------------------------------------------------------------
// GREATER THAN
class TKVMExprCodeGT : public TKVMExprBinaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return ">"; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodeGT(TKVMExprCode_base *l, TKVMExprCode_base *r):TKVMExprBinaryCode_base(l, r) {}
};

//--------------------------------------------------------------------------
// GREATER OR EQUAL
class TKVMExprCodeGTE : public TKVMExprBinaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return ">="; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodeGTE(TKVMExprCode_base *l, TKVMExprCode_base *r):TKVMExprBinaryCode_base(l, r) {}
};

//--------------------------------------------------------------------------
// LESS THAN
class TKVMExprCodeLT : public TKVMExprBinaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "<"; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodeLT(TKVMExprCode_base *l, TKVMExprCode_base *r):TKVMExprBinaryCode_base(l, r) {}
};

//--------------------------------------------------------------------------
// LESS OR EQUAL
class TKVMExprCodeLTE : public TKVMExprBinaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "<="; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodeLTE(TKVMExprCode_base *l, TKVMExprCode_base *r):TKVMExprBinaryCode_base(l, r) {}
};

//--------------------------------------------------------------------------
// BIT OR
class TKVMExprCodeBOR : public TKVMExprBinaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "|"; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodeBOR(TKVMExprCode_base *l, TKVMExprCode_base *r):TKVMExprBinaryCode_base(l, r) {}
};

//--------------------------------------------------------------------------
// BIT XOR
class TKVMExprCodeBXOR : public TKVMExprBinaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "^"; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodeBXOR(TKVMExprCode_base *l, TKVMExprCode_base *r):TKVMExprBinaryCode_base(l, r) {}
};

//--------------------------------------------------------------------------
// BIT AND
class TKVMExprCodeBAND : public TKVMExprBinaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "&"; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodeBAND(TKVMExprCode_base *l, TKVMExprCode_base *r):TKVMExprBinaryCode_base(l, r) {}
};

//--------------------------------------------------------------------------
// PLUS
class TKVMExprCodePLUS : public TKVMExprBinaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "+"; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodePLUS(TKVMExprCode_base *l, TKVMExprCode_base *r):TKVMExprBinaryCode_base(l, r) {}
};

//--------------------------------------------------------------------------
// MINUS
class TKVMExprCodeMINUS : public TKVMExprBinaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "-"; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodeMINUS(TKVMExprCode_base *l, TKVMExprCode_base *r):TKVMExprBinaryCode_base(l, r) {}
};

//--------------------------------------------------------------------------
// MULTIPLY
class TKVMExprCodeMUL : public TKVMExprBinaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "*"; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodeMUL(TKVMExprCode_base *l, TKVMExprCode_base *r):TKVMExprBinaryCode_base(l, r) {}
};

//--------------------------------------------------------------------------
// DIVERSION
class TKVMExprCodeDIV : public TKVMExprBinaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "/"; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodeDIV(TKVMExprCode_base *l, TKVMExprCode_base *r):TKVMExprBinaryCode_base(l, r) {}
};

//--------------------------------------------------------------------------
// MODULER
class TKVMExprCodeMOD : public TKVMExprBinaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "%"; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodeMOD(TKVMExprCode_base *l, TKVMExprCode_base *r):TKVMExprBinaryCode_base(l, r) {}
};

//--------------------------------------------------------------------------
// Unary PLUS
class TKVMExprCodeUPLUS : public TKVMExprUnaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "+"; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodeUPLUS(TKVMExprCode_base *c):TKVMExprUnaryCode_base(c) {}
};

//--------------------------------------------------------------------------
// Unary MINUS
class TKVMExprCodeUMINUS : public TKVMExprUnaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "-"; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodeUMINUS(TKVMExprCode_base *c):TKVMExprUnaryCode_base(c) {}
};

//--------------------------------------------------------------------------
// NOT
class TKVMExprCodeNOT : public TKVMExprUnaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "!"; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodeNOT(TKVMExprCode_base *c):TKVMExprUnaryCode_base(c) {}
};

//--------------------------------------------------------------------------
// COMPLIMENTAL
class TKVMExprCodeCOMP : public TKVMExprUnaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "~"; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodeCOMP(TKVMExprCode_base *c):TKVMExprUnaryCode_base(c) {}
};

//--------------------------------------------------------------------------
// POWER
class TKVMExprCodePOW : public TKVMExprBinaryCode_base {
protected:
	virtual std::string GetOperator(void)const{ return "**"; }
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
	// コンストラクタ
	TKVMExprCodePOW(TKVMExprCode_base *l, TKVMExprCode_base *r):TKVMExprBinaryCode_base(l, r) {}
};
//--------------------------------------------------------------------------
// ExprWord
class TKVMExprCodeWord : public TKVMExprCode_base {
protected:
	TKVMCode_base *code;
public:
	// 式評価
	virtual TValue Evaluate(class TKawariVM &vm);
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
	// コンストラクタ
	TKVMExprCodeWord(TKVMCode_base *c) : code(c) {}
	// デストラクタ
	virtual ~TKVMExprCodeWord(void){
		if (code) delete code;
	}
};
//--------------------------------------------------------------------------
// '( 〜 )'
class TKVMExprCodeGroup : public TKVMExprCodeWord {
public:
	// 逆コンパイル
	virtual std::string DisCompile(void) const{
		return "("+code->DisCompile()+")";
	}
	// コンストラクタ
	TKVMExprCodeGroup(TKVMCode_base *c) : TKVMExprCodeWord(c) {}
	// デストラクタ
	~TKVMExprCodeGroup(void){ }
};
//---------------------------------------------------------------------------
#endif// CODE_EXPR_H__
