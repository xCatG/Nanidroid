//---------------------------------------------------------------------------
//
// KOSUI -- Kawari cOnSole Use Interpritor --
// 幸水 -- コンソール版華和梨インタープリタ --
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.05.30  Phase 5.1     コンソールインタープリタ
//  2001.08.06  Phase 6.2     KawariDebuggerに合わせてデフォルトをecho-modeに
//  2001.08.12  Phase 6.2.1   KawariDebuggerと統合
//  2002.01.08  番外          NothingIsDone向け
//  2002.03.12  Phase 7.9.0   Attach入力の所のちんまいバグ修正
//  2002.04.12  Phase 8.0.0   インターフェース切り直し
//  2008.01.23  Phase 8.2.5   quietモード追加
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#ifdef __BORLANDC__
#	define _POSIX_
#	include <limits.h>
#endif
//---------------------------------------------------------------------------
#include <string>
#include <iostream>
#include <cstdlib>
using namespace std;
//---------------------------------------------------------------------------
#include "tool/kosui_base.h"
#include "tool/kawari_kosui.h"
#include "misc/l10n.h"
#ifdef USEKDB
#	include "tool/kdb.h"
#	include "tool/kosui_dsstp.h"
#endif
#if defined(KAWARI_POSIX)
#	include <unistd.h>
#	include <limits.h>
#else
#	include <direct.h>
#endif
#ifdef _MSC_VER
#define PATH_MAX _MAX_PATH
#endif
//---------------------------------------------------------------------------
// 接続するゴーストを選ぶ
TKawariInterface_base* AttachTarget(const string& datapath,const string& inifile,const string& event)
{
	cout << "[" << 0 << "] : " << " Interpreter mode." << endl;

	#ifdef USEKDB
		// 華和梨デバッガを組み込む場合
		map<HWND,string> ghostname;
		GetGhostList(ghostname);

		if(ghostname.size()>0) {
			vector<HWND> ghostlist;
			map<HWND,string>::iterator it;
			for(it=ghostname.begin();it!=ghostname.end();it++) {
				ghostlist.push_back(it->first);
				cout << "[" << ghostlist.size() << "] : " << it->second << endl;
			}

			unsigned int no;
			while(true) {
				cout << "Attach : ";
				string buff;
				getline(cin,buff);
				no=atoi(buff.c_str());
				if(no<=ghostlist.size()) break;
			}

			if(no>0) return(new TKosuiDSSTPInterface(ghostlist[no-1],event));
		}
	#endif

	return(new TKawariKosuiAdapter(datapath,inifile));
}
//---------------------------------------------------------------------------
int main(int argc,char *argv[])
{
	string datapath;
	char cwd[PATH_MAX+1];
	if(NULL==getcwd(cwd, PATH_MAX+1)){
		datapath=CanonicalPath("./");
	}else{
		datapath=cwd;
		if(datapath.length()&&(datapath[datapath.length()-1]!=FILE_SEPARATOR))
			datapath+=FILE_SEPARATOR;
	}
	string inifile="kawarirc.kis";
	string event="ShioriEcho";
	bool quietmode=false;

	if(argc>1) {
		for (int i=1; i<argc; i++){
			string param=argv[i];
			if ((param=="--event")||(param=="-e")){
				if (++i<argc){
					param=argv[i];
					if (param.size())
						event=param;
				}
			}else if (param=="--norc"){
				inifile="";
			}else if (param=="--quiet"){
				quietmode=true;
			}else{
				inifile=param;
				wstring wini=ctow(inifile);
				unsigned int pos=wini.rfind((wchar_t)'/');
				if(pos==string::npos) pos=wini.rfind((wchar_t)'\\');
				if(pos!=string::npos)
					datapath=CanonicalPath(wtoc(wini.substr(0,pos+1)));
			}
		}
		if(quietmode){
			// quietモード(inifileを実行したらすぐに終了)
			TKawariInterface_base *Kawari=new TKawariKosuiAdapter(datapath,inifile);
			delete Kawari;
			return(0);
		}
	}

	cout << "K O S U I -- Kawari cOnSole Use Interpritor --" << endl;
	cout << "2001 Programed by NAKAUE.T (Meister)" << endl << endl;

	TKawariInterface_base *Kawari=AttachTarget(datapath,inifile,event);

	cout << endl;
	cout << Kawari->GetInformation() << endl << endl;

	cout << "'.' is mode change command." << endl;

	bool commode=false;
	while(true) {
		if(commode) cout << "command-mode > " << flush;
		 else cout << "echo-mode > " << flush;

		string buff;
		getline(cin,buff);
//		buff=buff.c_str();

		if(buff.size()==0) continue;

		if(buff=="exit") break;
		if(buff==".") {
			commode=!commode;
			continue;
		}

		if(commode) cout << Kawari->Parse(string("$(")+buff+")") << endl;
		 else cout << Kawari->Parse(buff) << endl;
	}

	delete Kawari;

	return(0);
}
//---------------------------------------------------------------------------

