//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// 辞書
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.04.21  Phase 0.50a1   辞書管理のクラス化
//  2001.05.03  Phase 0.50a4  インラインスクリプト
//  2001.05.26  Phase 5.1     インタープリタ・コンパイラ化
//                            API整理
//  2001.06.17  Phase 6.0     複数エントリへの同時追加のバグ修正
//  2002.03.10  Phase 7.9.0   辞書アクセスインターフェース強化
//  2002.05.09  Phase 8.0.0   名前空間機能を偽Composite Patternで分離
//                            KIS++への布石？
//
//---------------------------------------------------------------------------
#ifndef KAWARI_DICT_H
#define KAWARI_DICT_H
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include <string>
#include <vector>
#include <map>
#include <set>
using namespace std;
//---------------------------------------------------------------------------
#include "libkawari/kawari_ns.h"
#include "libkawari/kawari_code.h"
#include "libkawari/wordcollection.h"
//---------------------------------------------------------------------------
// 華和梨辞書
class TNS_KawariDictionary: public TGarbageCollector {
private:
	// 名前空間関係

	// グローバルな名前空間
	TNameSpace *GlobalNameSpace;

	// コンテキスト
	class TContext : public TNameSpace {
	public:
		// 履歴参照スタック
		std::vector<std::string> history;
		// ループの入れ子数
		unsigned int loopcount;

		TContext(TGarbageCollector *col): TNameSpace(col), loopcount(0) {}
		virtual ~TContext() {}
	};

	// NameSpaceを得る
	TNameSpace *SearchNameSpace(const string &entry) {
		if (IsLocalEntry(entry)) return GetCurrentContext();
		else return GlobalNameSpace;
	}


	// 単語関係

	// すべての単語を保持する
	TWordPointerCollection<TKVMCode_base,TKVMCode_baseP_Less> WordCollection;

	// 削除候補単語
	set<TWordID> Garbage;

	// PVWを保持する
	set<TWordID> PVWSet;


	// コンテキスト関係

	// コンテキストスタック
	std::vector<TContext *> ContextStack;

	// 現在のコンテキストを得る
	// 無ければNULLが返る
	TContext *GetCurrentContext(void) const;

	// ローカルエントリ名を判別
	bool IsLocalEntry(const std::string &entry_name) const;


	// ログ
	class TKawariLogger &logger;
public:
	TNS_KawariDictionary (TKawariLogger &lgr) : logger(lgr) {
		GlobalNameSpace = new TNameSpace(this);
		GlobalNameSpace->Reserve(2000);
		WordCollection.Reserve(10000);
	}
	virtual ~TNS_KawariDictionary (){
		delete GlobalNameSpace;
		GlobalNameSpace = NULL;
	}

	// 範囲外のインデックス
	static const unsigned int NPos;		// UINT_MAX


	// 情報系API

	// 総単語数を取得
	// 戻り値 : 単語の個数 (削除された単語は数えられない)
	unsigned int WordCollectionSize(void) const;

	// 有効エントリ数を取得
	// 戻り値 : エントリの個数
	unsigned int Size(void) const;

	// 指定されたエントリの単語数を取得
	// 戻り値 : 単語の個数
//	unsigned int EntrySize(TEntryID entry) const;


	// エントリ関連API

	// エントリ獲得
	// 戻り値 : エントリ
	TEntry GetEntry(const string &entry);

	// エントリを生成する
	// 既にエントリが存在する場合は、生成せずにIDを返す
	// 戻り値 : 生成したエントリのID
	TEntry CreateEntry(const string& entry);

	// エントリIDを全て列挙
	// 戻り値 : エントリの個数
	unsigned int FindAllEntry(vector<TEntry> &entrycol) const;

	// 指定されたエントリ全てに含まれる単語を
	// 純粋仮想単語「${エントリ名}」のみ展開して再帰的に列挙する
	// 戻り値 : 単語の個数
	unsigned int GetWordCollection(TEntry entry,set<TWordID> &wordcol);


	// 単語関連API

	// 単語を生成する
	// 既に単語が存在する場合は、生成せずにIDを返す
	// 注意・既に単語IDを持つ単語であった場合、deleteされる
	// 戻り値 : 生成した単語のID
	TWordID CreateWord(TKVMCode_base* word);

	// 単語ID取得
	// 戻り値 : 1オリジン、見つからなければ0を返す
	TWordID GetWordID(TKVMCode_base* word) const;

	// IDから単語に変換
	// 戻り値 : 中間コードツリー
	TKVMCode_base *GetWordFromID(TWordID id) const;

	// 単語に削除マークを付ける
	void MarkWordForGC(TWordID id);


	// コンテキスト関連API

	// 現在のコンテキストの履歴参照スタックのポインタを取得
	unsigned int LinkFrame(void);

	// 現在のコンテキストの履歴参照スタックのポインタを復帰
	void UnlinkFrame(unsigned int pos);

	// ループに入る
	void StartLoop(void);

	// ループを出る
	void EndLoop(void);

	// 現在のループ階層を得る
	unsigned int CurrentLoop(void);

	// 新しいコンテキストを作成し、スタックにpush
	void CreateContext(void);

	// コンテキストをpop
	// これが最後のコンテキストである場合、ガベッジコレクション。
	void DeleteContext(void);

	// コンテキストスタックの現在の深さ
	unsigned int GetContextStackDepth(void);

	// 履歴参照スタックに置換結果文字列をpushする。
	void PushToHistory (const string &str);

	// 履歴参照
	string GetHistory (int index);

	// ロガーを返す
	TKawariLogger &GetLogger(void) {
		return logger;
	}

};
//--------------------------------------------------------------------------
// ローカルエントリ名を判別
inline bool TNS_KawariDictionary::IsLocalEntry(const std::string &entry_name) const{
	return (entry_name.size()&&(entry_name[0]=='@'));
}
//---------------------------------------------------------------------------
// エントリID取得
inline TEntry TNS_KawariDictionary::GetEntry(const string& entry)
{
	TNameSpace *ns=SearchNameSpace(entry);
	return (ns) ? ns->Get(entry):TEntry(GlobalNameSpace, 0);
}
//---------------------------------------------------------------------------
// 総単語数を取得
inline unsigned int TNS_KawariDictionary::WordCollectionSize(void) const
{
	return(WordCollection.Size());
}
//---------------------------------------------------------------------------
// 有効エントリ数を取得
inline unsigned int TNS_KawariDictionary::Size(void) const
{
	return(GlobalNameSpace->Size());
}
//---------------------------------------------------------------------------
// 単語ID取得
inline TWordID TNS_KawariDictionary::GetWordID(TKVMCode_base* word) const
{
	return(WordCollection.Find(word));
}
//---------------------------------------------------------------------------
// IDから単語に変換
// 戻り値 : 中間コードツリー
inline TKVMCode_base *TNS_KawariDictionary::GetWordFromID(TWordID id) const
{
	TKVMCode_base *const*word=WordCollection.Find(id);
	return(word?(*word):NULL);
}
//---------------------------------------------------------------------------
// 単語に削除マークを付ける
inline void TNS_KawariDictionary::MarkWordForGC(TWordID id)
{
	Garbage.insert(id);
}
//---------------------------------------------------------------------------
// エントリ名を全て列挙
// 戻り値 : エントリの個数
inline unsigned int TNS_KawariDictionary::FindAllEntry(vector<TEntry> &entrycol) const
{
	return GlobalNameSpace->FindAllEntry(entrycol);
}
//--------------------------------------------------------------------------
// 現在のコンテキストを得る
inline TNS_KawariDictionary::TContext *TNS_KawariDictionary::GetCurrentContext(void) const{
	if (ContextStack.size())
		return ContextStack.back();
	else
		return NULL;
}
//--------------------------------------------------------------------------
// コンテキストスタックの現在の深さ
inline unsigned int TNS_KawariDictionary::GetContextStackDepth(void){
	return ContextStack.size();
}
//--------------------------------------------------------------------------
// ループに入る
inline void TNS_KawariDictionary::StartLoop(void){
	TContext *ctx=GetCurrentContext();
	if (!ctx) return;
	ctx->loopcount++;
}
//--------------------------------------------------------------------------
// ループを出る
inline void TNS_KawariDictionary::EndLoop(void){
	TContext *ctx=GetCurrentContext();
	if (!ctx) return;
	if (ctx->loopcount) ctx->loopcount--;
}
//--------------------------------------------------------------------------
// 現在のループ階層を得る
inline unsigned int TNS_KawariDictionary::CurrentLoop(void){
	TContext *ctx=GetCurrentContext();
	if (!ctx) return 0;
	return ctx->loopcount;
}
//--------------------------------------------------------------------------
#endif
