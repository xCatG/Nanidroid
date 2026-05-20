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
#ifndef KAWARI_VM_H__
#define KAWARI_VM_H__
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "libkawari/kawari_code.h"
#include "libkawari/wordcollection.h"
#include "libkawari/kawari_dict.h"
//---------------------------------------------------------------------------
#include <vector>
#include <string>
#include <map>
#include <climits>
#include <cstdlib>
#include <set>
#include <iostream>
//---------------------------------------------------------------------------

//==========================================================================
//
//  華和梨仮想機械
//  Kawari Virtual Machine  (former TKisEngine)
//
//==========================================================================
class TKawariVM {
public:

	// インタプリタ内部状態
	struct InterpState {
		enum StateValue {
			NORMAL,
			CONTINUE,
			BREAK,
			RETURN,
			EXCEPTION
		} state;
		std::string mes;
		bool override;
		InterpState (StateValue s, std::string str, bool ovr=true);
	};

private:
	// 華和梨
	class TKawariEngine &engine;

	// 辞書
	class TNS_KawariDictionary &dictionary;

	// 関数テーブル
	std::map<std::string, class TKisFunction_base *> FunctionTable;

	// 関数リスト
	std::vector<class TKisFunction_base*> FunctionList;

	// 内部状態
	InterpState state;

	// ロガー
	class TKawariLogger &logger;
public:
	TKawariVM(class TKawariEngine &e, class TNS_KawariDictionary &d, class TKawariLogger &lgr);

	~TKawariVM();

	// "System.function."
	static const std::string SYSTEM_FUNCTION_PREFIX;

	//=====================================================================
	// 中間コード実行機能
	//=====================================================================

	// 新しいコンテキストを作成し、コードを実行する。
	// 実行が終了するとコンテキストは破棄。
	std::string RunWithNewContext(class TKVMCode_base *code);

	// 現在のコンテキスト上でコードを実行する。
	// 実行が終了すると履歴参照スタックを実行前のポイントまで巻き戻す。
	// 現在のコンテキストがない場合はRunWithNewContext()を呼ぶ。
	std::string RunWithCurrentContext(class TKVMCode_base *code);

	// 関数実行
	std::string FunctionCall(const std::vector<std::string>& args);

	// 例外状態(continue, break, return, exception)であるかを知る
	bool IsOnException(void) { return (state.state!=InterpState::NORMAL); }

	// 弱い例外状態(break, return, exception)であるかを知る
	bool IsOnWeakException(void) { return (state.state>InterpState::CONTINUE); }

	// 状態値を得る
	const InterpState &GetState(void) { return state; }

	// 状態のセット
	void SetState(const InterpState &st) { state=st; }

	// 状態のリセット
	void ResetState(void) { state=InterpState(InterpState::NORMAL, ""); }

	// 指定状態のリセット
	void ResetState(InterpState::StateValue test) {
		if (state.state==test)
			state=InterpState(InterpState::NORMAL, "");
	}

	//=====================================================================
	// ユーティリティ
	//=====================================================================

	// ログ
	inline class TKawariLogger &GetLogger(void) {
		return logger;
	}

	// 辞書
	TNS_KawariDictionary &Dictionary(void){
		return dictionary;
	}

	// ビルトインコマンドの情報を得る
	// name : (入力)コマンド名
	// info : (出力)コマンド情報
	// 戻り値(bool) : 存在すればtrue
	bool GetFunctionInfo(const std::string &name, struct TKisFunctionInfo &info);

	// ビルトインコマンドのリスト
	// list : (出力)コマンド名リスト
	// 戻り値(unsigned int) : コマンドの数
	unsigned int GetFunctionList(std::vector<std::string> &list) const;
};
//---------------------------------------------------------------------------
#endif // KAWARI_VM_H__
