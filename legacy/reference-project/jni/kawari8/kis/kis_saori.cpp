//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// KawariInlineScript -- SAORI --
//
//  2001.03.28 initial
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "kis/kis_saori.h"
//---------------------------------------------------------------------------
#include "libkawari/kawari_engine.h"
#include "libkawari/kawari_log.h"
#include "libkawari/kawari_rc.h"
#include "misc/misc.h"
#include "misc/phttp.h"
using namespace kawari::resource;
using namespace kawari_log;
//---------------------------------------------------------------------------
#include <cstdlib>
#include <map>
using namespace std;
//---------------------------------------------------------------------------
string KIS_saoriregist::Function(const vector<string>& args)
{
	if(!AssertArgument(args, 3, 4)) return ("");

	TKawariEngine::SAORILOADTYPE loadopt=TKawariEngine::LOADONCALL;
	if (args.size()>=4){
		if (args[3]=="preload")
			loadopt=TKawariEngine::PRELOAD;
		else if (args[3]=="noresident")
			loadopt=TKawariEngine::NORESIDENT;
	}
	Engine->RegisterSAORIModule(args[2], CanonicalPath(Engine->GetDataPath(), args[1]), loadopt);

	return ("");
}
//---------------------------------------------------------------------------
string KIS_saorierase::Function(const vector<string>& args)
{
	if(!AssertArgument(args, 2, 2)) return ("");
	Engine->EraseSAORIModule(args[1]);
	return ("");
}
//---------------------------------------------------------------------------
string KIS_saorilist::Function(const vector<string>& args)
{
	if(!AssertArgument(args, 2, 2)) return ("");

	const string stem=args[1];
	if (stem.size()==0) return ("");

	vector<string> list;
	if (Engine->ListSAORIModule(list)){
		for (vector<string>::iterator it=list.begin(); it!=list.end(); it++){
			Engine->Push(stem, (*it));
		}
	}
	return ("");
}
//---------------------------------------------------------------------------
bool KIS_callsaori::CallSaori(
	const string& saoriname, const vector<string>& args, TPHMessage &response)
{
	// リクエスト生成
	TPHMessage request;
	request.SetStartline("EXECUTE SAORI/1.0");
	request["Sender"]="kawari";
	request["Charset"]="Shift_JIS";
	string slevel=Engine->IndexParse("system.Sender.Path");
	// shiori.dllから動かした場合、Sender.Pathは必ず設定される。
	// そうでないのは幸水。だからLocalで良い。
	request["SecurityLevel"]=(slevel.size())?
		((slevel=="local")? string("Local") : slevel): string("Local");
	for (unsigned int i=0; i<args.size(); i++)
		request["Argument"+IntToString(i)]=args[i];

	// リクエスト
	if ((!Engine->RequestToSAORIModule(saoriname, request, response))
		||(!response.GetStartline().size())){
		// Failed
		GetLogger().GetStream(LOG_ERROR) << RC.S(ERR_KIS_SAORI_CALL_FAILED1) << saoriname << RC.S(ERR_KIS_SAORI_CALL_FAILED2) << endl;
		return false;
	}

	// ステータスラインの分解
	const string &statusline=response.GetStartline();
	unsigned int pos=statusline.find(' ');
	if (pos==string::npos) return false;
	unsigned int npos=statusline.find(' ', pos+1);
	string statuscode=statusline.substr(pos+1, npos-pos-1);

	if (!(statuscode[0]=='2')) return false;
	return true;
}
//---------------------------------------------------------------------------
string KIS_callsaori::Function(const vector<string>& args)
{
	if(!AssertArgument(args, 2)) return ("");

	const string saoriname=args[1];
	vector<string> saori_args;
	for (unsigned int i=2; i<args.size(); i++)
		saori_args.push_back(args[i]);

	TPHMessage response;

	if (CallSaori(saoriname, saori_args, response)){
		if (response.count("Result"))
			return response["Result"];
		else
			return ("");
	}else{
		return ("");
	}
}

//---------------------------------------------------------------------------
string KIS_callsaorix::Function(const vector<string>& args)
{
	if(!AssertArgument(args, 3)) return ("");

	const string saoriname=args[1];
	const string stem=args[2];
	vector<string> saori_args;
	for (unsigned int i=3; i<args.size(); i++)
		saori_args.push_back(args[i]);

	TPHMessage response;

	if (CallSaori(saoriname, saori_args, response)){
		if (stem.size()){
			string stemp=stem+'.';
			int value_max=0;
			for (TPHMessage::iterator it=response.begin(); it!=response.end(); it++){
				const string key=it->first;
				if (key.find("Value")==0){
					int num=atoi(key.c_str()+5)+1;
					if (num>value_max) value_max=num;
				}
				Engine->PushStrAfterClear(stemp+key, it->second);
			}
			Engine->PushStrAfterClear(stemp+"size", IntToString(value_max));
			Engine->PushStrAfterClear(stem, response.GetStartline());
		}
		if (response.count("Result"))
			return response["Result"];
		else
			return ("");
	}else{
		return ("");
	}
}
//---------------------------------------------------------------------------
