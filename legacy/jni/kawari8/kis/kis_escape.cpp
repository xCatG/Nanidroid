//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// KawariInlineScript -- エスケープ --
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.07.14  Phase 6.1     First version
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "kis/kis_escape.h"
//---------------------------------------------------------------------------
#include "misc/l10n.h"
//---------------------------------------------------------------------------
string KIS_escape::Function(const vector<string>& args)
{
	if(!AssertArgument(args, 2)) return ("");

	string retstr;
	for(unsigned int i=1;i<args.size();i++) {
		if(i>1) retstr+=string(" ");

		for(unsigned int j=0;j<args[i].size();j++) {
			if(iskanji1st(args[i][j])) {
				retstr+=args[i][j++];
				retstr+=args[i][j];
			} else {
				if((args[i][j]=='\\')||(args[i][j]=='%')) retstr+='\\';
				retstr+=args[i][j];
			}
		}
	}


	return(retstr);
}
//---------------------------------------------------------------------------

