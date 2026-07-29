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
//                            同じくTKawariCodeからTKVMCodeに名称変更
//  2002.03.18                KIUに合わせてTKawariCompiler分離
//  2002.04.18  Phase 8.0.0   中間コードクラス全取っ替え。
//  2003.01.07  Phase 8.1.0   function定義の獲得、削除を追加
//  2004.01.04  Phase 8.2.0   continue追加
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "libkawari/kawari_code.h"
#include "libkawari/kawari_codeexpr.h"
#include "libkawari/kawari_vm.h"
#include "libkawari/kawari_engine.h"
#include "misc/l10n.h"
#include "misc/misc.h"
//---------------------------------------------------------------------------
#include <cctype>
//---------------------------------------------------------------------------
using namespace std;
//---------------------------------------------------------------------------
// Comparator (for STL)
bool TKVMCode_baseP_Less::operator()(const TKVMCode_base *L,const TKVMCode_base *R) const{
	if(typeid(*L)==typeid(*R)) return (L->Less(*R));
	return(string(typeid(*L).name())<string(typeid(*R).name()));
}

//---------------------------------------------------------------------------
// 中間コードの基底クラス
ostream& TKVMCode_base::DebugIndent(ostream& os,unsigned int level) const
{
	for(unsigned int i=0;i<level;i++) os << "  ";
	return(os);
}

//---------------------------------------------------------------------------
// 中間コードの基底クラス(リストを持つもの)
bool TKVMCodeList_base::Less(const TKVMCode_base &r_) const{
	const TKVMCodeList_base& r=dynamic_cast<const TKVMCodeList_base&>(r_);
	unsigned int lsize=list.size();
	if(lsize!=r.list.size()) return(lsize<r.list.size());

	for(unsigned int i=0;i<lsize;i++) {
		if (TKVMCode_baseP_Less()(list[i], r.list[i])) return(true);
		if (TKVMCode_baseP_Less()(r.list[i], list[i])) return(false);
	}
	return(false);
}
ostream &TKVMCodeList_base::Debug(ostream& os, unsigned int level) const{
	DebugIndent(os, level) << GetName() << "(" << endl;
	for (TCodePVector::const_iterator it=list.begin(); it!=list.end(); it++){
		if (*it)
			(*it)->Debug(os, level+1);
		// ASSERTすべきだなぁ
	}
	return DebugIndent(os, level) << ")" << endl;
}

TKVMCodeList_base::TKVMCodeList_base(const vector<TKVMCode_base *> &tmplist){
	list.insert(list.end(), tmplist.begin(), tmplist.end());
	list.resize(list.size());
}

TKVMCodeList_base::~TKVMCodeList_base(){
	for (TCodePVector::iterator it=list.begin(); it!=list.end(); it++){
		if (*it)
			delete (*it);
	}
}

//---------------------------------------------------------------------------
// 中間コードリスト
// 持っているCodeを連続的に実行し、全ての結果を結合して出力
string TKVMCodeList::Run(TKawariVM &vm){
	string retstr;
	// ここでVMの例外チェック
	for (TCodePVector::iterator it=list.begin(); (it!=list.end())&&(!vm.IsOnException()); it++){
		retstr+=(*it)->Run(vm);
	}
	return retstr;
}

string TKVMCodeList::DisCompile(void) const{
	string retstr;
	for (TCodePVector::const_iterator it=list.begin(); it!=list.end(); it++){
		retstr+=(*it)->DisCompile();
	}
	return retstr;
}

//--------------------------------------------------------------------------
// 文字列 (Literal)
TKVMCodeString::TKVMCodeString(const string &str) : s(str)
{ }

string TKVMCodeString::DisCompile(void) const{
	const static wstring illchs=ctow("\\\"");
	const static wstring wbs=ctow("\\");
	const static wstring wdq=ctow("\"");
	wstring ws=ctow(s);
	wstring retstr=ctow("\"");
	unsigned int len=ws.size();
	unsigned int pos=0;

	while(pos<len) {
		unsigned int pos2=ws.find_first_of(illchs, pos);
		if (pos2==string::npos) {
			retstr+=ws.substr(pos);
			break;
		}else{
			retstr+=ws.substr(pos, pos2-pos)+wbs+ws[pos2];
			pos=pos2+1;
		}
	}

	retstr+=wdq;
	return(wtoc(retstr));
}

//--------------------------------------------------------------------------
// スクリプト文 ( WS ( Word WS ) * )
// 最初の単語がTKVMCodeStringの時、内容を返す
string TKVMCodeScriptStatement::GetArg0(void){
	if (!list.size()) return "";
	TKVMCodeString *str=dynamic_cast<TKVMCodeString *>(list[0]);
	if (!str) return "";
	return str->s;
}

string TKVMCodeScriptStatement::Run(TKawariVM &vm){
	if (!list.size()) return "";
	string arg0=GetArg0();
	string retstr;
	// 2段パースは気持ち悪いよぅ。
	if(arg0=="NULL") {
		retstr="";
	}else if(arg0=="break"){
		if (vm.Dictionary().CurrentLoop()){
			vm.SetState(TKawariVM::InterpState(TKawariVM::InterpState::BREAK, ""));
		}else{
			// error
			vm.GetLogger().GetStream(kawari_log::LOG_ERROR)
				<< RC.S(kawari::resource::ERR_CODE_BREAK_OUTOF_LOOP) << endl;
		}
		retstr="";
	}else if(arg0=="continue"){
		if (vm.Dictionary().CurrentLoop()){
			vm.SetState(TKawariVM::InterpState(TKawariVM::InterpState::CONTINUE, ""));
		}else{
			// error
			vm.GetLogger().GetStream(kawari_log::LOG_ERROR)
				<< RC.S(kawari::resource::ERR_CODE_CONTINUE_OUTOF_LOOP) << endl;
		}
		retstr="";
	}else if(arg0=="return"){
		if(list.size()==2){
			unsigned int frame=vm.Dictionary().LinkFrame();
			vm.SetState(TKawariVM::InterpState(
				TKawariVM::InterpState::RETURN, list[1]->Run(vm)));
			vm.Dictionary().UnlinkFrame(frame);
		}else{
			vm.SetState(TKawariVM::InterpState(
				TKawariVM::InterpState::RETURN, "", false));
		}
	}else if(arg0=="function"){
		if(list.size()==3){
			string funcname=vm.RunWithCurrentContext(list[1]);
			// 確実なコピーは逆コンパイルしかない。
			string funcdecl=list[2]->DisCompile();
			if (funcname.size()&&funcdecl.size()){
				TEntry entry=vm.Dictionary().CreateEntry(
					TKawariVM::SYSTEM_FUNCTION_PREFIX+funcname);
				TWordID id=vm.Dictionary().CreateWord(
					TKawariCompiler::Compile(funcdecl, vm.GetLogger()));
				entry.Clear();
				entry.Push(id);
			}
		}else if(list.size()==2){
			string funcname=vm.RunWithCurrentContext(list[1]);
			if (funcname.size()){
				TEntry entry=vm.Dictionary().GetEntry(
					TKawariVM::SYSTEM_FUNCTION_PREFIX+funcname);
				if (entry.Size()){
					TWordID wid=entry.Index();
					TKVMCode_base *code=vm.Dictionary().GetWordFromID(wid);
					retstr=code->DisCompile();
				}
			}
		}else{
			// エラー
		}
	}else if(arg0=="rmfunc"){
		if (list.size()==2){
			string funcname=vm.RunWithCurrentContext(list[1]);
			if (funcname.size())
				vm.Dictionary().GetEntry(
					TKawariVM::SYSTEM_FUNCTION_PREFIX+funcname).Clear();
		}
	}else if(arg0=="while"){
		if(list.size()==3) {
			unsigned int frame=vm.Dictionary().LinkFrame();
			vm.Dictionary().StartLoop();
			while(!vm.IsOnWeakException()) {
				// 式文の評価(第1パラメータ)
				string cond=list[1]->Run(vm);
				if (!IsTrue(cond)) break;
				vm.Dictionary().UnlinkFrame(frame);
				vm.Dictionary().PushToHistory(cond);
				// trueの時(第2パラメータ)
				retstr+=vm.RunWithCurrentContext(list[2]);
				vm.ResetState(TKawariVM::InterpState::CONTINUE);
			}
			vm.ResetState(TKawariVM::InterpState::BREAK);
			vm.Dictionary().EndLoop();
			vm.Dictionary().UnlinkFrame(frame);
			vm.Dictionary().PushToHistory(retstr);
		} else {
			// エラー
		}
	}else if(arg0=="until"){
		if(list.size()==3) {
			unsigned int frame=vm.Dictionary().LinkFrame();
			vm.Dictionary().StartLoop();
			// 式文の評価(第1パラメータ)
			while(!vm.IsOnWeakException()) {
				string cond=list[1]->Run(vm);
				if (IsTrue(cond)) break;
				vm.Dictionary().UnlinkFrame(frame);
				vm.Dictionary().PushToHistory(cond);

				retstr+=vm.RunWithCurrentContext(list[2]);
				vm.ResetState(TKawariVM::InterpState::CONTINUE);
			}
			vm.ResetState(TKawariVM::InterpState::BREAK);
			vm.Dictionary().EndLoop();
			vm.Dictionary().UnlinkFrame(frame);
			vm.Dictionary().PushToHistory(retstr);
		} else {
			// エラー
		}
	}else if(arg0=="loop"){
		if(list.size()==3) {
			unsigned int frame=vm.Dictionary().LinkFrame();
			vm.Dictionary().StartLoop();
			// 回数の評価(第1パラメータ)
			int limit=atoi(list[1]->Run(vm).c_str());
			for(int i=0;(i<limit)&&(limit>0)&&(!vm.IsOnWeakException());i++) {
				vm.Dictionary().PushToHistory(IntToString(i));
				// 実行(第2パラメータ)
				retstr+=list[2]->Run(vm);
				vm.Dictionary().UnlinkFrame(frame);
				vm.ResetState(TKawariVM::InterpState::CONTINUE);
			}
			vm.ResetState(TKawariVM::InterpState::BREAK);
			vm.Dictionary().EndLoop();
			vm.Dictionary().UnlinkFrame(frame);
			vm.Dictionary().PushToHistory(retstr);
		} else {
			// エラー
		}
	}else if(arg0=="foreach"){
		if(list.size()==4) {
			TNS_KawariDictionary &dict=vm.Dictionary();
			unsigned int frame=dict.LinkFrame();
			vm.Dictionary().StartLoop();
			// テンポラリエントリ(第1パラメータ)
			TEntry tmpentry=dict.CreateEntry(
				vm.RunWithCurrentContext(list[1]));

			// 列挙対象(第2パラメータ)
			const string listentryname=vm.RunWithCurrentContext(list[2]);
			TEntry listentry=dict.GetEntry(listentryname);

			if((!listentry.AssertIfEmpty(listentryname))&&
			   (tmpentry.IsValid()&&listentry.IsValid())){
				vector<TWordID> wordcol;
				listentry.FindAll(wordcol);
				vector<TWordID>::iterator it=wordcol.begin();
				for(;(it!=wordcol.end())&&(!vm.IsOnWeakException());it++) {
					// 敵は別エントリ中の単語なので、コンテキストを作成
					TKVMCode_base *code=dict.GetWordFromID(*it);
					if (!code) continue;
					TWordID wid=dict.CreateWord(
						TKawariCompiler::CompileAsString(vm.RunWithNewContext(code)));
					tmpentry.Clear();
					tmpentry.Push(wid);

					// 実行(第3パラメータ)
					retstr+=vm.RunWithCurrentContext(list[3]);
					vm.ResetState(TKawariVM::InterpState::CONTINUE);
				}
				vm.ResetState(TKawariVM::InterpState::BREAK);
			}
			vm.Dictionary().EndLoop();
			dict.UnlinkFrame(frame);
			dict.PushToHistory(retstr);
		}else{
			// エラー
		}
	}else if(arg0=="?"){
		if(list.size()>1) {
			// 第1パラメータ以降から一つ選択
			unsigned int index=Random(list.size()-1)+1;
			retstr=vm.RunWithCurrentContext(list[index]);
			vm.Dictionary().PushToHistory(retstr);
		}
	}else{
		// 「関数」の処理
		vector<string> args;
		for(TCodePVector::iterator it=list.begin();it!=list.end();it++)
			args.push_back((*it)->Run(vm));
		retstr=vm.FunctionCall(args);
	}
	vm.Dictionary().PushToHistory(retstr);
	return retstr;
}

string TKVMCodeScriptStatement::DisCompile(void) const{
	string retstr;
	if (list.size()){
		for (unsigned int i=0,max=list.size()-1;i<max;i++){
			retstr+=list[i]->DisCompile()+" ";
		}
		retstr+=list.back()->DisCompile();
	}
	return retstr;
}

//-------------------------------------------------------------------------
// インラインスクリプト ( '$(' ScriptStatementSeq ') )
string TKVMCodeInlineScript::Run(TKawariVM &vm){
	string retstr;
	// $( ... ; ... ; ... )の中では、基本的にはスタックは巻き戻らない
	unsigned int frame=vm.Dictionary().LinkFrame();
	for (TCodePVector::iterator it=list.begin(); (it!=list.end())&&(!vm.IsOnException()); it++){
		// おのおのの行について
		TKVMCodeScriptStatement *code=dynamic_cast<TKVMCodeScriptStatement *>(*it);
		if (code){
			if(code->GetArg0()=="silent"){
				retstr="";
				continue;
			}
		}
		retstr+=(*it)->Run(vm);
	}
	vm.Dictionary().UnlinkFrame(frame);
	vm.Dictionary().PushToHistory(retstr);
	return retstr;
}

string TKVMCodeInlineScript::DisCompile(void) const{
	if(list.size()){
		string retstr="$(";
		for (unsigned int i=0,max=list.size()-1; i<max; i++)
			retstr+=list[i]->DisCompile()+";";
		retstr+=list.back()->DisCompile()+")";
		return retstr;
	}else{
		return "$( )";
	}
}

//--------------------------------------------------------------------------
// 添え字付きエントリ呼び出し ( '$' EntryWord '[' WS Expression WS ']' )
string TKVMCodeEntryIndex::Run(TKawariVM &vm){
	string entry_name=entry_id->Run(vm);
	if (!entry_name.size()) return("");

	TEntry entry=vm.Dictionary().GetEntry(entry_name);
	if (entry.AssertIfEmpty(entry_name)) return("");

	string num=expr->Run(vm);
	int index=atoi(num.c_str());
	if (index<0) index+=entry.Size();
	if (index<0) return("");

	TWordID id=entry.Index(index);
	if (!id) return("");
	TKVMCode_base *code=vm.Dictionary().GetWordFromID(id);
	if (!code) return("");

	string retstr=vm.RunWithNewContext(code);
	vm.Dictionary().PushToHistory(retstr);
	return retstr;
}

string TKVMCodeEntryIndex::DisCompile(void) const{
	const TKVMCodeExpression *code_expr=dynamic_cast<const TKVMCodeExpression *>(expr);
	if (code_expr){
		return "$"+entry_id->DisCompile()+"["+code_expr->DisCompileExpression()+"]";
	}else{
		return "$"+entry_id->DisCompile()+"["+expr->DisCompile()+"]";
	}
}
bool TKVMCodeEntryIndex::Less(const TKVMCode_base &r_) const{
	const TKVMCodeEntryIndex& r=dynamic_cast<const TKVMCodeEntryIndex&>(r_);
	if (TKVMCode_baseP_Less()(entry_id, r.entry_id)) return true;
	if (TKVMCode_baseP_Less()(r.entry_id, entry_id)) return false;

	return TKVMCode_baseP_Less()(expr, r.expr);
}
ostream &TKVMCodeEntryIndex::Debug(ostream& os, unsigned int level) const{
	DebugIndent(os, level) << "EntryIndex(" << endl;
	entry_id->Debug(os, level+1);
	DebugIndent(os, level) << ")[" << endl;
	expr->Debug(os, level+1);
	return DebugIndent(os, level) << "]" << endl;
}
TKVMCodeEntryIndex::~TKVMCodeEntryIndex(){
	if (entry_id)
		delete entry_id;
	if (expr)
		delete expr;
}
//-------------------------------------------------------------------------
