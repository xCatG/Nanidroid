//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// SAORI インターフェース(汎用)
//
//      Programed by Suikyo.
//
//  2002.04.15  Phase 8.0.0   えびさわさんバージョンを参考に導入
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "saori/saori.h"
#include "saori/saori_module.h"
#include "libkawari/kawari_log.h"
//---------------------------------------------------------------------------
using namespace kawari_log;
using namespace std;
using namespace saori;
//---------------------------------------------------------------------------
TBind::TBind(IModuleFactory &fac, TKawariLogger &lgr, const string &path, const LOADTYPE t)
	 : type(t), libpath(path), module(NULL), factory(fac), logger(lgr)
{
	if (type==PRELOAD)
		Attach();
}
//---------------------------------------------------------------------------
// SAORI/1.0 request
bool TBind::Request(const TPHMessage &request, TPHMessage &response)
{
	Attach();
	if (!module)
		return false;

	bool ret=Query(request, response);

	if (type==NORESIDENT)
		Detach();

	return ret;
}
//---------------------------------------------------------------------------
bool TBind::Query(const TPHMessage &request, TPHMessage &response){

	if (logger.Check(LOG_INFO)){
		logger.GetStream() << "[SAORI] Query to ("+libpath+")" << endl
			<< "---------------------- REQUEST" << endl;
		request.Dump(logger.GetStream());
	}

	// シリアライズ
	string reqstr=request.Serialize();

	// Request
	string resstr=module->Request(reqstr);

	// デシリアライズ
	response.Deserialize(resstr);

	if (logger.Check(LOG_INFO)){
		logger.GetStream() << "----------------------RESPONSE" << endl;
		response.Dump(logger.GetStream());
		logger.GetStream() << "[SAORI] Query end." << endl;
	}

	return true;
}
//---------------------------------------------------------------------------
void TBind::Attach(void){
	if (module) return;
	module=factory.CreateModule(libpath);
	if (!module){
		logger.GetStream(LOG_ERROR) << "[SAORI] module attach failed" << endl;
		return;
	}

	TPHMessage request, response;

	// GET Version チェック
	request.SetStartline("GET Version SAORI/1.0");
	request["Charset"]="Shift_JIS";
	request["Sender"]="kawari";

	Query(request, response);

	if (response.GetStartline().find("SAORI/1.")!=0){
		logger.GetStream(LOG_ERROR) << "[SAORI] SAORI version mismatch." << endl;
		Detach();
		return;
	}else{
		logger.GetStream(LOG_INFO) << "[SAORI] (" << libpath << ") attached." << endl;
	}
}
//---------------------------------------------------------------------------
void TBind::Detach(void){
	if (module){
		factory.DeleteModule(module);
		module=NULL;
	}
	logger.GetStream(LOG_INFO) << "[SAORI] (" << libpath << ") detached." << endl;
}
//---------------------------------------------------------------------------
TBind::~TBind(){
	Detach();
}
//---------------------------------------------------------------------------
// モジュールの登録
void TSaoriPark::RegisterModule(const string &aliasname, const string &path, const saori::LOADTYPE type){
	// 2002/09/02 多重ロード対策 suikyo@yk.rim.or.jp
	if (aliasmap.count(aliasname))
		EraseModule(aliasname);
	TBind *module=new TBind((*factory), logger, path, type);
	aliasmap[aliasname]=module;

	logger.GetStream(LOG_INFO) << "[SAORI] Registered \"" << aliasname << "\" = (" << path << ")" << endl;
}
//---------------------------------------------------------------------------
// モジュール登録の削除
void TSaoriPark::EraseModule(const string &alias){
	if (aliasmap.count(alias)){
		TBind *module=aliasmap[alias];
		delete module;
		aliasmap.erase(alias);
		logger.GetStream(LOG_INFO) << "[SAORI] Unregistered (" << alias << ")" << endl;
	}else{
		logger.GetStream(LOG_WARNING) << "[SAORI] Can not unregister (" << alias << "). not found." << endl;
	}
}
//---------------------------------------------------------------------------
// モジュールを得る
TBind * const TSaoriPark::GetModule(const string &alias) {
	if (aliasmap.count(alias)){
		return aliasmap[alias];
	}else{
		logger.GetStream(LOG_ERROR) << "[SAORI] module (" << alias << ") not found." << endl;
		return NULL;
	}
}
//---------------------------------------------------------------------------
// 登録済みモジュール名のリストを得る
int TSaoriPark::ListModule(vector<string> &list)
{
	logger.GetStream(LOG_INFO) << "listmodule" << endl;
	int ret=0;
	map<string, TBind *>::const_iterator it=aliasmap.begin();
	for(;it!=aliasmap.end(); it++){
		logger.GetStream(LOG_INFO) << "[SAORI] found(" << it->first << ")" << endl;
		list.push_back(it->first);
		ret++;
	}
	return ret;
}
//---------------------------------------------------------------------------
TSaoriPark::TSaoriPark(TKawariLogger &lgr) : logger(lgr) {
	factory=new TModuleFactoryMaster(logger);
}
//---------------------------------------------------------------------------
// 全モジュールのアンロード
TSaoriPark::~TSaoriPark(){
	for(map<string, TBind *>::iterator it=aliasmap.begin(); it!=aliasmap.end(); it++){
		if (it->second){
			delete it->second;
		}
	}
	delete factory;
}
//---------------------------------------------------------------------------
