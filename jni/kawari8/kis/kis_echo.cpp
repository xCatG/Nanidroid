//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// KawariInlineScript -- エコー --
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.05.25  Phase 5.1     First version
//  2002.03.17  Phase 7.9.0   stdout
//  2002.04.15  Phase 8.0.0   stdout -> logprint
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "kis/kis_echo.h"
//---------------------------------------------------------------------------
#include "misc/misc.h"
#include "libkawari/kawari_engine.h"
#include "libkawari/kawari_log.h"
//---------------------------------------------------------------------------
string KIS_echo::Function(const vector<string>& args)
{
	if(args.size()<2) return("");

	string retstr=args[1];

	for(unsigned int i=2;i<args.size();i++) retstr+=string(" ")+args[i];

	return(retstr);
}
//---------------------------------------------------------------------------
string KIS_logprint::Function(const vector<string>& args)
{
	TKawariLogger &Logger=GetLogger();
	if(args.size()>=2) {
		Logger.GetStream() << args[1];
		for(unsigned int i=2;i<args.size();i++) Logger.GetStream() << " " << args[i];
	}

	Logger.GetStream() << endl;

	return("");
}
//---------------------------------------------------------------------------

