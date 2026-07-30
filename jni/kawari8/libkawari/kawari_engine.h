//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// 華和梨エンジン
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.05.27  Phase 5.1     インタープリタ・コンパイラ化
//                            クラス階層整理
//  2001.05.31  Phase 5.2     保守的piro
//  2001.06.09  Phase 5.3     マッチエントリ
//  2001.06.10  Phase 5.3.1   微調整
//  2001.06.17  Phase 5.4     save
//  2001.08.25  Phase 7.0     セキュリティ対策(WriteProtect)
//  2001.12.08  Phase 7.1.2   テキストファイル読み込み対応
//  2002.03.10  Phase 7.9.0   Parseの宣言をbaseに移動
//                            テキストファイルの読み込みを外す(KISじゃないの？)
//                            そもそもFISの開発中止で意味のなくなってた
//                            TNS_Engine_baseを廃止
//                            ついでにマッチ辞書廃止
//                            辞書アクセスインターフェース強化
//  2002.04.18  Phase 8.0.0   VMの変更に追従
//                            SAORI対応
//  2005.06.28  Phase 8.2.3   DecodeEntryNameに代えてGetEntryRange導入
//
//---------------------------------------------------------------------------
#ifndef KAWARI_ENGINE_H
#define KAWARI_ENGINE_H
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include <string>
#include <vector>
#include <set>
using namespace std;
//---------------------------------------------------------------------------
#include "libkawari/kawari_compiler.h"
#include "libkawari/kawari_dict.h"
#include "libkawari/kawari_code.h"
#include "libkawari/kawari_vm.h"
#include "libkawari/kawari_log.h"
#include "saori/saori.h"
//---------------------------------------------------------------------------
struct TEntryRange;
//---------------------------------------------------------------------------
class TKawariEngine {
private:
	// 辞書ファイルのパス名
	string DataPath;

	// ログ
	TKawariLogger *logger;

	// 辞書
	TNS_KawariDictionary *Dictionary;

	// スクリプトエンジン
	class TKawariVM *KawariVM;

	// SAORI
	class saori::TSaoriPark *SaoriPark;

public:
	// 範囲外のインデックス
	static const unsigned int NPos;

	// 情報系API

	// 総単語数を取得
	// 戻り値 : 単語の個数
	unsigned int WordCollectionSize(void) const;

	// 有効エントリ数を取得
	// 戻り値 : エントリの個数
	unsigned int EntryCollectionSize(void) const;

	// 指定されたエントリの単語数を取得
	// 戻り値 : 単語の個数
	unsigned int EntrySize(const string& entryname) const;


	// エントリ名API

	// 文字列をエントリ名で使用可能な文字列にエンコードする
	static string EncodeEntryName(const string &orgsen);

	// 文字列をエントリ名とインデックスにデコードする
	// 例 : Entry        単独のエントリ名
	//      Entry[i]     エントリのi番目の要素
	//      Entry[i..j]  エントリのi番目からj番目までの要素
	//      インデックスが負の場合は、後ろから数えた位置
	// 戻り値 :  0=添え字無し
	//           1=単独要素指定
	//           2=範囲指定
	//          -1=エラー
//	static int DecodeEntryName(const string &orgsen,string &entryname,int &st,int &end);
	// 戻り値の範囲は必ず0以上になるが、エントリサイズ内とは限らない。
	TEntryRange GetEntryRange(const string &orgsen);

	// 辞書ID変換系API

	// 単語ID取得
	// 戻り値 : 1オリジン、見つからなければ0を返す
	TWordID GetWordID(const string& word) const;

	// IDから単語に変換
	// 戻り値 : 逆コンパイルした単語文字列
	string GetWordFromID(TWordID id) const;

	// エントリ取得
	// 戻り値 : 1オリジン、見つからなければ0を返す
	TEntry GetEntry(const string& entryname) const;


	// 辞書追加・削除系API

	// エントリを生成する
	// 既にエントリが存在する場合は、生成せずにIDを返す
	// 戻り値 : 生成したエントリのID
	TEntry CreateEntry(const string& entryname);

	// 単語を生成する
	// 既に単語が存在する場合は、生成せずにIDを返す
	// 戻り値 : 生成した単語のID
	TWordID CreateWord(const string& word);
	TWordID CreateStrWord(const string& word);

	// 指定されたエントリを空にする
	// メモリに空エントリと単語が残っても良い
	void ClearEntry(const string& entryname);

	// エントリ最後尾への単語の追加
	// 戻り値 : 成功でtrue
	void Push(const string& entryname,const string& word);

	// エントリ最後尾の単語の削除
	void Pop(const string& entryname);

	// エントリ途中への単語の挿入
	void Insert(const string& entryname,unsigned int pos,const string& word);

	// エントリ途中の単語の削除
	// endも削除されることに注意。
	void Erase(const string& entryname,unsigned int st,unsigned int end);

	// エントリ途中の単語の入れ替え
	void Replace(const string& entryname,unsigned int pos,const string& word);

	// エントリ途中の単語の入れ替え(インデックスが範囲外の場合、id2を追加)
	void Replace2(const string& entryname,unsigned int pos,const string& word,const string& word2);

	// 指定されたエントリを空にしてから単語を追加
	// エントリを変数的に利用する時に使用する
	void PushAfterClear(const string& entryname,const string& word);
	void PushStrAfterClear(const string& entryname,const string& word);

	// 指定されたエントリへの書き込みを禁止する
	void WriteProtect(const string& entryname);

	// 指定されたエントリ名から始まるエントリを全て空にする
	// メモリに空エントリと単語が残っても良い
	// "."で全エントリ・・・危険すぎ？
	void ClearTree(const string& spacename);


	// 検索系API

	// 指定されたエントリが空かどうか？
	// 戻り値 : 空ならtrue
	bool Empty(const string& entryname) const;

	// 指定されたエントリの指定した順番(0オリジン)の単語を返す
	// 戻り値 : 逆コンパイルした単語文字列
	string IndexWord(TEntry entry,unsigned int index=0) const;
	string IndexWord(const string& entryname,unsigned int index=0) const;

	// 指定されたエントリ内から指定した単語を検索
	// 戻り値 : インデックス(見つからなければNPos)
	unsigned int Find(const string& entryname,const string& word,unsigned int pos=0) const;

	// 指定されたエントリ内から指定した単語を検索(逆順)
	// 戻り値 : インデックス(見つからなければNPos)
	unsigned int RFind(const string& entryname,const string& word,unsigned int pos=NPos) const;

	// エントリ集合演算を行い、結果を列挙する
	// 戻り値 : 単語の個数
	unsigned int CalcEntryExpression(const string &entryexpr, set<TWordID> &wordcol) const;

	// エントリを全て列挙
	// 戻り値 : エントリの個数
	unsigned int FindAllEntry(vector<TEntry> &entrycol) const;


	// 実行系API

	// 指定されたエントリの指定した順番(0オリジン)の単語を実行する
	// 戻り値 string : 出力
	string IndexParse(TEntry entry,unsigned int index=0);
	string IndexParse(const string& entryname,unsigned int index=0);

	// 指定されたIDの単語(スクリプト)を実行する
	// 戻り値 string : 出力
	string Parse(TWordID id);

	// 与えられたスクリプトを解釈・実行する
	// 戻り値 string : 出力
	string Parse(const string& script);

	// KIS関数(ビルトイン及びユーザ定義)の実行
	string FunctionCall(const vector<string> &args);

	// ビルトイン関数の情報を得る
	// name : (入力)関数名
	// info : (出力)関数名、文法、戻り値、備考のリスト
	// 戻り値(bool) : 関数が存在すればtrue
	bool GetFunctionInfo(const std::string &name, struct TKisFunctionInfo &info);

	// ビルトイン関数のリスト
	// list : (出力)関数名リスト
	// 戻り値(unsigned int) : 関数の数
	unsigned int GetFunctionList(std::vector<std::string> &list) const;


	// SAORI関係API

	enum SAORILOADTYPE {
		PRELOAD=saori::PRELOAD,
		LOADONCALL,
		NORESIDENT
	};

	// SAORIモジュールの登録
	void RegisterSAORIModule(const std::string &aliasname, const std::string &path, const SAORILOADTYPE type);

	// SAORIモジュール登録の削除
	void EraseSAORIModule(const std::string &aliasname);

	// SAORIリクエストを行う
	bool RequestToSAORIModule(
		const std::string &aliasname,
		const TPHMessage &request, TPHMessage &response);

	// 登録されたSAORIモジュールのリストを得る
	int ListSAORIModule(std::vector<string> &list);

	// 指定SAORIモジュールの情報を得る


	// コンテキスト関係 API

	// 履歴参照
	string GetHistory(int index);


	// ファイルAPI

	// 辞書ファイルのパス名を設定する
	void SetDataPath(const string &datapath);

	// 辞書ファイルのパス名を取得する
	string GetDataPath(void) const;

	// 華和梨フォーマット辞書ファイルを読み込む
	// 戻り値 : 成功でtrue
	bool LoadKawariDict(const string &filename);

	// 華和梨フォーマット辞書ファイルを書き込む
	// 戻り値 : 成功でtrue
	bool SaveKawariDict(const string &filename,const vector<string>& entry,bool crypt=false) const;

public:

	// ロガーの取得
	TKawariLogger &GetLogger(void) const;

	TKawariEngine(void);

	~TKawariEngine();

};
//---------------------------------------------------------------------------
// エントリの領域指定
// range == false : no index (Start=0, End=max(Size()-1, 0))
// start == end : single word
// start < end  : multiple words
// start == TKawariEngine::NPos  : error
struct TEntryRange {
	string Name;
	TEntry Entry;
	bool Range;
	unsigned int Start;
	unsigned int End;

	TEntryRange(const string &name, const TEntry &entry)
		:Name(name), Entry(entry), Range(false),
		 Start(0), End((entry.Size()>0)?entry.Size()-1:0) {}
	TEntryRange(const string &name, const TEntry &entry,
				unsigned int start, unsigned int end)
		:Name(name), Entry(entry), Range(true), Start(start), End(end) {}
	TEntryRange(const TEntryRange &range)
		:Name(range.Name), Entry(range.Entry),
		 Range(range.Range), Start(range.Start), End(range.End) {}
};
//---------------------------------------------------------------------------
// 総単語数を取得
inline unsigned int TKawariEngine::WordCollectionSize(void) const
{
	return(Dictionary->WordCollectionSize());
}
//---------------------------------------------------------------------------
// 有効エントリ数を取得
inline unsigned int TKawariEngine::EntryCollectionSize(void) const
{
	return(Dictionary->Size());
}
//---------------------------------------------------------------------------
// 指定されたエントリの単語数
inline unsigned int TKawariEngine::EntrySize(const string& entryname) const
{
	return(Dictionary->GetEntry(entryname).Size());
}
//---------------------------------------------------------------------------
// 単語ID取得
inline TWordID TKawariEngine::GetWordID(const string& word) const
{
	TKVMCode_base *code=TKawariCompiler::Compile(word, GetLogger());
	TWordID id=Dictionary->GetWordID(code);
	delete code;

	return(id);
}
//---------------------------------------------------------------------------
// エントリ取得
inline TEntry TKawariEngine::GetEntry(const string& entryname) const
{
	return(Dictionary->GetEntry(entryname));
}
//---------------------------------------------------------------------------
// エントリを生成する
inline TEntry TKawariEngine::CreateEntry(const string& entryname)
{
	return(Dictionary->CreateEntry(entryname));
}
//---------------------------------------------------------------------------
// 単語を生成する
inline TWordID TKawariEngine::CreateWord(const string& word)
{
	return(Dictionary->CreateWord(TKawariCompiler::Compile(word, (*logger))));
}
//---------------------------------------------------------------------------
inline TWordID TKawariEngine::CreateStrWord(const string& word)
{
	return(Dictionary->CreateWord(TKawariCompiler::CompileAsString(word)));
}
//---------------------------------------------------------------------------
// 指定されたエントリを空にする
inline void TKawariEngine::ClearEntry(const string& entryname)
{
	Dictionary->GetEntry(entryname).Clear();
}
//---------------------------------------------------------------------------
// 辞書への単語の追加
inline void TKawariEngine::Push(const string& entryname,const string& word)
{
	Dictionary->CreateEntry(entryname).Push(CreateWord(word));
}
//---------------------------------------------------------------------------
// エントリ最後尾の単語の削除
inline void TKawariEngine::Pop(const string& entryname)
{
	Dictionary->GetEntry(entryname).Pop();
}
//---------------------------------------------------------------------------
// エントリ途中への単語の挿入
inline void TKawariEngine::Insert(const string& entryname,unsigned int pos,const string& word)
{
	Dictionary->CreateEntry(entryname).Insert(pos,CreateWord(word));
}
//---------------------------------------------------------------------------
// エントリ途中の単語の削除
inline void TKawariEngine::Erase(const string& entryname,unsigned int st,unsigned int end)
{
	Dictionary->GetEntry(entryname).Erase(st, end);
}
//---------------------------------------------------------------------------
// エントリ途中の単語の入れ替え
inline void TKawariEngine::Replace(const string& entryname,unsigned int pos,const string& word)
{
	Dictionary->CreateEntry(entryname).Replace(pos,CreateWord(word));
}
//---------------------------------------------------------------------------
// エントリ途中の単語の入れ替え(インデックスが範囲外の場合、id2を追加)
inline void TKawariEngine::Replace2(const string& entryname,unsigned int pos,const string& word,const string& word2)
{
	Dictionary->CreateEntry(entryname).Replace2(pos,CreateWord(word),CreateWord(word2));
}
//---------------------------------------------------------------------------
// 指定されたエントリを空にしてから単語を追加
inline void TKawariEngine::PushAfterClear(const string& entryname,const string& word)
{
	Dictionary->CreateEntry(entryname).PushAfterClear(CreateWord(word));
}
//---------------------------------------------------------------------------
inline void TKawariEngine::PushStrAfterClear(const string& entryname,const string& word)
{
	Dictionary->CreateEntry(entryname).PushAfterClear(CreateStrWord(word));
}
//---------------------------------------------------------------------------
// 指定されたエントリへの書き込みを禁止する
inline void TKawariEngine::WriteProtect(const string& entryname)
{
	Dictionary->CreateEntry(entryname).WriteProtect();
}
//---------------------------------------------------------------------------
// 指定されたエントリが空かどうか？
// 戻り値 : 空ならtrue
inline bool TKawariEngine::Empty(const string& entryname) const
{
	return(GetEntry(entryname).Size()==0);
}
//---------------------------------------------------------------------------
// 指定されたエントリの指定した順番(0オリジン)の単語を返す
inline string TKawariEngine::IndexWord(TEntry entry,unsigned int index) const
{
	TWordID id=entry.Index(index);
	if(id==0) return("");
	return(GetWordFromID(id));
}
//---------------------------------------------------------------------------
inline string TKawariEngine::IndexWord(const string& entryname,unsigned int index) const
{
	return(GetWordFromID(Dictionary->GetEntry(entryname).Index(index)));
}
//---------------------------------------------------------------------------
// 指定されたエントリ内から指定した単語を検索
// 戻り値 : インデックス(見つからなければNPos)
inline unsigned int TKawariEngine::Find(const string& entryname,const string& word,unsigned int pos) const
{
//	return(Find(GetEntryID(entryname),GetWordID(word),pos));
	return Dictionary->GetEntry(entryname).Find(GetWordID(word),pos);
}
//---------------------------------------------------------------------------
// 指定されたエントリ内から指定した単語を検索(逆順)
// 戻り値 : インデックス(見つからなければNPos)
inline unsigned int TKawariEngine::RFind(const string& entryname,const string& word,unsigned int pos) const
{
//	return(RFind(GetEntryID(entryname),GetWordID(word),pos));
	return Dictionary->GetEntry(entryname).RFind(GetWordID(word),pos);
}
//---------------------------------------------------------------------------
// エントリ名を全て列挙
inline unsigned int TKawariEngine::FindAllEntry(vector<TEntry> &entrycol) const
{
	return(Dictionary->FindAllEntry(entrycol));
}
//---------------------------------------------------------------------------
// 指定されたエントリの指定した順番(0オリジン)の単語を返す
inline string TKawariEngine::IndexParse(TEntry entry,unsigned int index)
{
	if(!entry.IsValid()) return("");
	return(Parse(entry.Index(index)));
}
//---------------------------------------------------------------------------
// 指定されたエントリの指定した順番(0オリジン)の単語を返す
inline string TKawariEngine::IndexParse(const string& entryname,unsigned int index)
{
	return(Parse(Dictionary->GetEntry(entryname).Index(index)));
}
//--------------------------------------------------------------------------
// KIS関数を実行する
inline string TKawariEngine::FunctionCall(const vector<string> &args){
	return KawariVM->FunctionCall(args);
}
//---------------------------------------------------------------------------
// ビルトイン関数の情報を得る
inline bool TKawariEngine::GetFunctionInfo(const string &name, struct TKisFunctionInfo &info){
	return KawariVM->GetFunctionInfo(name, info);
}
//---------------------------------------------------------------------------
// ビルトイン関数のリスト
inline unsigned int TKawariEngine::GetFunctionList(vector<string> &list) const{
	return KawariVM->GetFunctionList(list);
}
//--------------------------------------------------------------------------
// 履歴参照
inline std::string TKawariEngine::GetHistory(int index){
	return Dictionary->GetHistory(index);
}
//---------------------------------------------------------------------------
// 辞書ファイルのパス名を設定する
inline void TKawariEngine::SetDataPath(const string &datapath)
{
	DataPath=datapath;
}
//---------------------------------------------------------------------------
// 辞書ファイルのパス名を取得する
inline string TKawariEngine::GetDataPath(void) const
{
	return(DataPath);
}
//---------------------------------------------------------------------------
// ロガーの取得
inline TKawariLogger &TKawariEngine::GetLogger(void) const
{
	return (*logger);
}
//---------------------------------------------------------------------------
#endif
