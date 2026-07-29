//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// 暫定shiori.dll
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.02.03  Phase 0.3     ノーコメント
//  2001.02.11  Phase 0.31    再帰定義実装
//  2001.02.23  Phase 0.4     卒論戦争終戦祈念
//                            召薫造蕕�にお眠り下さい、過ちは繰り返しませあ�
//                                                                バージョン
//                            kawari.ini導入
//                            複数辞書ファイル
//                            sentence.txt廃止
//  2001.02.27  Phase 0.41    週末全くやれなかったバグ取り(getword関連)
//  2001.03.15  Phase 0.42    unload予約
//                            ロギング機能
//                            暗号化ファイル対応
//                            漢字エントリ対応
//                            ローカル変数対応
//  2001.04.25  Phase 0.50a1  レスポンス対応
//  2001.04.27  Phase 0.50a2  SHIORI/2.1対応
//  2001.04.28  Phase 0.50a3  COMMUNICATE戦争
//       |
//  2001.05.02
//  2001.05.03  Phase 0.50a4  インラインスクリプト
//  2001.05.12  Phase 0.50b2  Piroject-X 完結編
//                            SHIORI/2.2対応
//                            インラインスクリプトを$()に変更
//  2001.05.30  Phase 5.1     インタープリタ・コンパイラ化
//                            Phase0.50b2までのバグフィックス
//  2001.05.31  Phase 5.2     保守的piro
//  2001.06.10  Phase 5.3.1   GET Version対策
//                            偽SHIORI/2.4実装 (^_^;
//  2001.06.18  Phase 5.4     インラインスクリプト大幅強化
//  2001.07.10  Phase 6.0     getmoduleversion追加
//                            バグフィックス
//  2001.07.21  Phase 6.2     SHIORI/2.5実装
//                            内部イベント追加(OnLoad,OnUnload,SHIORI/2.4)
//                            ハードコーディングのエラーメッセージ廃止
//                            kawari.iniにadddict、include追加
//  2001.08.06  Phase 6.2     Age二重カウントアップのバグ修正
//  2001.08.07  Phase 6.2     内部イベントのプレフィックスをsystem.に統一
//                            内部イベント追加(OnNotifyGhost,OnNotifyOther,OnGetStatus)
//  2001.08.08  Phase 6.2     幸水用にLoadSubを新設
//  2001.08.25  Phase 6.3     セキュリティーホール対策
//  2001.08.25  Phase 7.0     セキュリティ対策(WriteProtect)
//  2001.09.23  Phase 7.0.1   セキュリティ対策をredo34以降の方式に対応
//                            Sender経路情報追加(System.Sender.Path)
//                            3エントリ以上でのAND検索バグを修正
//                            SHIORI/2.3b対応
//                            インラインスクリプト強化
//                            起動速度の高速化
//                            ハイフンを使用する旧エントリの対応停止
//  2002.01.12  Phase 8.0     なかなか出来ないPhase8
//                            NIDからフィードバック
//                            kpcg Phase7.3.1からフィードバック
//  2002.03                   Phase 8.0 (Rhapsody in the Blue)は破棄
//  2002.03.10  Phase 7.9.0   Philedelphia実験
//  2002.03.15                なんか、もう、あらゆるものが消えていく・・・
//                            kawari.ini廃止,代わりにkawarirc.kis導入
//  2002.03.20                SAORI統合
//  2002.04.12  Phase 8.0.0   インターフェース切り直し
//  2002.04.19                SHIORI/3.0対応
//  2002.12.30  Phase 8.1.0   Winter Comicket Version
//                            複数インスタンスを許可(内部的に)
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include <string>
//#include <iostream>
#include <fstream>
#include <cstdlib>
#include <ctime>
#include <cctype>
using namespace std;
//---------------------------------------------------------------------------
#include "shiori/kawari_shiori.h"
#include "libkawari/kawari_log.h"
#include "misc/misc.h"
using namespace kawari_log;
//---------------------------------------------------------------------------
// SHIORI/2.x API
//---------------------------------------------------------------------------
// DLLロード時に呼ばれる
bool TKawariShioriAdapter::Load(const string& datapath)
{
	// このタイミングで乱数初期化
	SRandom((unsigned int)time(NULL));

	Engine.SetDataPath(datapath);

	Engine.CreateEntry("System.DataPath").Push(Engine.CreateStrWord(datapath));
	Engine.WriteProtect("System.DataPath");

	// 初期実行スクリプト
	Engine.LoadKawariDict(datapath+"kawarirc.kis");

	// セキュリティレベル設定
	string slv=Engine.IndexParse("System.SecurityLevel");
	if (slv.size()&&IsInteger(slv)){
		unsigned int lv=atoi(slv.c_str());
		SecurityLevel.Set(lv);
	}else{
		Engine.CreateEntry("System.SecurityLevel").Push(Engine.CreateStrWord(IntToString(SecurityLevel.Get())));
	}
	Engine.WriteProtect("System.SecurityLevel");

	// 初期化終了
	initialized=true;

	Engine.GetLogger().GetStream(LOG_INFO) << "[SHIORI/SAORI Adapter] Load finished." << endl;
	return(true);
}
//---------------------------------------------------------------------------
// DLLアンロード時に呼ばれる
bool TKawariShioriAdapter::Unload(void)
{
	string aistr=EnumExec("System.Callback.OnUnload");

	Engine.GetLogger().GetStream(LOG_INFO) << "[SHIORI/SAORI Adapter] Unload." << endl;

	return(true);
}
//---------------------------------------------------------------------------
// 偽AIリクエスト
void TKawariShioriAdapter::Request(TPHMessage &request, TPHMessage &response)
{
	const string &reqline=request.GetStartline();
	TKawariLogger &Logger=Engine.GetLogger();

	// リクエストコマンドの抽出
	bool saori=false;
	string::size_type pos=reqline.find(" SHIORI/");
	if(pos==string::npos) {
		pos=reqline.find(" SAORI/");
		saori=true;
	}
	string type=reqline.substr(0,pos);

	if (!initialized){
		// 未初期化エラー
		if(!saori) {
			response.SetStartline("SHIORI/"SHIORIVER" 500 Internal Server Error");
		} else {
			response.SetStartline("SAORI/"SAORIVER" 500 Internal Server Error");
		}
		return;
		// KIU_TODO: エラーログを吐きましょう
	}

	// ログ取りのチェック
	unsigned int evtchk;
	if(type=="GET"){
		// GET SHIORI/3.0
		if(request["ID"].find("On")==0){
			// イベント
			if((request["ID"]=="OnSecondChange")||(request["ID"]=="OnMinuteChange"))
				// 時刻イベント
				evtchk=LOG_TIMEEVENTS;
			else if(request["ID"].find("OnMouse")==0)
				// マウスイベント
				evtchk=LOG_MOUSEEVENTS;
			else
				// その他
				evtchk=LOG_BASEEVENTS;
		}else{
			// リソース
			evtchk=LOG_RSCEVENTS;
		}
	}else if(type=="NOTIFY"){
		// NOTIFY SHIORI/3.0
		evtchk=LOG_BASEEVENTS;
	}else if((type=="GET String")&&(request.count("ID"))){
		// GET String SHIORI/2.0
		// リソース
		evtchk=LOG_RSCEVENTS;
	}else if(request.count("Event")) {
		// イベント
		if((request["Event"]=="OnSecondChange")||(request["Event"]=="OnMinuteChange")){
			// 時刻イベント
			evtchk=LOG_TIMEEVENTS;
		}else if (request["Event"]=="OnMouseMove"){
			// マウスイベント
			evtchk=LOG_MOUSEEVENTS;
		}else{
			evtchk=LOG_BASEEVENTS;
		}
	}else{
		evtchk=LOG_BASEEVENTS;
	}
	bool logmode=Logger.Check(evtchk);

	if(logmode) {
		Logger.GetStream() << "[SHIORI/SAORI Adapter] Query sequence begin." << endl;
		Logger.GetStream() << "---------------------- REQUEST" << endl;
		request.Dump(Logger.GetStream());
	}

	unsigned int statuscode=400;

	// 伝達経路を確認
	string sender_path_name;
	TSecurityLevel::TSenderPath sender_path;
	GetSenderPath(request["SecurityLevel"],sender_path,sender_path_name);

	if(SecurityLevel.Check(sender_path)) {

		// リクエストヘッダを辞書に格納
		Engine.ClearTree("System.Request");
		Engine.CreateEntry("System.Request").Push(Engine.CreateStrWord(type));
		for(TMMap<string,string>::const_iterator it=request.begin();it!=request.end();it++) {
			Engine.CreateEntry("System.Request."+Engine.EncodeEntryName(it->first)).Push(Engine.CreateStrWord(it->second));
		}

		// 辞書内のレスポンスエントリを消去
		Engine.ClearTree("System.Response");

		// SHIORI/3.0
		if(type=="GET"){
			const string &reqid=request["ID"];
			string aistr;

			// デフォルトKOSUIインターフェースはオーバーライド不可能
			if (reqid=="ShioriEcho"){
				if (Engine.IndexParse("System.Debugger")=="on"){
					Engine.ClearEntry("System.Request.Reference0");

					aistr=Engine.Parse(request["Reference0"])+"\\e";
				}
			}else{
				aistr=EnumExec("System.Callback.OnGET");

				GetResponse(response);
				response.erase("Value");

				// version/craftman/craftmanw/shioriid/nameは、
				// ユーザがオーバーライド可能
				if (!aistr.size()){
					if (reqid=="version")
						aistr=KAWARI_MAJOR"."KAWARI_MINOR"."KAWARI_SUBMINOR;
					else if ((reqid=="craftman")||(reqid=="craftmanw"))
						aistr=KAWARI_AUTHOR;
					else if ((reqid=="shioriid")||(reqid=="name"))
						aistr=KAWARI_NAME;
				}
			}

			if (aistr.size()){
				response["Value"]=aistr;
				statuscode=200;
			}else{
				statuscode=204;
			}
			response["Sender"]=GhostName;
			response["Charset"]="Shift_JIS";

		}else if(type=="NOTIFY"){
			const string &reqid=request["ID"];
			if (reqid=="ownerghostname"){
				GhostName=request["Reference0"];
			}
			string aistr=EnumExec("System.Callback.OnNOTIFY");

			GetResponse(response);
			response.erase("Value");
			response["Sender"]=GhostName;
			response["Charset"]="Shift_JIS";

			statuscode=204;
		}else
			// 以降 SHIORI/2.6
			if(type=="GET Sentence") {
			// 単純発話とかイベントとか色々

			string aistr;
			if(request.count("Sentence")) {
				// コミュニケートの応答
				aistr=EnumExec("System.Callback.OnGetSentence");
			} else {
				// KOSUIインターフェース
				if (request["Event"]=="ShioriEcho"){
					if (Engine.IndexParse("System.Debugger")=="on"){
						Engine.ClearEntry("System.Request.Reference0");
						aistr=Engine.Parse(request["Reference0"])+"\\e";
					}
				}else{
					// イベント処理/単純発話
					aistr=EnumExec("System.Callback.OnEvent");
				}
			}

			// 
			GetResponse(response);
			response.erase("Sentence");
			response.erase("Age");
			response["Sender"]=GhostName;
			response["Charset"]="Shift_JIS";

			if(aistr.size()){
				response["Sentence"]=aistr;
				statuscode=200;
			}else{
				statuscode=204;
			}

			// ゴースト間コミュニケートの処理
			// 無言電話もできます
			string targetghost=response["To"];
			response.erase("To");
			string reqsender=request["Sender"];
			if((!targetghost.size())&&
			   ((!request.count("Age"))||(!reqsender.size())||
				(reqsender=="User")||(reqsender=="Nobody"))){
				// コミュニケート終了
			}else if(targetghost=="stop"){
				// コミュニケート終了
			}else {
				int age=0;
				if(targetghost.size()&&(targetghost!=reqsender)){
					// 新しい相手にコミュニケート
				}else{
					// Ageのインクリメント
					targetghost=reqsender;
					if(request.count("Age"))
						age=atoi(request["Age"].c_str())+1;
				}
				response["To"]=targetghost;
				response["Age"]=IntToString(age);
			}
		} else if(type=="GET Status") {
			string aistr=Engine.IndexParse("System.Callback.OnGetStatus");
			if(aistr.size()>0) {
				response["Status"]=aistr;
			} else {
				response["Status"]=
				 IntToString(Engine.WordCollectionSize())+","
				 +IntToString(Engine.EntryCollectionSize())+","
				 +"100,"
				 +KAWARI_MAJOR KAWARI_MINOR KAWARI_SUBMINOR ","	// 華和梨のバージョン番号
				 +"100,"
				 +"100";
			}
			statuscode=200;
		} else if((type=="GET String")&&(request.count("ID"))) {
			// SHIORI/2.5
			string aistr=Engine.IndexParse("System.Callback.OnResource");
			response.erase("String");
			if (aistr.size()){
				statuscode=200;
				response["String"]=aistr;
			}else{
				statuscode=204;
			}
		} else if(type=="NOTIFY OwnerGhostName") {
			// ゴースト名の通知
			GhostName=request["Ghost"];
			statuscode=200;
			EnumExec("System.Callback.OnRequest");
		} else if(type=="GET Version") {
			// 初期化前のGET Versionだけはshiori_interface.cppで既に処理している
			if(!saori) {
				response["ID"]=KAWARI_NAME;
				response["Craftman"]=KAWARI_AUTHOR;
				response["Version"]=KAWARI_MAJOR"."KAWARI_MINOR"."KAWARI_SUBMINOR;
			} else {
				// 仕様書通りだと"200 OK"も付けられない事になるが、はて？
//				return("SAORI/1.0");
			}
			statuscode=200;
		} else if(saori&&(type=="EXECUTE")) {
			// SAORI実行
			EnumExec("System.Callback.OnSaoriExecute");
			statuscode=GetResponse(response);
			if(statuscode==0) statuscode=400;
		} else {
			// その他全てのリクエスト
			EnumExec("System.Callback.OnRequest");
			statuscode=GetResponse(response);
			if(statuscode==0) statuscode=400;
		}
	} else {
		// 実行許可なし
	}

	string statusheader;
	switch(statuscode) {
	case 200:
		statusheader="200 OK";
		break;
	case 204:
		statusheader="204 No Content";
		break;
	case 311:
		statusheader="311 Not Enough";
		break;
	case 312:
		statusheader="312 Advice";
		break;
	case 400:
		statusheader="400 Bad Request";
		break;
	default:
		statusheader="500 Internal Server Error";
		break;
	}

	if(!saori)
		response.SetStartline("SHIORI/"SHIORIVER" "+statusheader);
	else
		response.SetStartline("SAORI/"SAORIVER" "+statusheader);

	if (logmode){
		Logger.GetStream() << "--------------------- RESPONSE" << endl;
		response.Dump(Logger.GetStream());
		Logger.GetStream() << "[SHIORI/SAORI Adapter] Query sequence end." << endl;
	}
}
//---------------------------------------------------------------------------
// 以下はAPI以外
//---------------------------------------------------------------------------
// Senderを伝達経路情報と分離
void TKawariShioriAdapter::GetSenderPath(const string &senderstr,
 TKawariShioriAdapter::TSecurityLevel::TSenderPath &sender_path,string &sender_path_name)
{
	string sender=StringTrim(senderstr);

	if((sender=="local")||(sender=="Local")) {
		sender_path=TSecurityLevel::tsidLocal;
		sender_path_name="local";
	} else if((sender=="external")||(sender=="External")) {
		sender_path=TSecurityLevel::tsidExternal;
		sender_path_name="external";
	} else if(sender=="") {
		// 往々にしてSecurityLevelは渡ってこない
		sender_path=TSecurityLevel::tsidLocal;
		sender_path_name="local";
	}else{
		sender_path=TSecurityLevel::tsidUnknown;
		sender_path_name="unknown";
	}

	return;
}
//---------------------------------------------------------------------------
// 指定したエントリの単語を全て呼び出す
string TKawariShioriAdapter::EnumExec(const string& key)
{
	TEntry entry=Engine.GetEntry(key);
	unsigned int size=entry.Size();

	string aistr;
	for(unsigned int i=0;i<size;i++) aistr+=Engine.IndexParse(entry,i);

	return(aistr);
}
//---------------------------------------------------------------------------
// 辞書内のレスポンスを全て読み出す
int TKawariShioriAdapter::GetResponse(TPHMessage &response)
{
	const char *resentryname="System.Response";
	TEntry resentry=Engine.GetEntry(resentryname);

	if (!resentry.IsValid()) return 0;

	vector<TEntry> entrycol;
	resentry.FindTree(entrycol);

	for(unsigned int i=0;i<entrycol.size();i++) {
		if(entrycol[i]!=resentry) {
			string key=entrycol[i].GetName();
			key=key.substr(strlen(resentryname)+1);
			string str=Engine.IndexParse(entrycol[i]);
			if(str.size()) response[key]=str;
		}
	}

	return((int)atoi(Engine.IndexParse(resentry).c_str()));
}
//---------------------------------------------------------------------------
TKawariShioriFactory *TKawariShioriFactory::instance=NULL;
//---------------------------------------------------------------------------
// デストラクタ
TKawariShioriFactory::~TKawariShioriFactory(){
	typedef vector<TKawariShioriAdapter *> TKawariList;
	// 全てのインスタンスを破棄
	for(TKawariList::iterator it=list.begin(); it!=list.end(); it++)
		if (*it)
			delete (*it);
	list.clear();
}
//---------------------------------------------------------------------------
// 新規インスタンスの作成
unsigned int TKawariShioriFactory::CreateInstance(const string &datapath){
	TKawariShioriAdapter *instance=new TKawariShioriAdapter();
	if (!instance->Load(datapath)){
		delete instance;
		return 0;
	}

	// serching NULL entry
	int index=-1;
	int list_max=list.size();
	for(int i=0; i<list_max; i++)
		if (!list[i])
			index=i;
	if (index==-1){
		list.push_back(instance);
		return list.size();
	}else{
		list[index]=instance;
		return index+1;
	}
}
//---------------------------------------------------------------------------
// インスタンスの削除
bool TKawariShioriFactory::DisposeInstance(unsigned int h){
	if ((h==0)||(h>list.size())) return false;
	TKawariShioriAdapter *instance=list[(int)h-1];
	if (!instance) return false;
	instance->Unload();
	delete instance;
	list[h-1]=NULL;
	return true;
}
//---------------------------------------------------------------------------
// リクエスト
string TKawariShioriFactory::RequestInstance(unsigned int h, const string &reqstr){
	if ((h==0)||(h>list.size())) return ("");
	TKawariShioriAdapter *instance=list[(int)h-1];
	if (!instance) return ("");

	TPHMessage mreq, mres;
	mreq.Deserialize(reqstr);
	instance->Request(mreq, mres);
	return mres.Serialize();
}
//---------------------------------------------------------------------------
// 以下のエントリは特別扱いされる(Phase7.9.0にて全面的に変更)
//
// 本体からの通知情報
// System.Request.*               : SHIORI/2.0 リクエストヘッダ
//
// 本体への応答
// System.Response.*              : SHIORI/2.0 レスポンス
// System.Response.To             : 話し掛けたいゴースト名
//                                  "stop"でCOMMUNICATE打ち切り
// System.Response                : SHIORI/2.0 ステータスコード
//
// イベント処理
// System.Callback.OnUnload       : 切り離しイベント
// System.Callback.OnEvent        : 外部イベント処理
// System.Callback.OnResource     : リソース取得イベント
// System.Callback.OnGetSentence  : 応答イベント
// System.Callback.OnGetStatus    : ステータス取得イベント
// System.Callback.OnSaoriExecute : SAORI実行
// System.Callback.OnRequest      : その他全てのリクエスト
//
// その他
// System.DataPath                : shiori.dllの存在するディレクトリ
//
//---------------------------------------------------------------------------
