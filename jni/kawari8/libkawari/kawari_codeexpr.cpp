//--------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// 演算式
//
//      Programed by Suikyo.
//
//  2002.06.22  Phase 8.0.0   作成
//  2005.06.21  Phase 8.2.3   gcc3.xのwarning対応 (suikyo)
//
//--------------------------------------------------------------------------
#include "config.h"
//--------------------------------------------------------------------------
#include "libkawari/kawari_codeexpr.h"
#include "libkawari/kawari_engine.h"
#include "libkawari/kawari_vm.h"
#include "libkawari/kawari_log.h"
#include "libkawari/kawari_rc.h"
#include "misc/l10n.h"
#include "misc/misc.h"
using namespace kawari::resource;
//--------------------------------------------------------------------------
#include <cctype>
//#include <cmath>
#include <string>
#include <iostream>
using namespace std;
//-------------------------------------------------------------------------
// 例外処理は重いらしいので一生懸命フラグ読むですよ。
TValue TValue::Error(void){
	return TValue();
}
int TValue::AsInteger(void){
	if (CanInteger()) return i;
	return 0;
}
bool TValue::CanInteger(void){
	if (state==vERROR) return false;
	if ((state==vINTEGER)||(state==vBOOL)) return true;
	if (IsInteger(str)){
		state=vINTEGER;
		i=atoi(str.c_str());
		return true;
	}else{
		return false;
	}
}
bool TValue::IsTrue(void){
	if (state==vBOOL) return b;
	if (state==vERROR) return false;
	if (state==vINTEGER) return (i!=0);
	return ::IsTrue(str);
}
TValue::TValue(const int orgi):i(orgi), state(vINTEGER){
	str=IntToString(orgi);
}
//=========================================================================
// 式
// 実行
string TKVMCodeExpression::Run(TKawariVM &vm){
	return vm.RunWithCurrentContext(code);
}
// 序列 同じクラスの場合のみ呼ばれる。
bool TKVMCodeExpression::Less(const TKVMCode_base& r_) const{
	const TKVMCodeExpression &r=dynamic_cast<const TKVMCodeExpression &>(r_);
	if(TKVMCode_baseP_Less()(code, r.code)) return (true);
	if(TKVMCode_baseP_Less()(r.code, code)) return (false);
	return (false);
}
// デバッグ用ツリー表示
ostream &TKVMCodeExpression::Debug(ostream& os, unsigned int level) const{
	DebugIndent(os, level) << "Expression(" << endl;
	code->Debug(os, level+1);
	return DebugIndent(os, level) << ")" << endl;
}

//=========================================================================
// class TKVMExprCode_base;
// 演算式中間コードの基底クラス
string TKVMExprCode_base::Run(TKawariVM &vm){
	return Evaluate(vm).AsString();
}

//=========================================================================
// class TKVMExprBinaryCode_base;
// 二項演算子の基底クラス
// 序列 同じクラスの場合のみ呼ばれる。
bool TKVMExprBinaryCode_base::Less(const TKVMCode_base& r_) const{
	const TKVMExprBinaryCode_base &r=dynamic_cast<const TKVMExprBinaryCode_base &>(r_);
	if(TKVMCode_baseP_Less()(lhs, r.lhs)) return (true);
	if(TKVMCode_baseP_Less()(r.lhs, lhs)) return (false);
	if(TKVMCode_baseP_Less()(rhs, r.rhs)) return (true);
	if(TKVMCode_baseP_Less()(r.rhs, rhs)) return (false);
	return (false);
}
// 逆コンパイル
string TKVMExprBinaryCode_base::DisCompile(void) const{
	if (!(lhs&&rhs)) return "";
	return lhs->DisCompile()+GetOperator()+rhs->DisCompile();
}
// デバッグ用ツリー表示
ostream &TKVMExprBinaryCode_base::Debug(ostream& os, unsigned int level) const{
	if (lhs) lhs->Debug(os, level+1);
	DebugIndent(os, level) << GetOperator() << endl;
	if (rhs) rhs->Debug(os, level+1);
	return os;
}

//=========================================================================
// class TKVMExprUnaryCode_base;
// 単項演算子の基底クラス
// 序列 同じクラスの場合のみ呼ばれる。
bool TKVMExprUnaryCode_base::Less(const TKVMCode_base& r_) const{
	const TKVMExprUnaryCode_base &r=dynamic_cast<const TKVMExprUnaryCode_base &>(r_);
	if(TKVMCode_baseP_Less()(code, r.code)) return (true);
	if(TKVMCode_baseP_Less()(r.code, code)) return (false);
	return (false);
}
// 逆コンパイル
string TKVMExprUnaryCode_base::DisCompile(void) const{
	if (!code) return "";
	return GetOperator()+code->DisCompile();
}
// デバッグ用ツリー表示
ostream &TKVMExprUnaryCode_base::Debug(ostream& os, unsigned int level) const{
	if (!code) return os;
	DebugIndent(os, level) << GetOperator() << endl;
	return code->Debug(os, level+1);
}

#define COMP_FUNC(name, rel)\
	TValue name(TKawariVM &vm){\
		if (!lhs||!rhs) return TValue::Error();\
		TValue l=lhs->Evaluate(vm);\
		if (l.IsError()) return l;\
		TValue r=rhs->Evaluate(vm);\
		if (r.IsError()) return r;\
		if (l.CanInteger()&&r.CanInteger()){\
			return TValue(l.AsInteger() rel r.AsInteger()); \
		}else{\
			return TValue(l.AsString() rel r.AsString()); \
		}\
	}\


// KIU_TODO:ランタイムエラー表示
#define ARITH_FUNC(name, rel)\
	TValue name(TKawariVM &vm){\
		if (!lhs||!rhs) return TValue::Error();\
		TValue l=lhs->Evaluate(vm);\
		if (l.IsError()) return l;\
		TValue r=rhs->Evaluate(vm);\
		if (r.IsError()) return r;\
		if (!l.CanInteger()||!r.CanInteger()) return TValue::Error();\
		return TValue(l.AsInteger() rel r.AsInteger());\
	}\

#define ARITHDIV_FUNC(name, rel)\
	TValue name(TKawariVM &vm){\
		if (!lhs||!rhs) return TValue::Error();\
		TValue l=lhs->Evaluate(vm);\
		if (l.IsError()) return l;\
		TValue r=rhs->Evaluate(vm);\
		if (r.IsError()) return r;\
		if (!l.CanInteger()||!r.CanInteger()) return TValue::Error();\
		if (r.AsInteger()==0) { vm.GetLogger().GetStream(kawari_log::LOG_ERROR) << RC.S(ERR_CODEEXPR_DIVIDED_BY_ZERO) << endl; return TValue::Error(); }\
		return TValue(l.AsInteger() rel r.AsInteger());\
	}\


//--------------------------------------------------------------------------
// Logical OR
TValue TKVMExprCodeLOR::Evaluate(TKawariVM &vm){
	if ((!lhs)||(!rhs)) return TValue::Error();

	TValue t=lhs->Evaluate(vm);
	if (t.IsError()) return t;
	if (t.IsTrue()) return t;
	return rhs->Evaluate(vm);
}

//--------------------------------------------------------------------------
// Logical AND
TValue TKVMExprCodeLAND::Evaluate(TKawariVM &vm){
	if ((!lhs)||(!rhs)) return TValue::Error();

	TValue l=lhs->Evaluate(vm);
	if (l.IsError()) return l;
	if (!l.IsTrue()) return TValue(false);
	TValue r=rhs->Evaluate(vm);
	if (r.IsError()) return r;
	if (!r.IsTrue()) return TValue(false);
	return l;
}

//--------------------------------------------------------------------------
// EQUAL
COMP_FUNC(TKVMExprCodeEQ::Evaluate, ==)

//--------------------------------------------------------------------------
// NOT EQUAL
COMP_FUNC(TKVMExprCodeNEQ::Evaluate, !=)

//--------------------------------------------------------------------------
// MATCH
TValue TKVMExprCodeMATCH::Evaluate(TKawariVM &vm){
	if ((!lhs)||(!rhs)) return TValue::Error();

	TValue l=lhs->Evaluate(vm);
	if (l.IsError()) return l;
	TValue r=rhs->Evaluate(vm);
	if (r.IsError()) return r;
	return TValue(ctow(l.AsString()).find(ctow(r.AsString()))!=string::npos);
}

//--------------------------------------------------------------------------
// NOT MATCH
TValue TKVMExprCodeNMATCH::Evaluate(TKawariVM &vm){
	if ((!lhs)||(!rhs)) return TValue::Error();

	TValue l=lhs->Evaluate(vm);
	if (l.IsError()) return l;
	TValue r=rhs->Evaluate(vm);
	if (r.IsError()) return r;
	return TValue(ctow(l.AsString()).find(ctow(r.AsString()))==string::npos);
}

//--------------------------------------------------------------------------
// GREATER THAN
COMP_FUNC(TKVMExprCodeGT::Evaluate, >)

//--------------------------------------------------------------------------
// GREATER OR EQUAL
COMP_FUNC(TKVMExprCodeGTE::Evaluate, >=)

//--------------------------------------------------------------------------
// LESS THAN
COMP_FUNC(TKVMExprCodeLT::Evaluate, <)

//--------------------------------------------------------------------------
// LESS OR EQUAL
COMP_FUNC(TKVMExprCodeLTE::Evaluate, <=)

//--------------------------------------------------------------------------
// BIT OR
ARITH_FUNC(TKVMExprCodeBOR::Evaluate, |)

//--------------------------------------------------------------------------
// BIT XOR
ARITH_FUNC(TKVMExprCodeBXOR::Evaluate, ^)

//--------------------------------------------------------------------------
// BIT AND
ARITH_FUNC(TKVMExprCodeBAND::Evaluate, &)

//--------------------------------------------------------------------------
// PLUS
ARITH_FUNC(TKVMExprCodePLUS::Evaluate, +)

//--------------------------------------------------------------------------
// MINUS
ARITH_FUNC(TKVMExprCodeMINUS::Evaluate, -)

//--------------------------------------------------------------------------
// MULTIPLY
ARITH_FUNC(TKVMExprCodeMUL::Evaluate, *)

//--------------------------------------------------------------------------
// DIVERSION
ARITHDIV_FUNC(TKVMExprCodeDIV::Evaluate, /)

//--------------------------------------------------------------------------
// MODULER
ARITHDIV_FUNC(TKVMExprCodeMOD::Evaluate, %)

//--------------------------------------------------------------------------
// Unary PLUS
TValue TKVMExprCodeUPLUS::Evaluate(TKawariVM &vm){
	if (!code) return TValue::Error();
	return code->Evaluate(vm);
}

//--------------------------------------------------------------------------
// Unary MINUS
TValue TKVMExprCodeUMINUS::Evaluate(TKawariVM &vm){
	if (!code) return TValue::Error();
	TValue val=code->Evaluate(vm);
	if (val.IsError()) return val;
	if (!val.CanInteger()) return TValue::Error();
	return TValue(-1*val.AsInteger());
}

//--------------------------------------------------------------------------
// NOT
TValue TKVMExprCodeNOT::Evaluate(TKawariVM &vm){
	if (!code) return TValue::Error();
	TValue val=code->Evaluate(vm);
	if (val.IsError()) return val;
	return TValue(!val.IsTrue());
}

//--------------------------------------------------------------------------
// COMPLIMENTAL
TValue TKVMExprCodeCOMP::Evaluate(TKawariVM &vm){
	if (!code) return TValue::Error();
	TValue val=code->Evaluate(vm);
	if (val.IsError()) return val;
	if (!val.CanInteger()) return TValue::Error();
	return TValue(~ val.AsInteger());
}

//--------------------------------------------------------------------------
// POWER

namespace {
	// n>0, nは偶数
	int pow_local(int x, unsigned int n){
		if (n==1) return x;
		int tmp=pow_local(x, n>>1);
		return (n&0x01)?x*tmp*tmp:tmp*tmp;
	}
}

TValue TKVMExprCodePOW::Evaluate(TKawariVM &vm){
	if (!lhs||!rhs) return TValue::Error();
	TValue l=lhs->Evaluate(vm);
	if (l.IsError()) return l;
	TValue r=rhs->Evaluate(vm);
	if (r.IsError()) return r;
	if (!l.CanInteger()||!r.CanInteger()) return TValue::Error();
	int x=l.AsInteger();
	int n=r.AsInteger();
	if (n<0) return TValue::Error();
	else if (n==0) return TValue(0);
	return TValue(pow_local(x, (unsigned int)n));
}
//--------------------------------------------------------------------------
// ExprWord
TValue TKVMExprCodeWord::Evaluate(TKawariVM &vm){
	if (!code) return TValue::Error();
	string retstr=code->Run(vm);
	if (vm.IsOnException())
		return TValue::Error();
	else
		return TValue(retstr);
}
bool TKVMExprCodeWord::Less(const TKVMCode_base& r_) const{
	const TKVMExprCodeWord &r=dynamic_cast<const TKVMExprCodeWord &>(r_);
	if(TKVMCode_baseP_Less()(code, r.code)) return (true);
	if(TKVMCode_baseP_Less()(r.code, code)) return (false);
	return false;
}
//-------------------------------------------------------------------------
