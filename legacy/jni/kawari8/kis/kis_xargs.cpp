//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// KawariInlineScript -- 引数展開実行 --
//
//      Programed by NAKAUE.T (Meister)
//
//  2002.03.18  Phase 7.9.0   新コミュニケート機構補助
//  2005.06.28  Phase 8.2.3   TEntryRange対応
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "kis/kis_xargs.h"
#include "libkawari/kawari_rc.h"
using namespace kawari::resource;
using namespace kawari_log;
//---------------------------------------------------------------------------
#include "libkawari/kawari_engine.h"
//---------------------------------------------------------------------------
string KIS_xargs::Function(const vector<string>& args)
{
	if(!AssertArgument(args, 3)) return ("");

#if 0
	string entryname;
	int st,end;

	TKawariEngine::DecodeEntryName(args[1],entryname,st,end);

	TEntry entry=Engine->GetEntry(entryname);
	unsigned int size=entry.Size();
	if (size) {
		if(st<0) st=size+st;
		if(end<0) end=size+end;
		if((st<0)||(end<0)||(st>end)) {
			// エラー
			GetLogger().GetStream(kawari_log::LOG_ERROR) << args[0] << " : Invalid index" << endl;
			return("");
		}
	}else{
		st=end=0;
	}

	vector<string> args2;
	for(unsigned int i=2;i<args.size();i++) {
		args2.push_back(args[i]);
	}
	for(unsigned int i=(unsigned int)st;i<=(unsigned int)end;i++) {
		args2.push_back(Engine->IndexParse(entry,i));
	}
#else
	TEntryRange range=Engine->GetEntryRange(args[1]);
	if(range.Start==TKawariEngine::NPos){
		GetLogger().GetStream(LOG_ERROR) << args[0]
			<< RC.S(ERR_KIS_DICT_INVALID_INDEX) << endl;
		return("");
	}

	vector<string> args2;
	for(unsigned int i=2;i<args.size();i++)
		args2.push_back(args[i]);

	for(unsigned int i=range.Start; i<=range.End; i++)
		args2.push_back(Engine->IndexParse(range.Entry,i));
#endif
	return(Engine->FunctionCall(args2));
}
//---------------------------------------------------------------------------

