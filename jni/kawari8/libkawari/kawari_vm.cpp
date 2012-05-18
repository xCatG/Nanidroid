//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
//  華和梨仮想機械(インラインスクリプトエンジン)
//
//      Programed by NAKAUE.T (Meister) / Suikyo
//
//  2001.05.24  Phase 5.1     インタープリタ・コンパイラ化
//  2001.06.12  Phase 5.3.2   ダミーコンテキスト
//  2001.06.17  Phase 6.0     インラインスクリプト内の履歴参照のバグ修正
//  2001.07.21  Phase 6.2     関数情報参照
//  2001.08.08  Phase 6.2     関数テーブル参照
//  2002.03.10  Phase 7.9.0   kawari_engine_base.h廃止
//                            辞書の直接アクセス禁止
//  2002.03.17                KIUに合わせてTKisEngineからTKawariVMに名称変更
//                            同じくTKawariCode~からTKVMCode~に名称変更
//  2002.04.18  Phase 8.0.0   書き直し
//                            コンテキスト周りは辞書へ
//                            例外状態の実現
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "libkawari/kawari_vm.h"
#include "libkawari/kawari_engine.h"
#include "libkawari/kawari_compiler.h"
#include "libkawari/kawari_code.h"
#include "libkawari/kawari_dict.h"
#include "libkawari/kawari_rc.h"
#include "kis/kis_config.h"
#include "kis/kis_base.h"
#include "misc/misc.h"
using namespace kawari::resource;
//---------------------------------------------------------------------------
#include <string>
#include <vector>
#include <cstdlib>
using namespace std;
//---------------------------------------------------------------------------
// 関数を登録するエントリのプレフィックス
const string TKawariVM::SYSTEM_FUNCTION_PREFIX="System.Function.";

//---------------------------------------------------------------------------
// インタプリタの状態
TKawariVM::InterpState::InterpState (TKawariVM::InterpState::StateValue s, string str, bool ovr) : state(s), mes(str), override(ovr) {}
//---------------------------------------------------------------------------
// コンストラクタ
TKawariVM::TKawariVM(TKawariEngine &eng, TNS_KawariDictionary &d, TKawariLogger &lgr)
	 : engine(eng), dictionary(d),
	   state(InterpState(TKawariVM::InterpState::NORMAL, "")), logger(lgr) {
	#define INLINE_SCRIPT_REGIST(T) \
	{ \
		TKisFunction_base *func=new T; \
		func->NotifyEngine(&engine); \
		if(func->Init()) { \
			FunctionTable[func->Name()]=func; \
			FunctionList.push_back(func); \
		} else delete func; \
	}

	#include "kis/kis_config.h"

	#undef INLINE_SCRIPT_REGIST
}

//---------------------------------------------------------------------------
// デストラクタ
TKawariVM::~TKawariVM(){
	for (vector<TKisFunction_base *>::iterator it=FunctionList.begin();it!=FunctionList.end();it++)
		if (*it)
			delete (*it);
}
//---------------------------------------------------------------------------


//---------------------------------------------------------------------------
// コード実行系
//---------------------------------------------------------------------------

//---------------------------------------------------------------------------
// 新しいコンテキストを作成し、コードを実行する。
// 実行が終了するとコンテキストは破棄。
string TKawariVM::RunWithNewContext(class TKVMCode_base *code){
	if (!code) return "";
	dictionary.CreateContext();
	string retstr=code->Run(*this);
	dictionary.DeleteContext();

	// Return状態の解決
	if (GetState().state==InterpState::RETURN){
		if (GetState().mes.size())
			retstr=GetState().mes;
	}

	// 状態のリセット
	ResetState();

	return retstr;
}
//---------------------------------------------------------------------------
// 現在のコンテキスト上でコードを実行する。
// 実行が終了すると履歴参照スタックを実行前のポイントまで巻き戻す。
// 現在のコンテキストがない場合はRunWithNewContext()を呼ぶ。
string TKawariVM::RunWithCurrentContext(TKVMCode_base *code){
	if (dictionary.GetContextStackDepth()){
		unsigned int frame=dictionary.LinkFrame();
		string retstr=code->Run(*this);
		dictionary.UnlinkFrame(frame);
		return retstr;
	}else{
		// コンテキストがありませんー
		return RunWithNewContext(code);
	}
}
//---------------------------------------------------------------------------
// 関数実行
string TKawariVM::FunctionCall(const vector<string>& args){
	if (!args[0].size()) return ("");
	// 先頭に'.'がある場合は強制ビルトインコマンド呼び出し
	if (args[0][0]=='.') {
		string comname=args[0].substr(1);
		if (FunctionTable.count(comname))
			return (FunctionTable[comname]->Function(args));
	}else{
		TEntry entry=dictionary.GetEntry(SYSTEM_FUNCTION_PREFIX+args[0]);
		// ユーザ定義関数を優先
		if (entry.IsValid()&&entry.Size()){
			// 最初の単語を実体とする
			TWordID id=entry.Index();
			if (!id) return "";
			TKVMCode_base *code=dictionary.GetWordFromID(id);

			// コンテキスト作成
			dictionary.CreateContext();

			// 引数格納
			if (args.size()){
				TEntry argentry=dictionary.CreateEntry("@arg");
				for(unsigned int i=0; i<args.size(); i++){
					argentry.Push(
						dictionary.CreateWord(
							TKawariCompiler::CompileAsString(args[i])));
				}
			}
			// コード実行
			string retstr=code->Run(*this);

			// コンテキスト削除
			dictionary.DeleteContext();

			// Return状態の解決
			if (GetState().state==InterpState::RETURN){
				if (GetState().override)
					retstr=GetState().mes;
			}
			ResetState();

			return retstr;
		}else if(FunctionTable.count(args[0])){
			// ビルトイン関数
			return (FunctionTable[args[0]]->Function(args));
		}
	}
	logger.GetStream(kawari_log::LOG_ERROR) << RC.S(ERR_VM_UNDEFINED_FUNCTION1) << args[0] << RC.S(ERR_VM_UNDEFINED_FUNCTION2) << endl;
	return "";
}
//---------------------------------------------------------------------------
// ビルトイン関数の情報を得る
bool TKawariVM::GetFunctionInfo(const string &name, TKisFunctionInfo &info){
	if (FunctionTable.count(name)){
		info=FunctionTable[name]->GetInformation();
		return true;
	}else{
		return false;
	}
}
//---------------------------------------------------------------------------
// ビルトイン関数のリスト
unsigned int TKawariVM::GetFunctionList(vector<string> &list) const{
	for (vector<TKisFunction_base*>::const_iterator it=FunctionList.begin();it!=FunctionList.end();it++)
		list.push_back(string((*it)->Name()));
	return FunctionList.size();
}
//---------------------------------------------------------------------------
