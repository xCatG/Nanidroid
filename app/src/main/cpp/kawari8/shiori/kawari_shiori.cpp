//---------------------------------------------------------------------------
//
// "������" for ����ʳ��β����ʳ��β���
// ����shiori.dll
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.02.03  Phase 0.3     �Ρ�������
//  2001.02.11  Phase 0.31    �Ƶ��������
//  2001.02.23  Phase 0.4     ´�����轪�ﵧǰ
//                            ����¤餫�ˤ�̲�겼����������Ϸ����֤��ޤ����
//                                                                �С������
//                            kawari.iniƳ��
//                            ʣ�����ե�����
//                            sentence.txt�ѻ�
//  2001.02.27  Phase 0.41    �����������ʤ��ä��Х����(getword��Ϣ)
//  2001.03.15  Phase 0.42    unloadͽ��
//                            ����󥰵�ǽ
//                            �Ź沽�ե������б�
//                            ��������ȥ��б�
//                            ��������ѿ��б�
//  2001.04.25  Phase 0.50a1  �쥹�ݥ��б�
//  2001.04.27  Phase 0.50a2  SHIORI/2.1�б�
//  2001.04.28  Phase 0.50a3  COMMUNICATE����
//       |
//  2001.05.02
//  2001.05.03  Phase 0.50a4  ����饤�󥹥���ץ�
//  2001.05.12  Phase 0.50b2  Piroject-X ������
//                            SHIORI/2.2�б�
//                            ����饤�󥹥���ץȤ�$()���ѹ�
//  2001.05.30  Phase 5.1     ���󥿡��ץ꥿������ѥ��鲽
//                            Phase0.50b2�ޤǤΥХ��ե��å���
//  2001.05.31  Phase 5.2     �ݼ�Ūpiro
//  2001.06.10  Phase 5.3.1   GET Version�к�
//                            ��SHIORI/2.4���� (^_^;
//  2001.06.18  Phase 5.4     ����饤�󥹥���ץ���������
//  2001.07.10  Phase 6.0     getmoduleversion�ɲ�
//                            �Х��ե��å���
//  2001.07.21  Phase 6.2     SHIORI/2.5����
//                            ������٥���ɲ�(OnLoad,OnUnload,SHIORI/2.4)
//                            �ϡ��ɥ����ǥ��󥰤Υ��顼��å������ѻ�
//                            kawari.ini��adddict��include�ɲ�
//  2001.08.06  Phase 6.2     Age��ť�����ȥ��åפΥХ�����
//  2001.08.07  Phase 6.2     ������٥�ȤΥץ�ե��å�����system.������
//                            ������٥���ɲ�(OnNotifyGhost,OnNotifyOther,OnGetStatus)
//  2001.08.08  Phase 6.2     �����Ѥ�LoadSub����
//  2001.08.25  Phase 6.3     �������ƥ����ۡ����к�
//  2001.08.25  Phase 7.0     �������ƥ��к�(WriteProtect)
//  2001.09.23  Phase 7.0.1   �������ƥ��к���redo34�ʹߤ��������б�
//                            Sender��ϩ�����ɲ�(System.Sender.Path)
//                            3����ȥ�ʾ�Ǥ�AND�����Х�����
//                            SHIORI/2.3b�б�
//                            ����饤�󥹥���ץȶ���
//                            ��ư®�٤ι�®��
//                            �ϥ��ե����Ѥ���쥨��ȥ���б����
//  2002.01.12  Phase 8.0     �ʤ��ʤ�����ʤ�Phase8
//                            NID����ե����ɥХå�
//                            kpcg Phase7.3.1����ե����ɥХå�
//  2002.03                   Phase 8.0 (Rhapsody in the Blue)���˴�
//  2002.03.10  Phase 7.9.0   Philedelphia�¸�
//  2002.03.15                �ʤ󤫡��⤦���������Τ��ä��Ƥ���������
//                            kawari.ini�ѻ�,�����kawarirc.kisƳ��
//  2002.03.20                SAORI����
//  2002.04.12  Phase 8.0.0   ���󥿡��ե������ڤ�ľ��
//  2002.04.19                SHIORI/3.0�б�
//  2002.12.30  Phase 8.1.0   Winter Comicket Version
//                            ʣ����󥹥��󥹤����(����Ū��)
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
// DLL����ɻ��˸ƤФ��
bool TKawariShioriAdapter::Load(const string& datapath)
{
	// ���Υ����ߥ󥰤��������
	SRandom((unsigned int)time(NULL));

	Engine.SetDataPath(datapath);

	Engine.CreateEntry("System.DataPath").Push(Engine.CreateStrWord(datapath));
	Engine.WriteProtect("System.DataPath");

	// ����¹ԥ�����ץ�
	Engine.LoadKawariDict(datapath+"kawarirc.kis");

	// �������ƥ���٥�����
	string slv=Engine.IndexParse("System.SecurityLevel");
	if (slv.size()&&IsInteger(slv)){
		unsigned int lv=atoi(slv.c_str());
		SecurityLevel.Set(lv);
	}else{
		Engine.CreateEntry("System.SecurityLevel").Push(Engine.CreateStrWord(IntToString(SecurityLevel.Get())));
	}
	Engine.WriteProtect("System.SecurityLevel");

	// �������λ
	initialized=true;

	Engine.GetLogger().GetStream(LOG_INFO) << "[SHIORI/SAORI Adapter] Load finished." << endl;
	return(true);
}
//---------------------------------------------------------------------------
// DLL�������ɻ��˸ƤФ��
bool TKawariShioriAdapter::Unload(void)
{
	string aistr=EnumExec("System.Callback.OnUnload");

	Engine.GetLogger().GetStream(LOG_INFO) << "[SHIORI/SAORI Adapter] Unload." << endl;

	return(true);
}
//---------------------------------------------------------------------------
// ��AI�ꥯ������
void TKawariShioriAdapter::Request(TPHMessage &request, TPHMessage &response)
{
	const string &reqline=request.GetStartline();
	TKawariLogger &Logger=Engine.GetLogger();

	// �ꥯ�����ȥ��ޥ�ɤ����
	bool saori=false;
	string::size_type pos=reqline.find(" SHIORI/");
	if(pos==string::npos) {
		pos=reqline.find(" SAORI/");
		saori=true;
	}
	string type=reqline.substr(0,pos);

	if (!initialized){
		// ̤��������顼
		if(!saori) {
			response.SetStartline("SHIORI/" SHIORIVER" 500 Internal Server Error");
		} else {
			response.SetStartline("SAORI/" SAORIVER" 500 Internal Server Error");
		}
		return;
		// KIU_TODO: ���顼������Ǥ��ޤ��礦
	}

	// ������Υ����å�
	unsigned int evtchk;
	if(type=="GET"){
		// GET SHIORI/3.0
		if(request["ID"].find("On")==0){
			// ���٥��
			if((request["ID"]=="OnSecondChange")||(request["ID"]=="OnMinuteChange"))
				// ���磻�٥��
				evtchk=LOG_TIMEEVENTS;
			else if(request["ID"].find("OnMouse")==0)
				// �ޥ������٥��
				evtchk=LOG_MOUSEEVENTS;
			else
				// ����¾
				evtchk=LOG_BASEEVENTS;
		}else{
			// �꥽����
			evtchk=LOG_RSCEVENTS;
		}
	}else if(type=="NOTIFY"){
		// NOTIFY SHIORI/3.0
		evtchk=LOG_BASEEVENTS;
	}else if((type=="GET String")&&(request.count("ID"))){
		// GET String SHIORI/2.0
		// �꥽����
		evtchk=LOG_RSCEVENTS;
	}else if(request.count("Event")) {
		// ���٥��
		if((request["Event"]=="OnSecondChange")||(request["Event"]=="OnMinuteChange")){
			// ���磻�٥��
			evtchk=LOG_TIMEEVENTS;
		}else if (request["Event"]=="OnMouseMove"){
			// �ޥ������٥��
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

	// ��ã��ϩ���ǧ
	string sender_path_name;
	TSecurityLevel::TSenderPath sender_path;
	GetSenderPath(request["SecurityLevel"],sender_path,sender_path_name);

	if(SecurityLevel.Check(sender_path)) {

		// �ꥯ�����ȥإå��򼭽�˳�Ǽ
		Engine.ClearTree("System.Request");
		Engine.CreateEntry("System.Request").Push(Engine.CreateStrWord(type));
		for(TMMap<string,string>::const_iterator it=request.begin();it!=request.end();it++) {
			Engine.CreateEntry("System.Request."+Engine.EncodeEntryName(it->first)).Push(Engine.CreateStrWord(it->second));
		}

		// ������Υ쥹�ݥ󥹥���ȥ��õ�
		Engine.ClearTree("System.Response");

		// SHIORI/3.0
		if(type=="GET"){
			const string &reqid=request["ID"];
			string aistr;

			// �ǥե����KOSUI���󥿡��ե������ϥ����С��饤���Բ�ǽ
			if (reqid=="ShioriEcho"){
				if (Engine.IndexParse("System.Debugger")=="on"){
					Engine.ClearEntry("System.Request.Reference0");

					aistr=Engine.Parse(request["Reference0"])+"\\e";
				}
			}else{
				aistr=EnumExec("System.Callback.OnGET");

				GetResponse(response);
				response.erase("Value");

				// version/craftman/craftmanw/shioriid/name�ϡ�
				// �桼���������С��饤�ɲ�ǽ
				if (!aistr.size()){
					if (reqid=="version")
						aistr=KAWARI_MAJOR "." KAWARI_MINOR "." KAWARI_SUBMINOR;
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
			// �ʹ� SHIORI/2.6
			if(type=="GET Sentence") {
			// ñ��ȯ�äȤ����٥�ȤȤ�����

			string aistr;
			if(request.count("Sentence")) {
				// ���ߥ�˥����Ȥα���
				aistr=EnumExec("System.Callback.OnGetSentence");
			} else {
				// KOSUI���󥿡��ե�����
				if (request["Event"]=="ShioriEcho"){
					if (Engine.IndexParse("System.Debugger")=="on"){
						Engine.ClearEntry("System.Request.Reference0");
						aistr=Engine.Parse(request["Reference0"])+"\\e";
					}
				}else{
					// ���٥�Ƚ���/ñ��ȯ��
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

			// �������ȴ֥��ߥ�˥����Ȥν���
			// ̵�����ä�Ǥ��ޤ�
			string targetghost=response["To"];
			response.erase("To");
			string reqsender=request["Sender"];
			if((!targetghost.size())&&
			   ((!request.count("Age"))||(!reqsender.size())||
				(reqsender=="User")||(reqsender=="Nobody"))){
				// ���ߥ�˥����Ƚ�λ
			}else if(targetghost=="stop"){
				// ���ߥ�˥����Ƚ�λ
			}else {
				int age=0;
				if(targetghost.size()&&(targetghost!=reqsender)){
					// ���������˥��ߥ�˥�����
				}else{
					// Age�Υ��󥯥����
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
				 +KAWARI_MAJOR KAWARI_MINOR KAWARI_SUBMINOR ","	// �������ΥС�������ֹ�
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
			// ��������̾������
			GhostName=request["Ghost"];
			statuscode=200;
			EnumExec("System.Callback.OnRequest");
		} else if(type=="GET Version") {
			// ���������GET Version������shiori_interface.cpp�Ǵ��˽������Ƥ���
			if(!saori) {
				response["ID"]=KAWARI_NAME;
				response["Craftman"]=KAWARI_AUTHOR;
				response["Version"]=KAWARI_MAJOR "." KAWARI_MINOR "." KAWARI_SUBMINOR;
			} else {
				// ���ͽ��̤����"200 OK"���դ����ʤ����ˤʤ뤬���Ϥơ�
//				return("SAORI/1.0");
			}
			statuscode=200;
		} else if(saori&&(type=="EXECUTE")) {
			// SAORI�¹�
			EnumExec("System.Callback.OnSaoriExecute");
			statuscode=GetResponse(response);
			if(statuscode==0) statuscode=400;
		} else {
			// ����¾���ƤΥꥯ������
			EnumExec("System.Callback.OnRequest");
			statuscode=GetResponse(response);
			if(statuscode==0) statuscode=400;
		}
	} else {
		// �¹Ե��Ĥʤ�
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
		response.SetStartline("SHIORI/" SHIORIVER" "+statusheader);
	else
		response.SetStartline("SAORI/" SAORIVER" "+statusheader);

	if (logmode){
		Logger.GetStream() << "--------------------- RESPONSE" << endl;
		response.Dump(Logger.GetStream());
		Logger.GetStream() << "[SHIORI/SAORI Adapter] Query sequence end." << endl;
	}
}
//---------------------------------------------------------------------------
// �ʲ���API�ʳ�
//---------------------------------------------------------------------------
// Sender����ã��ϩ�����ʬΥ
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
		// �����ˤ���SecurityLevel���ϤäƤ��ʤ�
		sender_path=TSecurityLevel::tsidLocal;
		sender_path_name="local";
	}else{
		sender_path=TSecurityLevel::tsidUnknown;
		sender_path_name="unknown";
	}

	return;
}
//---------------------------------------------------------------------------
// ���ꤷ������ȥ��ñ������ƸƤӽФ�
string TKawariShioriAdapter::EnumExec(const string& key)
{
	TEntry entry=Engine.GetEntry(key);
	unsigned int size=entry.Size();

	string aistr;
	for(unsigned int i=0;i<size;i++) aistr+=Engine.IndexParse(entry,i);

	return(aistr);
}
//---------------------------------------------------------------------------
// ������Υ쥹�ݥ󥹤������ɤ߽Ф�
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
// �ǥ��ȥ饯��
TKawariShioriFactory::~TKawariShioriFactory(){
	typedef vector<TKawariShioriAdapter *> TKawariList;
	// ���ƤΥ��󥹥��󥹤��˴�
	for(TKawariList::iterator it=list.begin(); it!=list.end(); it++)
		if (*it)
			delete (*it);
	list.clear();
}
//---------------------------------------------------------------------------
// �������󥹥��󥹤κ���
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
// ���󥹥��󥹤κ��
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
// �ꥯ������
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
// �ʲ��Υ���ȥ�����̰��������(Phase7.9.0�ˤ�����Ū���ѹ�)
//
// ���Τ�������ξ���
// System.Request.*               : SHIORI/2.0 �ꥯ�����ȥإå�
//
// ���Τؤα���
// System.Response.*              : SHIORI/2.0 �쥹�ݥ�
// System.Response.To             : �ä��ݤ�������������̾
//                                  "stop"��COMMUNICATE�Ǥ��ڤ�
// System.Response                : SHIORI/2.0 ���ơ�����������
//
// ���٥�Ƚ���
// System.Callback.OnUnload       : �ڤ�Υ�����٥��
// System.Callback.OnEvent        : ������٥�Ƚ���
// System.Callback.OnResource     : �꥽�����������٥��
// System.Callback.OnGetSentence  : �������٥��
// System.Callback.OnGetStatus    : ���ơ������������٥��
// System.Callback.OnSaoriExecute : SAORI�¹�
// System.Callback.OnRequest      : ����¾���ƤΥꥯ������
//
// ����¾
// System.DataPath                : shiori.dll��¸�ߤ���ǥ��쥯�ȥ�
//
//---------------------------------------------------------------------------
