//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
//  2001.05.30  Phase 5.1     First version
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.05.25  Phase 5.1     First version
//  2005.06.21  Phase 8.2.3   負の添え字に対応
//  2005.06.28  Phase 8.2.3   TEntryRange対応
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "kis/kis_counter.h"
#include "libkawari/kawari_engine.h"
#include "libkawari/kawari_rc.h"
#include "misc/misc.h"
using namespace kawari::resource;
using namespace kawari_log;
//---------------------------------------------------------------------------
#include <cstdlib>
using namespace std;
//---------------------------------------------------------------------------
string KIS_inc::Function_(const vector<string>& args,bool flag_dec)
{
	if(!AssertArgument(args, 2, 4)) return("");

	int diff=(args.size()<3)? 1: atoi(args[2].c_str());
	if(flag_dec) diff=-diff;
#if 0
	string entryname;
	int st,end;
	unsigned int size;

	int range=TKawariEngine::DecodeEntryName(args[1],entryname,st,end);

	switch(range) {
	case 0:		// 範囲指定無し
		st=end=0;
		break;
	case 1:		// 単独要素指定
		size=Engine->EntrySize(entryname);
		if(st<0) st=size+st;
		if(st<0) {
			// エラー
			GetLogger().GetStream(kawari_log::LOG_ERROR) << args[0] << RC.S(ERR_KIS_DICT_INVALID_INDEX) << endl;
			return("");
		}
		break;
	case 2:		// 範囲指定
		// エラー
		GetLogger().GetStream(kawari_log::LOG_ERROR) << args[0] << RC.S(ERR_KIS_DICT_INVALID_INDEX) << endl;
		return("");
	}

	int counter=atoi(Engine->IndexParse(entryname,st).c_str())+diff;

	if(args.size()>=4) {
		int limit=atoi(args[3].c_str());
		if((counter>limit)&&!flag) counter=limit;
		if((counter<limit)&&flag)  counter=limit;
	}

	TEntry entry=Engine->CreateEntry(entryname);
	TWordID id=Engine->CreateStrWord(IntToString(counter));
	TWordID nullstr=Engine->CreateStrWord("");

	entry.Replace2(st,id,nullstr);
#else
	TEntryRange range=Engine->GetEntryRange(args[1]);
	if(range.Start==TKawariEngine::NPos){
		GetLogger().GetStream(LOG_ERROR) << args[0]
			<< RC.S(ERR_KIS_DICT_INVALID_INDEX) << endl;
		return("");
	}

	int limit=(args.size()>=4)?
		atoi(args[3].c_str()):((flag_dec)? INT_MIN: INT_MAX);
	TWordID nullstr=Engine->CreateStrWord("");

	if(!range.Range)
		range.Start=range.End=0;

	//MEMO: 範囲指定した場合、その全てをinc/decするように仕様追加。
	for(unsigned int i=range.Start;i<=range.End;i++){
		// atoi()は""に対して0を返すことが保証されている。
		int counter=atoi(Engine->IndexParse(range.Entry,i).c_str())+diff;
		if(((!flag_dec)&&(counter>limit))
		   ||(flag_dec&&(counter<limit))) counter=limit;
		TWordID wid=Engine->CreateStrWord(IntToString(counter));
		range.Entry.Replace2(i,wid,nullstr);
	}
#endif
	return("");
}
//---------------------------------------------------------------------------

