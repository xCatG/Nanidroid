//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// KawariInlineScript -- システム操作 --
//
//      Programed by Suikyo.
//
//  2002.04.20  Phase 8.0.0   従来kawari.iniにあったコマンドの一部
//  2002.06.23  Phase 8.0.0   KIS_rccharset 追加
//  2002.11.20  Phase 8.1.0   ログストリームをエンジン単位で管理
//  2003.11.18  Phase 8.2.0   getenv追加
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "kis/kis_system.h"
#include "libkawari/kawari_engine.h"
#include "libkawari/kawari_log.h"
#include "misc/misc.h"
using namespace kawari_log;
//---------------------------------------------------------------------------
#include <fstream>
#include <cstdlib>
using namespace std;
//---------------------------------------------------------------------------
string KIS_securitylevel::Function(const vector<string>& args){
	if(!AssertArgument(args, 2, 2)) return ("");
	if (initialized){
		GetLogger().GetStream(LOG_ERROR) << "SecurityLevel is already fixed." << endl;
		return ("");
	}

	unsigned int lv=2;
	if (IsInteger(args[1])){
		lv=(unsigned int)atoi(args[1].c_str());
	}else if(args[1]=="low"){
		lv=0;
	}else if(args[1]=="middle"){
		lv=1;
	}else if(args[1]=="high"){
		lv=2;
	}else if(args[1]=="ultrahigh"){
		lv=3;
	}
	Engine->PushStrAfterClear("System.SecurityLevel", IntToString(lv));
	Engine->WriteProtect("System.SecurityLevel");
	initialized=true;
	if (GetLogger().Check(LOG_INFO)){
		switch (lv){
		case 0:
			GetLogger().GetStream() << "SecurityLevel: low" << endl; break;
		case 1:
			GetLogger().GetStream() << "SecurityLevel: middle" << endl; break;
		case 2:
			GetLogger().GetStream() << "SecurityLevel: high" << endl; break;
		case 3:
			GetLogger().GetStream() << "SecurityLevel: ultrahigh" << endl; break;
		}
	}
	return ("");
}
//---------------------------------------------------------------------------
string KIS_logfile::Function(const vector<string>& args)
{
	if (args.size()==1){
		// ロギング中止
		GetLogger().SetStream(NULL);
		if (filestream){
			delete filestream;
			filestream=NULL;
		}
	}else if (args.size()>=2){
		if (filestream)
			delete filestream;
		filestream=NULL;
		if (args[1]=="-"){
			GetLogger().SetStreamStdOut();
		}else{
			filestream=new ofstream(CanonicalPath(Engine->GetDataPath(), args[1]).c_str());
			if (filestream)
				GetLogger().SetStream(filestream);
		}
	}
	return ("");
}
//---------------------------------------------------------------------------
KIS_logfile::~KIS_logfile(){
	// ロギング中止
	GetLogger().SetStream(NULL);
	if (filestream)
		delete filestream;
	filestream=NULL;
}
//---------------------------------------------------------------------------
string KIS_loglevel::Function(const vector<string>& args)
{
	if (args.size()==1)
		return IntToString(GetLogger().ErrLevel());

	unsigned int level=0;
	if (IsInteger(args[1])){
		level=atoi(args[1].c_str());
	}else{
		for (unsigned int i=1; i<args.size(); i++){
			if (args[i]=="error")
				level|=LOG_ERROR;
			else if (args[i]=="warning")
				level|=LOG_WARNING;
			else if (args[i]=="info")
				level|=LOG_INFO;
			else if (args[i]=="decl")
				level|=LOG_DECL;
			else if (args[i]=="paranoia")
				level|=LOG_ERROR|LOG_WARNING|LOG_INFO|LOG_DECL|LOG_DUMP;
			else if (args[i]=="baseevents")
				level|=LOG_BASEEVENTS;
			else if (args[i]=="mouseevents")
				level|=LOG_MOUSEEVENTS;
			else if (args[i]=="rscevents")
				level|=LOG_RSCEVENTS;
			else if (args[i]=="timeevents")
				level|=LOG_TIMEEVENTS;
			else if (args[i]=="quiet")
				level=0;
		}
	}
	GetLogger().SetErrLevel(level);
	return ("");
}
//---------------------------------------------------------------------------
string KIS_debugger::Function(const vector<string>& args)
{
	if(!AssertArgument(args, 2, 2)) return ("");
	if(args[1]=="on"){
		Engine->PushStrAfterClear("System.Debugger", "on");
		GetLogger().GetStream(LOG_INFO) << "Debugger: on" << endl;
	}else if(args[1]=="off"){
		Engine->ClearEntry("System.Debugger");
		GetLogger().GetStream(LOG_INFO) << "Debugger: off" << endl;
	}
	return ("");
}
//---------------------------------------------------------------------------
string KIS_rccharset::Function(const vector<string>& args){
	if(!AssertArgument(args, 2, 2)) return ("");

	RC.SwitchTo(args[1]);
	return ("");
}
//---------------------------------------------------------------------------
string KIS_getenv::Function(const vector<string>& args)
{
	if(!AssertArgument(args, 2, 2)) return ("");

	char *p;
	p=getenv(args[1].c_str());
	if(p==NULL) return("");
	string str(p);
	return(str);
}
//---------------------------------------------------------------------------
