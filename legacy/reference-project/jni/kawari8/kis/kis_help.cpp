//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// KawariInlineScript -- オンラインヘルプ --
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.07.21  Phase 6.2     First version
//  2001.08.06  Phase 6.2     ver追加
//  2001.08.12  Phase 6.2.1   mingw対策
//  2002.03.17  Phase 7.9.0   helpの出力先を標準出力に変更
//  2002.04.22  Phase 8.0.0   ver license追加
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "kis/kis_help.h"
//---------------------------------------------------------------------------
#include "libkawari/kawari_vm.h"
#include "libkawari/kawari_engine.h"
#include "libkawari/kawari_version.h"
#include "libkawari/kawari_log.h"
//---------------------------------------------------------------------------
#include <string>
#include <vector>
using namespace std;
//---------------------------------------------------------------------------
string KIS_help::Function(const vector<string>& args)
{
	if(args.size()>=2) {
		TKisFunctionInfo info;
		if (Engine->GetFunctionInfo(args[1], info)){
			GetLogger().GetStream() << info.name << endl
			 << "syntax  : " << info.format << endl
			 << "return  : " << info.returnval << endl
			 << "comment : " << info.information << endl;
		} else {
			GetLogger().GetStream() << "Command \"" << args[1] << "\" is NOT exist." << endl;
		}
	} else {
		GetLogger().GetStream() << "Command list :" << endl;
		vector<string> list;
		Engine->GetFunctionList(list);

		vector<string>::const_iterator it;
		for(it=list.begin();it!=list.end();it++) {
			GetLogger().GetStream() << (*it) << endl;
		}
	}

	return("");
}
//---------------------------------------------------------------------------
string KIS_ver::Function(const vector<string>& args)
{
	if ((args.size()>=2)&&(args[1]=="license")){
		GetLogger().GetStream() << KAWARI_CORE_LICENSE;
		return ("");
	}else if((args.size()>=2)&&(args[1]=="author")) {
		return(KAWARI_AUTHOR);
	}else{
		return(KAWARI_NAME "/" KAWARI_MAJOR "." KAWARI_MINOR "." KAWARI_SUBMINOR);
	}
}
//---------------------------------------------------------------------------

