//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// 名前空間
//
//      Programed by Suikyo
//
//  2002.05.12  Phase 8.0.0   分離
//  2003.11.17                FindAllSubEntryにエントリ実在チェックさせる
//  2003.12.02  Phase 8.2.0   FindAllSubEntry(直下より深いサブエントリ対策)
//  2005.10.28  Phase 8.2.4   RFind(インデックスのデクリメント順序を修正)
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "libkawari/kawari_ns.h"
#include "libkawari/kawari_log.h"
#include "misc/misc.h"
using namespace kawari_log;
//---------------------------------------------------------------------------
using namespace std;
//---------------------------------------------------------------------------
// 範囲外のインデックス
const unsigned int TEntry::NPos=UINT_MAX;
//---------------------------------------------------------------------------
// エントリを生成する
// 既にエントリが存在する場合は、生成せずにIDを返す
// 戻り値 : 生成したエントリのID
TEntry TNameSpace::Create(const string& entry)
{
	// "."はルートを示す。
	if(entry==".") return(TEntry(this,0));

	vector<string> entryname_node;
	SplitEntryName(entry,entryname_node);
	if(entryname_node.size()==0) return(TEntry(this,0));

	string entryname;
	TEntryID parent=0,id;

	for(unsigned int i=0, max=entryname_node.size();i<max;i++) {
		entryname=entryname+entryname_node[i];

		id=0;
		if (EntryCollection.Insert(entryname,&id)){
			// 新規追加時に限り。
			ParentEntry[id]=parent;
			SubEntry.insert(pair<TEntryID,TEntryID>(parent,id));
		}
		parent=id;
		entryname=entryname+".";
	}
	return(TEntry(this,id));
}
//---------------------------------------------------------------------------
// 全てのエントリを削除する
void TNameSpace::ClearAllEntry(void)
{
	vector<TEntry> entrycol;
	FindAllEntry(entrycol);
	for(vector<TEntry>::iterator it=entrycol.begin(); it!=entrycol.end(); it++){
		it->Clear();
	}
}
//---------------------------------------------------------------------------
// エントリ名を全て列挙
// 戻り値 : エントリの個数
unsigned int TNameSpace::FindAllEntry(vector<TEntry> &entrycol)
{
	TDictionary::const_iterator it;
	unsigned int n=0;

	for(it=Dictionary.begin();it!=Dictionary.end();it++) {
		if(it->second.size()) {
			entrycol.push_back(TEntry(this,it->first));
			n++;
		}
	}

	return(n);
}
//---------------------------------------------------------------------------
// エントリ名を「.」で分解する
void TNameSpace::SplitEntryName(const string& entryname,vector<string> &entryname_node)
{
	string::size_type pos=0;
	string::size_type max=entryname.size();
	while(pos<max){
		while((pos<max)&&(entryname[pos]=='.')) pos++;
		if(pos>=max) break;

		string::size_type pos1=pos;

		while((pos<max)&&(entryname[pos]!='.')) pos++;

		entryname_node.push_back(entryname.substr(pos1,pos-pos1));
	}

	return;
}
//---------------------------------------------------------------------------
// 指定されたエントリの単語数を取得
// 戻り値 : 単語の個数
unsigned int TEntry::Size(void) const
{
	if(!IsValid()) return(0);
	TDictionary::const_iterator it=ns->Dictionary.find(entry);
	if(it==ns->Dictionary.end()) return(0);
	return(it->second.size());
}
//---------------------------------------------------------------------------
// 指定されたエントリを空にする
// メモリに空エントリと単語が残る
// 戻り値 : 成功でtrue
bool TEntry::Clear(void)
{
	if((!IsValid())||(ns->Dictionary.count(entry)==0)) return(false);
	if(AssertIfProtected()) return(false);

	// 全単語に対して……
	for(vector<TWordID>::iterator it=ns->Dictionary[entry].begin();it!=ns->Dictionary[entry].end();it++) {
		TWordID wid=(*it);
		// 逆引き辞書の消去
		ns->ReverseDictionary[wid].erase(ns->ReverseDictionary[wid].lower_bound(entry));
		// GCに登録
		ns->gc->MarkWordForGC(wid);
	}

//	ns->Dictionary.erase(entry);
	ns->Dictionary[entry].clear();
	return(true);
}
//---------------------------------------------------------------------------
// このエントリ以下のエントリを全て空にする
// メモリに空エントリと単語が残っても良い
void TEntry::ClearTree(void)
{
	if(!IsValid()) return;
	vector<TEntry> entrycol;
	FindAllSubEntry(entrycol);
	for(vector<TEntry>::iterator it=entrycol.begin(); it!=entrycol.end(); it++){
		it->ClearTree();
	}
	if (IsValid()) Clear();
}
//---------------------------------------------------------------------------
// エントリ最後尾への単語の追加
void TEntry::Push(TWordID id)
{
	if((!IsValid())||(id==0)) return;
	if(AssertIfProtected()) return;
	ns->Dictionary[entry].push_back(id);
	ns->ReverseDictionary[id].insert(entry);
}
//---------------------------------------------------------------------------
// エントリ最後尾の単語の削除
TWordID TEntry::Pop(void)
{
	if((!IsValid())||(ns->Dictionary.count(entry)==0)) return(0);
	if(AssertIfProtected()) return(0);
	TWordID id=ns->Dictionary[entry].back();
	ns->Dictionary[entry].pop_back();

	// 逆引き辞書の消去
	ns->ReverseDictionary[id].erase(ns->ReverseDictionary[id].lower_bound(entry));
	// GCに登録
	ns->gc->MarkWordForGC(id);
	return id;
}
//---------------------------------------------------------------------------
// エントリ途中への単語の挿入
void TEntry::Insert(unsigned int pos,TWordID id)
{
	if((!IsValid())||(id==0)) return;
	if(AssertIfProtected()) return;

	if(ns->Dictionary[entry].size()<pos) return;
	ns->Dictionary[entry].insert(ns->Dictionary[entry].begin()+pos,id);
	ns->ReverseDictionary[id].insert(entry);
}
//---------------------------------------------------------------------------
// エントリ途中の単語の削除
void TEntry::Erase(unsigned int st,unsigned int end)
{
	// st, edは信用できないことに注意!
	if((!IsValid())||(st>end)||(st==NPos)) return;
	if(AssertIfProtected()) return;

	unsigned int entry_size=ns->Dictionary[entry].size();
	if (entry_size<=st) return;
	if (entry_size<=end) end=entry_size-1;
	vector<TWordID>::iterator itst,itend;
	itst=ns->Dictionary[entry].begin()+st;
	if(end!=NPos) itend=ns->Dictionary[entry].begin()+end+1;
	 else itend=ns->Dictionary[entry].end();

	// 逆引き辞書の消去
	for(vector<TWordID>::iterator it=itst;it!=itend;it++) {
		TWordID id=*it;
		ns->ReverseDictionary[id].erase(ns->ReverseDictionary[id].lower_bound(entry));
		// GCに登録
		ns->gc->MarkWordForGC(id);
	}

	ns->Dictionary[entry].erase(itst,itend);
}
//---------------------------------------------------------------------------
// エントリ途中の単語の入れ替え
TWordID TEntry::Replace(unsigned int pos,TWordID id)
{
	if((!IsValid())||(id==0)) return(0);
	if(AssertIfProtected()) return(0);

	if(ns->Dictionary[entry].size()<pos) return(0);

	// 逆引き辞書の消去
	TWordID oldid=ns->Dictionary[entry][pos];
	ns->ReverseDictionary[oldid].erase(ns->ReverseDictionary[oldid].lower_bound(entry));
	// GCに登録
	ns->gc->MarkWordForGC(oldid);

	ns->Dictionary[entry][pos]=id;
	ns->ReverseDictionary[id].insert(entry);

	return(oldid);
}
//---------------------------------------------------------------------------
// エントリ途中の単語の入れ替え(インデックスが範囲外の場合、id2を追加)
// 戻り値 : 削除された単語
TWordID TEntry::Replace2(unsigned int pos,TWordID id,TWordID id2)
{
	if((!IsValid())||(id==0)||(AssertIfProtected())) return(0);
	unsigned int size=Size();
	if(pos<size) {
		return Replace(pos,id);
	}else{
		for(unsigned int i=size; i<pos; i++){
			Push(id2);
		}
		Push(id);
		return 0;
	}
}
//---------------------------------------------------------------------------
// 指定されたエントリの指定した順番(0オリジン)の単語を返す
// 戻り値 : 単語のID
TWordID TEntry::Index(unsigned int index) const
{
	if(!IsValid()) return(0);
	TDictionary::const_iterator it=ns->Dictionary.find(entry);
	if(it==ns->Dictionary.end()) return(0);
	if(it->second.size()<=index) return(0);

	return(it->second[index]);
}
//---------------------------------------------------------------------------
// 指定されたエントリ内から指定した単語を検索
// 戻り値 : インデックス(見つからなければNPos)
unsigned int TEntry::Find(TWordID id,unsigned int pos) const
{
	if(!IsValid()) return(0);
	TDictionary::const_iterator it=ns->Dictionary.find(entry);
	if(it==ns->Dictionary.end()) return(NPos);

	for(unsigned int i=pos, max=it->second.size();i<max;i++) {
		if(it->second[i]==id) return(i);
	}

	return(NPos);
}
//---------------------------------------------------------------------------
// 指定されたエントリ内から指定した単語を検索(逆順)
// 戻り値 : インデックス(見つからなければNPos)
unsigned int TEntry::RFind(TWordID id,unsigned int pos) const
{
	if(!IsValid()) return(0);
	TDictionary::const_iterator it=ns->Dictionary.find(entry);
	if((it==ns->Dictionary.end())||(!it->second.size())) return(NPos);

	if(pos==NPos) pos=it->second.size()-1;
	unsigned int i=pos;
	while(i<it->second.size()) {
		if (it->second[i]==id) return(i);
		i--;
	}

	return(NPos);
}
//---------------------------------------------------------------------------
// 指定されたエントリの単語を全て列挙
// 戻り値 : 単語の個数
unsigned int TEntry::FindAll(vector<TWordID> &wordcol) const
{
	if(!IsValid()) return(0);
	if(ns->Dictionary.count(entry)==0) return(0);

	TDictionary::const_iterator it=ns->Dictionary.find(entry);
	wordcol.insert(wordcol.end(),it->second.begin(),it->second.end());

	return(it->second.size());
}
//---------------------------------------------------------------------------
// サブエントリIDを全て列挙
// 戻り値 : エントリの個数
unsigned int TEntry::FindAllSubEntry(vector<TEntry> &entrycol) const
{
//	if(!IsValid()) return(0);
	// entry==0の時はルート直下を探す
	typedef multimap<TEntryID,TEntryID>::const_iterator T;
	pair<T,T> range=ns->SubEntry.equal_range(entry);
	unsigned int n=0;

	vector<TEntry> dmyentrycol;
	for(T it=range.first;it!=range.second;it++) {
		TEntry current(ns,it->second);
		if(current.Size()) {
			entrycol.push_back(current);
			n++;
		}else if(current.FindTree(dmyentrycol)){
			// 更に下の階層に(サブ)+エントリがあれば生きていることにする
			entrycol.push_back(current);
			n++;
		}
	}

	return(n);
}
//---------------------------------------------------------------------------
// 指定されたエントリ名から始まるエントリIDを全て列挙
// "."はルートを示す。
// 空のエントリは無視
// 戻り値 : エントリの個数
unsigned int TNameSpace::FindTree(TEntryID entry, vector<TEntry> &entrycol)
{
//	if(!IsValid()) return(0);
	typedef multimap<TEntryID,TEntryID>::const_iterator T;
	pair<T,T> range=SubEntry.equal_range(entry);
	unsigned int n=0;

	for(T it=range.first;it!=range.second;it++) {
		n+=FindTree(it->second, entrycol);
	}

	TEntry current(this,entry);
	if(current.Size()) {
		entrycol.push_back(current);
		n++;
	}

	return(n);
}
//---------------------------------------------------------------------------
// 指定されたエントリ名から始まるエントリIDを全て列挙
// "."はルートを示す。
// 空のエントリは無視
// 戻り値 : エントリの個数
unsigned int TEntry::FindTree(vector<TEntry> &entrycol) const
{
	return(ns->FindTree(entry, entrycol));
}
//---------------------------------------------------------------------------
