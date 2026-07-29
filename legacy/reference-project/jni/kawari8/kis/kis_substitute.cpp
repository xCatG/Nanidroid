//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// KawariInlineScript -- toupper, tolower --
//
//      Programed by Chikara.H (MDR)
//
//  2001.09.04  created(9割はkis_escape.cppから拝借）
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "kis/kis_substitute.h"
#include "misc/l10n.h"
//---------------------------------------------------------------------------
#include <ctype.h>
#include <cstdlib>
using namespace std;
//---------------------------------------------------------------------------
string KIS_toupper::Function(const vector<string>& args)
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
				if( 'a' <= args[i][j] && args[i][j] <= 'z') {
					retstr+= toupper(args[i][j]);
				} else {
					retstr+=args[i][j];
				}
			}
		}
	}


	return(retstr);
}
//---------------------------------------------------------------------------
string KIS_tolower::Function(const vector<string>& args)
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
				if( args[i][j] >= 'A'  && args[i][j] <= 'Z') {
					retstr+= tolower(args[i][j]);
				} else {
					retstr+=args[i][j];
				}
			}
		}
	}


	return(retstr);
}
//

//---------------------------------------------------------------------------

