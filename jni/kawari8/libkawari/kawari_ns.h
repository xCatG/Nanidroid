//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// 名前空間
//
//      Programed by Suikyo
//
//  2002.05.12  Phase 8.0.0   分離
//
//---------------------------------------------------------------------------
#ifndef KAWARI_NS_H
#define KAWARI_NS_H
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include <string>
#include <vector>
#include <map>
#include <set>
using namespace std;
//---------------------------------------------------------------------------
#include "libkawari/wordcollection.h"
#include "libkawari/kawari_log.h"
#include "libkawari/kawari_rc.h"
//---------------------------------------------------------------------------
// 単語ID
typedef unsigned int TWordID;
//---------------------------------------------------------------------------
// エントリID
typedef unsigned int TEntryID;
//---------------------------------------------------------------------------
// 辞書
typedef map<TEntryID,vector<TWordID> > TDictionary;
// 逆引き辞書
typedef map<TWordID,multiset<TEntryID> > TRDictionary;
// 親エントリディレクトリ
typedef map<TEntryID,TEntryID> TParentEntryMap;
// サブエントリディレクトリ
typedef multimap<TEntryID,TEntryID> TSubEntryMap;
//---------------------------------------------------------------------------
// ガベッジコレクタのインターフェース
class TGarbageCollector {
public:
	// 単語に削除マークを付ける
	virtual void MarkWordForGC(TWordID id)=0;

	// ロガーを返す
	virtual TKawariLogger &GetLogger(void)=0;
};
//---------------------------------------------------------------------------
// 名前空間
// エントリ名は更にピリオド('.')によって階層的に管理される。
class TNameSpace {
protected:
	// エントリコレクション
	TWordCollection<string,less<string> > EntryCollection;
	// 辞書
	TDictionary Dictionary;
	// 逆引き辞書
	TRDictionary ReverseDictionary;
	// 親エントリディレクトリ
	TParentEntryMap ParentEntry;
	// サブエントリディレクトリ
	TSubEntryMap SubEntry;
	// 書込み禁止のエントリ
	set<TEntryID> ProtectedEntry;

	// グローバルなガベッジコレクタへの参照
	TGarbageCollector *gc;

	// 指定されたエントリから始まるエントリIDを全て列挙
	unsigned int FindTree(TEntryID entry, vector<class TEntry>& entrycol);

public:
	TNameSpace(TGarbageCollector *col): gc(col) {}
	virtual ~TNameSpace();

	// WordCollectionのヒープを確保
	void Reserve(unsigned int n);


	// [ 情報系API ]

	// 有効エントリ数を取得
	unsigned int Size(void) const;


	// [ エントリ獲得 ]

	// エントリID取得
	// "."はルートを示す。
	// 戻り値 : 1オリジン、見つからなければ0を返す
	class TEntry Get(const string& entry);

	// エントリを生成する
	// 既にエントリが存在する場合は、生成せずにIDを返す
	// "."はルートを示す。
	// 戻り値 : 生成したエントリのID
	class TEntry Create(const string& entry);


	// [ エントリ操作 ]

	// 全てのエントリを削除する
	void ClearAllEntry(void);


	// [ 検索系API ]

	// エントリを全て列挙
	// 戻り値 : エントリの個数
	unsigned int FindAllEntry(vector<class TEntry> &entrycol);

	// エントリ名を「.」で分解する
	static void SplitEntryName(const string& entryname,vector<string> &entryname_node);
	// 指定された単語を含むエントリが存在するか調べる
	// 戻り値 : 存在すればtrue
	bool ContainsWord(TWordID id) const;

	friend class TEntry;
};
//---------------------------------------------------------------------------
// エントリ
class TEntry {
private:
	TNameSpace * ns;
	TEntryID entry;

	// 書き込み保護チェック
	// 戻り値 : 書込み保護対象ならばtrue
	bool AssertIfProtected(void);

public:
	TEntry(TNameSpace *space, TEntryID id) : ns(space), entry(id) {}

	TEntry(const TEntry& e) : ns(e.ns),entry(e.entry) {}
	TEntry &operator = (const TEntry& e) { ns=e.ns; entry=e.entry; return *this; }
	bool operator == (const TEntry& e) const {
		return ((ns==e.ns)&&(entry==e.entry));
	}
	bool operator != (const TEntry& e) const {
		return ((ns!=e.ns)||(entry!=e.entry));
	}
	bool operator < (const TEntry& r) const {
		if(ns<r.ns) return true;
		else if (ns>r.ns) return false;
		else return (entry<r.entry);
	}

	// 範囲外のインデックス
	static const unsigned int NPos;		// UINT_MAX
		// さて、こうしたclassメンバのconstはすぐその場で定義したくなりそうな
		// ものだが、VC++6.0のバグで、残念ながらそうもいかない。
		// このバグは、外で値を定義することによって回避できる。
		// 実際の値がどうなってるかは、お手数だが宣言の下の方を見ていただきたい。
		// 詳しくは下記参照。(ふざけるな)
		// BUG: C2258 and C2252 on in Place Initialization of Static Const Members (Q241569)
		// http://support.microsoft.com/default.aspx?scid=kb;en-us;Q241569

	// 空のエントリだった場合、警告をログに残す。
	// 急場しのぎ的。
	// 戻り値 : 空の場合true
	bool AssertIfEmpty(const string& name);


	// [ 情報系API ]

	// 指定されたエントリの単語数を取得
	// 戻り値 : 単語の個数
	unsigned int Size(void) const;

	// 有効なエントリであるかを調べる
	// 戻り値 : 有効なら真
	bool IsValid(void) const { return ((ns!=NULL)&&(entry!=0)); }


	// [ ID変換系 ]

	// エントリ名を得る
	// 戻り値 : エントリ名文字列
	string GetName(void) const;

	// IDを得る
	TEntryID GetID(void) const { return entry; }


	// [ 辞書追加・削除系API ]

	// 指定されたエントリを空にする
	// メモリに空エントリと単語が残る
	// 戻り値 : 成功でtrue
	bool Clear(void);

	// このエントリ以下のエントリを全て空にする
	// メモリに空エントリと単語が残っても良い
	void ClearTree(void);

	// エントリ最後尾への単語の追加
	void Push(TWordID id);

	// 指定されたエントリを空にしてから単語を追加
	// エントリを変数的に利用する時に使用する
	void PushAfterClear(TWordID id);

	// エントリ最後尾の単語の削除
	// 戻り値 : 削除された単語
	TWordID Pop(void);

	// エントリ途中への単語の挿入
	void Insert(unsigned int pos,TWordID id);

	// エントリ途中の単語の削除
	void Erase(unsigned int st,unsigned int end);

	// エントリ途中の単語の入れ替え
	// 戻り値 : 削除された単語
	TWordID Replace(unsigned int pos,TWordID id);

	// エントリ途中の単語の入れ替え(インデックスが範囲外の場合、id2を追加)
	// 戻り値 : 削除された単語
	TWordID Replace2(unsigned int pos,TWordID id,TWordID id2);

	// エントリへの書き込みを禁止する
	void WriteProtect(void);


	// [ 検索系API ]

	// 指定されたエントリが含まれるか否かを返す
	// 戻り値 : 含まれる場合true
//	bool Contains(TEntryID id) const;

	// 指定されたエントリの指定した順番(0オリジン)の単語を返す
	// 戻り値 : 単語のID
	TWordID Index(unsigned int index=0) const;

	// 指定されたエントリ内から指定した単語を検索
	// 戻り値 : インデックス(見つからなければNPos)
	unsigned int Find(TWordID id,unsigned int pos=0) const;

	// 指定されたエントリ内から指定した単語を検索(逆順)
	// 戻り値 : インデックス(見つからなければNPos)
	unsigned int RFind(TWordID id,unsigned int pos=NPos) const;

	// 指定されたエントリの単語を全て列挙
	// 戻り値 : 単語の個数
	unsigned int FindAll(vector<TWordID> &wordcol) const;

	// 親エントリ取得
	// 戻り値 : 1オリジン、見つからなければ0を返す
	TEntry GetParent(void) const;

	// サブエントリを全て列挙
	// 戻り値 : エントリの個数
	unsigned int FindAllSubEntry(vector<TEntry> &entrycol) const;

	// 指定されたエントリ名から始まるエントリを全て列挙
	// 空のエントリは無視
	// 戻り値 : エントリの個数
	unsigned int FindTree(vector<TEntry> &entrycol) const;
};
//---------------------------------------------------------------------------
inline TNameSpace::~TNameSpace()
{
	ProtectedEntry.clear();
	ClearAllEntry();
}
//---------------------------------------------------------------------------
// 指定されたエントリが含まれるか否かを返す
// 戻り値 : 含まれる場合true
#if 0
inline bool TNameSpace::Contains(TEntryID id) const
{
	return EntryCollection.Contains(id);
}
#endif
//---------------------------------------------------------------------------
// WordCollectionのヒープを確保
inline void TNameSpace::Reserve(unsigned int n)
{
	EntryCollection.Reserve(n);
}
//---------------------------------------------------------------------------
// 有効エントリ数を取得
inline unsigned int TNameSpace::Size(void) const
{
	return Dictionary.size();
}
//---------------------------------------------------------------------------
// エントリID取得
// "."はルートを示す。
// 戻り値 : 1オリジン、見つからなければ0を返す
inline TEntry TNameSpace::Get(const string& entry)
{
	return((entry==".")?TEntry(this,0):TEntry(this, EntryCollection.Find(entry)));
}
//--------------------------------------------------------------------------
// 指定された単語を含むエントリが存在するか調べる
// 戻り値 : 存在すればtrue
inline bool TNameSpace::ContainsWord(TWordID id) const
{
	if (ReverseDictionary.count(id)==0) return false;
	TRDictionary::const_iterator it=ReverseDictionary.find(id);
	return (it->second.size()!=0);
}
//---------------------------------------------------------------------------
// 親エントリ取得
inline TEntry TEntry::GetParent(void) const
{
	map<TEntryID,TEntryID>::const_iterator it=ns->ParentEntry.find(entry);
	return((it!=ns->ParentEntry.end())?(TEntry(ns,it->second)):(TEntry(ns,0)));
}
//---------------------------------------------------------------------------
// エントリIDからエントリ名に変換
// 戻り値 : エントリ名文字列
inline string TEntry::GetName(void) const
{
	string const*entryname=ns->EntryCollection.Find(entry);
	if (!entryname) return("");
	else return (*entryname);
}
//--------------------------------------------------------------------------
// 書き込み保護チェック
// 戻り値 : 書込み保護対象ならばtrue
inline bool TEntry::AssertIfProtected(void)
{
	if(IsValid()&&ns->ProtectedEntry.count(entry)){
		ns->gc->GetLogger().GetStream(kawari_log::LOG_ERROR)
			<< RC.S(kawari::resource::ERR_NS_ASSERT_PROTECTED_ENTRY1) << GetName()
			<< RC.S(kawari::resource::ERR_NS_ASSERT_PROTECTED_ENTRY2) << endl;
		return true;
	}else{
		return false;
	}
}
//---------------------------------------------------------------------------
// 指定されたエントリを空にしてから単語を追加
// エントリを変数的に利用する時に使用する
inline void TEntry::PushAfterClear(TWordID id)
{
	Clear();
	Push(id);
}
//--------------------------------------------------------------------------
// エントリへの書き込みを禁止する
inline void TEntry::WriteProtect(void)
{
	if(IsValid()) ns->ProtectedEntry.insert(entry);
}
//--------------------------------------------------------------------------
// 空のエントリだった場合、警告をログに残す。
// 急場しのぎ的。
// 戻り値 : 空の場合true
inline bool TEntry::AssertIfEmpty(const string& name)
{
	if(((!IsValid())||(!Size()))&&ns->gc->GetLogger().Check(kawari_log::LOG_DECL)){
		ns->gc->GetLogger().GetStream() << RC.S(kawari::resource::WARN_NS_READ_EMPTY_ENTRY1) << name << RC.S(kawari::resource::WARN_NS_READ_EMPTY_ENTRY2) << endl;
		return true;
	}else{
		return false;
	}
}
//--------------------------------------------------------------------------
#endif
