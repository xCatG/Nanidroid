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
#include "config.h"
//---------------------------------------------------------------------------
#include "libkawari/kawari_codeset.h"
#include "libkawari/kawari_engine.h"
#include "libkawari/kawari_vm.h"
#include "misc/misc.h"
//---------------------------------------------------------------------------
using namespace std;
//---------------------------------------------------------------------------
// 集合演算式中間コードの基底クラス

// 実行
string TKVMSetCode_base::Run(TKawariVM &vm){
	set<TWordID> wordset;
	Evaluate(vm, wordset);
	if (!wordset.size()) return "";

	unsigned int index=Random(wordset.size());
	set<TWordID>::iterator it=wordset.begin();
	for(unsigned int i=0; i<index; i++) it++;

	TKVMCode_base *code=vm.Dictionary().GetWordFromID(*it);
	if (!code) return "";
	string retstr=vm.RunWithNewContext(code);

	return retstr;
}

//---------------------------------------------------------------------------
// 集合演算式二項演算子コードの基底クラス

// 序列 同じクラスの場合のみ呼ばれる。
bool TKVMSetBinaryCode_base::Less(const TKVMCode_base& r_) const{
	const TKVMSetBinaryCode_base &r=dynamic_cast<const TKVMSetBinaryCode_base &>(r_);
	if(TKVMCode_baseP_Less()(lhs, r.lhs)) return (true);
	if(TKVMCode_baseP_Less()(r.lhs, lhs)) return (false);
	if(TKVMCode_baseP_Less()(rhs, r.rhs)) return (true);
	if(TKVMCode_baseP_Less()(r.rhs, rhs)) return (false);
	return (false);
}
// 逆コンパイル
string TKVMSetBinaryCode_base::DisCompile(void) const{
	if (!(lhs&&rhs)) return "";
	return lhs->DisCompile()+GetOperator()+rhs->DisCompile();
}
// デバッグ用ツリー表示
ostream &TKVMSetBinaryCode_base::Debug(ostream& os, unsigned int level) const{
	if (lhs) lhs->Debug(os, level+1);
	DebugIndent(os, level) << GetOperator() << endl;
	if (rhs) rhs->Debug(os, level+1);
	return os;
}

//---------------------------------------------------------------------------
// 和

// 式評価
void TKVMSetCodePLUS::Evaluate(TKawariVM &vm, set<TWordID> &wordcol){
	set<TWordID> l;
	set<TWordID> r;
	lhs->Evaluate(vm, l);
	rhs->Evaluate(vm, r);
	set<TWordID>::const_iterator lit=l.begin();
	set<TWordID>::const_iterator rit=r.begin();
	set<TWordID>::const_iterator l_e=l.end();
	set<TWordID>::const_iterator r_e=r.end();

	while ((lit!=l_e)&&(rit!=r_e)){
		if (*lit < *rit){
			wordcol.insert(*lit);
			++lit;
		}else if (*rit < *lit){
			wordcol.insert(*rit);
			++rit;
		}else{
			wordcol.insert(*lit);
			++lit;
			++rit;
		}
	}
	while (lit!=l_e)
		wordcol.insert(*(lit++));
	while (rit!=r_e)
		wordcol.insert(*(rit++));
}

//---------------------------------------------------------------------------
// 差

// 式評価
void TKVMSetCodeMINUS::Evaluate(TKawariVM &vm, set<TWordID> &wordcol){
	set<TWordID> l;
	set<TWordID> r;
	lhs->Evaluate(vm, l);
	rhs->Evaluate(vm, r);
	set<TWordID>::const_iterator lit=l.begin();
	set<TWordID>::const_iterator rit=r.begin();
	set<TWordID>::const_iterator l_e=l.end();
	set<TWordID>::const_iterator r_e=r.end();

	while ((lit!=l_e)&&(rit!=r_e)){
		if (*lit < *rit){
			wordcol.insert(*lit);
			++lit;
		}else if (*rit < *lit){
			++rit;
		}else{
			++lit;
			++rit;
		}
	}
	while (lit!=l_e)
		wordcol.insert(*(lit++));
}

//---------------------------------------------------------------------------
// 積

// 式評価
void TKVMSetCodeAND::Evaluate(TKawariVM &vm, set<TWordID> &wordcol){
	set<TWordID> l;
	set<TWordID> r;
	lhs->Evaluate(vm, l);
	rhs->Evaluate(vm, r);
	set<TWordID>::const_iterator lit=l.begin();
	set<TWordID>::const_iterator rit=r.begin();
	set<TWordID>::const_iterator l_e=l.end();
	set<TWordID>::const_iterator r_e=r.end();

	while (lit != l_e && rit != r_e){
		if (*lit < *rit){
			++lit;
		}else if (*rit < *lit){
			++rit;
		}else{
			wordcol.insert(*lit);
			++lit;
			++rit;
		}
	}
}

//---------------------------------------------------------------------------
// Set Expression Word

// 式評価
void TKVMSetCodeWord::Evaluate(TKawariVM &vm, set<TWordID> &wordcol){
	string entryname=code->Run(vm);
	TEntry entry=vm.Dictionary().GetEntry(entryname);
	if (!entry.IsValid()) return;
	vm.Dictionary().GetWordCollection(entry, wordcol);
}

// 序列 同じクラスの場合のみ呼ばれる。
bool TKVMSetCodeWord::Less(const TKVMCode_base& r_) const{
	const TKVMSetCodeWord &r=dynamic_cast<const TKVMSetCodeWord &>(r_);
	if(TKVMCode_baseP_Less()(code, r.code)) return (true);
	if(TKVMCode_baseP_Less()(r.code, code)) return (false);
	return false;
}

// もしcodeがTKVMCodeIDStringならば、その文字列を返す
const TKVMCodeIDString *TKVMSetCodeWord::GetIfPVW(void) const{
	const TKVMCodeIDString *cs=dynamic_cast<const TKVMCodeIDString *>(code);
	return cs;
}
//-------------------------------------------------------------------------
// エントリ呼び出し ( '${' EntryExpr '}' )

// 実行
string TKVMCodeEntryCall::Run(TKawariVM &vm){
	string retstr=code->Run(vm);
	vm.Dictionary().PushToHistory(retstr);
	return retstr;
}
// 逆コンパイル
string TKVMCodeEntryCall::DisCompile(void) const{
	return "${"+code->DisCompile()+"}";
}
// 序列 同じクラスの場合のみ呼ばれる。
bool TKVMCodeEntryCall::Less(const TKVMCode_base& r_) const{
	const TKVMCodeEntryCall &r=dynamic_cast<const TKVMCodeEntryCall &>(r_);
	if(TKVMCode_baseP_Less()(code, r.code)) return (true);
	if(TKVMCode_baseP_Less()(r.code, code)) return (false);
	return false;
}
// デバッグ用ツリー表示
ostream &TKVMCodeEntryCall::Debug(ostream& os, unsigned int level) const{
	DebugIndent(os, level) << "EntryCall(" << endl;
	code->Debug(os, level+1);
	return DebugIndent(os, level) << ")" << endl;
}
// デストラクタ
TKVMCodeEntryCall::~TKVMCodeEntryCall(){
	if (code) delete code;
}

//---------------------------------------------------------------------------
// 純粋仮想単語(特殊エントリ呼び出し) ( '${' Literal '}' )

// 実行
string TKVMCodePVW::Run(class TKawariVM &vm){
	TEntry eid=vm.Dictionary().GetEntry(entry);
	eid.AssertIfEmpty(entry);
	if (!eid.IsValid()) return "";

	unsigned int size=eid.Size();
	TWordID wid=eid.Index(Random(size));
	if (!wid) return "";

	TKVMCode_base *code=vm.Dictionary().GetWordFromID(wid);
	string retstr=vm.RunWithNewContext(code);

	vm.Dictionary().PushToHistory(retstr);

	return retstr;
}
// 序列 同じクラスの場合のみ呼ばれる。
bool TKVMCodePVW::Less(const TKVMCode_base& r_) const{
	const TKVMCodePVW &r=dynamic_cast<const TKVMCodePVW &>(r_);
	return (entry < r.entry);
}
// 逆コンパイル
string TKVMCodePVW::DisCompile(void) const{
	return "${"+entry+"}";
}
// デバッグ用ツリー表示
ostream &TKVMCodePVW::Debug(ostream& os, unsigned int level) const{
	DebugIndent(os, level) << "EntryCall[PVW](" << endl;
	DebugIndent(os, level+1) << entry << endl;
	return DebugIndent(os, level) << ")" << endl;
}

//---------------------------------------------------------------------------
// 履歴参照 ( '${' Integer '}' )

// 実行
string TKVMCodeHistoryCall::Run(class TKawariVM &vm){
	string retstr=vm.Dictionary().GetHistory(index);

	vm.Dictionary().PushToHistory(retstr);

	return retstr;
}
// 序列 同じクラスの場合のみ呼ばれる。
bool TKVMCodeHistoryCall::Less(const TKVMCode_base& r_) const{
	const TKVMCodeHistoryCall &r=dynamic_cast<const TKVMCodeHistoryCall &>(r_);
	return (index<r.index);
}
// 逆コンパイル
string TKVMCodeHistoryCall::DisCompile(void) const{
	return "${"+IntToString(index)+"}";
}
// デバッグ用ツリー表示
ostream &TKVMCodeHistoryCall::Debug(ostream& os, unsigned int level) const{
	DebugIndent(os, level) << "HistoryCall(" << endl;
	DebugIndent(os, level+1) << index << endl;
	return DebugIndent(os, level) << ")" << endl;
}
//---------------------------------------------------------------------------
