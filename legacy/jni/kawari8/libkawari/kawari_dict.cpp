//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// 辞書
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.04.21  Phase 0.50a1  辞書管理のクラス化
//  2001.05.03  Phase 0.50a4  インラインスクリプト
//  2001.05.26  Phase 5.1     インタープリタ・コンパイラ化
//                            API整理
//  2001.06.17  Phase 5.4     複数エントリへの同時追加のバグ修正
//                            逆コンパイラ
//  2001.12.16  Phase 7.2     ClearEntry(TEntryID entry)のバグ修正
//                            (Thanks: しの)
//  2002.03.10  Phase 7.9.0   辞書アクセスインターフェース強化
//  2002.05.09  Phase 8.0.0   名前空間機能を分離。
//                            KIS++への布石？
//  2002.05.20                PVWからのエントリ名取得を実行時に持ち越し
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "libkawari/kawari_dict.h"
//---------------------------------------------------------------------------
#include "libkawari/kawari_compiler.h"
#include "libkawari/kawari_codeset.h"
#include "libkawari/kawari_log.h"
#include "libkawari/kawari_rc.h"
#include "misc/misc.h"
using namespace kawari::resource;
using namespace kawari_log;
//---------------------------------------------------------------------------
#include <iostream>
using namespace std;
//---------------------------------------------------------------------------
// 範囲外のインデックス
const unsigned int TNS_KawariDictionary::NPos=UINT_MAX;
//---------------------------------------------------------------------------
// エントリを生成する
// 既にエントリが存在する場合は、生成せずにIDを返す
// 戻り値 : 生成したエントリのID
TEntry TNS_KawariDictionary::CreateEntry(const string& entry)
{
	TNameSpace *ns=SearchNameSpace(entry);
	return ((ns)?ns->Create(entry): TEntry(GlobalNameSpace,0));
}
//---------------------------------------------------------------------------
// 単語を生成する
// 既に単語が存在する場合は、生成せずにIDを返す
// 注意・既に単語IDを持つ単語であった場合、deleteされる
// 戻り値 : 生成したエントリのID
TWordID TNS_KawariDictionary::CreateWord(TKVMCode_base* word)
{
	if(!word) return(0);

	TWordID id=0;
	if(!WordCollection.Insert(word,&id)) {
		// 既に登録済みの単語だった
		delete word;
		word=GetWordFromID(id);
	}else{
		// 純粋仮想単語
		TKVMCodePVW *pvw=dynamic_cast<TKVMCodePVW *>(word);
		if (pvw)
			PVWSet.insert(id);
	}

	return(id);
}
//---------------------------------------------------------------------------
// 指定されたエントリ全てに含まれる単語を
// 純粋仮想単語「${エントリ名}」のみ展開して再帰的に列挙する
// 戻り値 : 単語の個数
unsigned int TNS_KawariDictionary::GetWordCollection(TEntry start_entry,set<TWordID> &wordcol)
{
	// 過去に検索済みのエントリ
	set<TEntry> donelist;
	// 検索予定のエントリ
	vector<TEntry> parselist;

	parselist.push_back(start_entry);

	while(parselist.size()) {
		// 次候補
		TEntry entry=parselist.back();
		parselist.pop_back();
		if(donelist.count(entry)) continue;
		donelist.insert(entry);

		vector<TWordID> tmpcol;
		entry.FindAll(tmpcol);
		for(vector<TWordID>::iterator it=tmpcol.begin(); it!=tmpcol.end(); it++) {
			TWordID id=(*it);
			if(PVWSet.count(id)){
				// 純粋仮想単語
				TKVMCode_base *code=GetWordFromID(id);
				if(!code) continue;
				TKVMCodePVW *pvw=dynamic_cast<TKVMCodePVW *>(code);
				if(!pvw) continue;
				TEntry child=GetEntry(pvw->Get());
				if(child.IsValid()&&(donelist.count(child)==0))
					parselist.push_back(child);
			}else{
				wordcol.insert(id);
			}
		}
	}

	return(wordcol.size());
}
//---------------------------------------------------------------------------
// 現在のコンテキストのスタックフレームのポインタを取得
// (えーいっ、ようはMC68000のLINKだ)
unsigned int TNS_KawariDictionary::LinkFrame(void){
	TContext *ctx=GetCurrentContext();
	if (!ctx) return 0;
	return ctx->history.size();
}
//---------------------------------------------------------------------------
// 現在のコンテキストのスタックフレームのポインタを復帰
// (えーいっ、ようはMC68000のUNLINKだ)
void TNS_KawariDictionary::UnlinkFrame(unsigned int pos){
	TContext *ctx=GetCurrentContext();
	if (!ctx) return ;
	if (pos<ctx->history.size()) ctx->history.resize(pos);
}
//---------------------------------------------------------------------------
// 新しいコンテキストを作成し、スタックにpush
void TNS_KawariDictionary::CreateContext(void){
	ContextStack.push_back(new TContext(this));
}
//---------------------------------------------------------------------------
// コンテキストをpop
// 削除予定単語を追加。必要ならばガベッジコレクション。
void TNS_KawariDictionary::DeleteContext(void){
	if (ContextStack.size()){
		if(ContextStack.back()) delete ContextStack.back();
		ContextStack.pop_back();
	}
	if (!ContextStack.size()){
		// GC
		if(logger.Check(LOG_DUMP)){
			ostream &logstream=logger.GetStream(LOG_DUMP);
			for (set<TWordID>::iterator it=Garbage.begin(); it!=Garbage.end(); it++){
				TWordID id=(*it);
				if (!GlobalNameSpace->ContainsWord(id)){
					// 逆引きが存在しない。
					TKVMCode_base *pword=*WordCollection.Find(id);
					WordCollection.Delete(id);
					if (pword){
						logstream << RC.S(DUMP_DICT_DELETE_WORD1) <<id<< RC.S(DUMP_DICT_DELETE_WORD2) << (pword)->DisCompile() << endl;
						delete (pword);
					}else{
						logger.GetStream(LOG_ERROR) << RC.S(ERR_DICT_CAN_NOT_DELETE_WORD1) << id << RC.S(ERR_DICT_CAN_NOT_DELETE_WORD2) << endl;
					}
				}
			}
		}else{
			for (set<TWordID>::iterator it=Garbage.begin(); it!=Garbage.end(); it++){
				TWordID id=(*it);
				if (!GlobalNameSpace->ContainsWord(id)){
					// 逆引きが存在しない。
					TKVMCode_base *pword=*WordCollection.Find(id);
					WordCollection.Delete(id);
					if (pword){
						delete (pword);
					}else{
						logger.GetStream(LOG_ERROR) << RC.S(ERR_DICT_CAN_NOT_DELETE_WORD1) << id << RC.S(ERR_DICT_CAN_NOT_DELETE_WORD2) << endl;
					}
				}
			}
		}
		Garbage.clear();
	}
}
//--------------------------------------------------------------------------
// 履歴参照スタックに置換結果文字列をpushする。
void TNS_KawariDictionary::PushToHistory (const string &str){
	TContext *ctx=GetCurrentContext();
	if (!ctx) return;
	ctx->history.push_back(str);
}
//---------------------------------------------------------------------------
// 履歴参照
string TNS_KawariDictionary::GetHistory (int index){
	TContext *ctx=GetCurrentContext();
	if (!ctx) return "";
	if (index<0)
		index = ctx->history.size()+index;
	if ((index<0)||(ctx->history.size()>INT_MAX)||(index>=(int)ctx->history.size())){
		return "";
	}else{
		return ctx->history[index];
	}
}
//---------------------------------------------------------------------------
