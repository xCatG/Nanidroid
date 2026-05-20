//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// データ保持クラス
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.04.24  Phase 0.5     導入
//  2001.05.26  Phase 5.1     インタープリタ・コンパイラ導入に伴いtemplate化
//  2002.05.05  Phase 8.0.0   ガベッジコレクタ、逆順対応
//  2003.01.22  Phase 8.1.1   gcc3以降ではtypename使用
//  2005.06.21  Phase 8.2.3   gcc3.xのwarning対応 (suikyo)
//
//---------------------------------------------------------------------------
#ifndef WORDCOLLECTION_H
#define WORDCOLLECTION_H
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include <string>
#include <vector>
#include <map>
//---------------------------------------------------------------------------
#ifndef FORCE_TYPENAME
#	undef typename
#endif
//---------------------------------------------------------------------------
// 抽象化インターフェース
template<class T,class Compare>
class IWordCollection {
public:
	virtual unsigned int Size(void) const=0;
	virtual bool Insert(const T& word,unsigned int *id_=NULL)=0;
	virtual bool Delete(unsigned int id)=0;
	virtual const T* Find(unsigned int id) const=0;
	virtual unsigned int Find(const T& word) const=0;
	virtual bool Contains(unsigned int id) const=0;
};
//---------------------------------------------------------------------------
// データを保持しIDを割り振るクラス
// IDは[1, UINT_MAX]
template<class T,class Compare>
class TWordCollection : public IWordCollection<T, Compare> {
protected:
	// 全てのデータ
	std::vector<T> WordList;

	// ビットマップ
	// 0は抹消されたデータ
	std::vector<unsigned int> IDMap;

	// データとIDの対応表
	std::map<T,unsigned int,Compare> WordIDMap;

	// リサイクル待ちID
	std::vector<unsigned int> Recycle;

public:
	virtual ~TWordCollection() {}

	inline TWordCollection(void) { IDMap.push_back(0); }

	// 総単語数を取得
	unsigned int Size(void) const
	{
		return(WordIDMap.size());
	}

	// データの追加
	// 新規追加でtrue,IDは1オリジン
	bool Insert(const T& word,unsigned int *id_=NULL);

	// データの抹消
	// ただし、Deleteされたデータは、その後に新しいデータがInsert
	// されるまでメモリ中に保持され続ける(妥協)
	virtual bool Delete(unsigned int id);

	// ID->データ
	const T* Find(unsigned int id) const;

	// データ->ID
	unsigned int Find(const T& word) const;

	// このIDを含むか
	bool Contains(unsigned int id) const;

	// ヒープの確保
	void Reserve(unsigned int n);
};
//---------------------------------------------------------------------------
// ポインタを保持しIDを割り振るクラス
template<class T,class Compare>
class TWordPointerCollection : public TWordCollection<T*, Compare>{
public:
	// データの抹消
	// Delete時にNULLを代入
	virtual bool Delete(unsigned int id){
		if(TWordCollection<T*,Compare>::Delete(id)){
            TWordCollection<T*, Compare>::WordList[id-1]=NULL;
			return true;
		}else{
			return false;
		}
	}
	virtual ~TWordPointerCollection (){
		for (typename std::vector<T*>::iterator it=TWordCollection<T*, Compare>::WordList.begin(); it < TWordCollection<T*, Compare>::WordList.end(); it++){
			if((*it))
				delete (*it);
		}
	}
};
//---------------------------------------------------------------------------
// データの追加
// 新規追加でtrue,IDは1オリジン
template<class T,class Compare>
bool TWordCollection<T,Compare>::Insert(const T& word,unsigned int *id_)
{
	unsigned int id=Find(word);
	if(id_) *id_=id;

	if(id) return(false);

	if(Recycle.size()){
		id=Recycle.back();
		Recycle.pop_back();
		// やばいかなー
		WordList[id-1]=word;
		WordIDMap[word]=id;
		IDMap[id]=id;
	}else{
		WordList.push_back(word);
		id=WordList.size();

		IDMap.push_back(id);
		WordIDMap[word]=id;
	}
	if(id_) *id_=id;

	return(true);
}
//---------------------------------------------------------------------------
// データの抹消
// ただし、Deleteされたデータは、その後に新しいデータがInsert
// されるまでメモリ中に保持され続ける(妥協)
template<class T,class Compare>
bool TWordCollection<T,Compare>::Delete(unsigned int id){
	if((id==0)||(IDMap[id]==0)||(WordList.size()<=(id-1))) return false;

	IDMap[id]=0;
	Recycle.push_back(id);
	WordIDMap.erase(WordList[id-1]);

	return true;
}
//---------------------------------------------------------------------------
// ID->データ
template<class T,class Compare>
 const T* TWordCollection<T,Compare>::Find(unsigned int id) const
{
	if((id==0)||(IDMap[id]==0)||(WordList.size()<=(id-1))) return(NULL);

	return(&(WordList[id-1]));
}
//---------------------------------------------------------------------------
// データ->ID
template<class T,class Compare>
 unsigned int TWordCollection<T,Compare>::Find(const T& word) const
{
	typename std::map<T,unsigned int,Compare>::const_iterator it=WordIDMap.find(word);

	return((it!=WordIDMap.end())?it->second:0);
}
//---------------------------------------------------------------------------
// このIDを含むか
template<class T,class Compare>
bool TWordCollection<T,Compare>::Contains(unsigned int id) const
{
	if((id==0)||(IDMap[id]==0)||(WordList.size()<=(id-1)))
		return(false);
	else
		return(true);
}
//---------------------------------------------------------------------------
// ヒープの確保
template<class T,class Compare>
void TWordCollection<T,Compare>::Reserve(unsigned int n)
{
	WordList.reserve(n);
	IDMap.reserve(n);
	Recycle.reserve(n/2);
}
//---------------------------------------------------------------------------
class TStringP_Less {
public:
	bool operator ()(const std::string *l,const std::string *r) const
	{
		return((*l)<(*r));
	}
};
//---------------------------------------------------------------------------
#endif
