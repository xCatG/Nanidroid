//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// KawariInlineScript -- file attribute --
//
//      Programed by Kouji.U (sky)
//
//  2001.06.03  Created
//  2001.06.17  Phase 6.0     Phase6.0にあわせて修正 (NAKAUE.T)
//  2001.07.14  Phase 6.1     loadで絶対パス指定可能に (NAKAUE.T)
//  2001.07.19  Phase 6.2     Mingw対応 (NAKAUE.T)
//  2001.12.09  Phase 7.1.2   kis_textfileと統合
//                            readdir追加
//  2001.12.18  Phase 7.2     VC向けにFindFirstFileA、FindNextFileAを使った
//                            opendir/readdir/closedir作成(Thanks: えびさわ)
//  2002.03.17  Phase 7.9.0   Phase7.9に合わせて変更 (NAKAUE.T)
//                            textload仕様変更/textsave追加
//                            readdirで.と..を除外
//  2002.04.16  Phase 8.0.0   パス文字列操作をmisc/misc.cppへ移動
//  2002.07.10  Phase 8.1.0   isdir/isexist/isfile追加
//  2008.01.23  Phase 8.2.5   isexist(ルートディレクトリ誤認対策)
//  2008.03.30  Phase 8.2.8   textsave修正(複数のエントリを保存できない)
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "kis/kis_file.h"
#include "libkawari/kawari_engine.h"
#include "libkawari/kawari_rc.h"
#include "misc/misc.h"
#include "misc/l10n.h"
using namespace kawari::resource;
using namespace kawari_log;
//---------------------------------------------------------------------------
#include <string>
#include <vector>
#include <fstream>
#include <sys/types.h>
#include <sys/stat.h>
#if defined(_WIN32) && defined(NOHAVE_READDIR)
#	include "misc/_dirent.h"
#	define S_ISDIR(m) ((m&_S_IFDIR)!=0)
#	define S_ISREG(m) ((m&_S_IFREG)!=0)
#else
#	include <dirent.h>
#endif
using namespace std;
//---------------------------------------------------------------------------
void KIS_save::Run(const vector<string>& args, bool crypt)
{
	if(!AssertArgument(args, 3)) return;

	vector<string> entry;
	entry.insert(entry.end(),args.begin()+2,args.end());

	// saveは相対パスのみ
	string filename;
	if (IsAbsolutePath(CanonicalPath(args[1])))
		filename=PathToFileName(args[1]);
	else
		filename=CanonicalPath(Engine->GetDataPath(), args[1]);

	if(!Engine->SaveKawariDict(filename,entry,crypt)) {
		// エラー
		GetLogger().GetStream(LOG_ERROR) << args[0] << RC.S(ERR_KIS_FILE_SAVE_FAILED) << filename << endl;
	}

	return;
}
//---------------------------------------------------------------------------
string KIS_load::Function(const vector<string>& args)
{
	if(!AssertArgument(args, 2, 2)) return ("");

	string filename=CanonicalPath(Engine->GetDataPath(), args[1]);

	if(!Engine->LoadKawariDict(filename)) {
		// エラー
		GetLogger().GetStream(LOG_ERROR) << args[0] << RC.S(ERR_KIS_FILE_LOAD_FAILED) << filename << endl;
	}

	return("");
}
//---------------------------------------------------------------------------
string KIS_textload::Function(const vector<string>& args)
{
	if(!AssertArgument(args, 3, 3)) return ("");

	string filename=CanonicalPath(Engine->GetDataPath(), args[2]);

	ifstream ifs;
	ifs.open(filename.c_str());
	if(!ifs.is_open()) {
		// エラー
		GetLogger().GetStream(LOG_ERROR) << args[0] << RC.S(ERR_KIS_FILE_LOAD_FAILED) << filename << endl;
		return("");
	}

	TEntry entry=Engine->CreateEntry(args[1]);

	string buff;
	while(getline(ifs,buff)) {
		entry.Push(Engine->CreateStrWord(buff));
	}

	ifs.close();

	return("");
}
//---------------------------------------------------------------------------
string KIS_textsave::Function_(const vector<string>& args,bool flag)
{
	if(!AssertArgument(args, 3)) return ("");

	// saveは相対パスのみ
	string filename;
	if (IsAbsolutePath(CanonicalPath(args[1])))
		filename=PathToFileName(args[1]);
	else
		filename=CanonicalPath(Engine->GetDataPath(), args[1]);

	ofstream ofs;
	if(flag) ofs.open(filename.c_str());
	 else ofs.open(filename.c_str(), ios::app);
	if(!ofs.is_open()) {
		// エラー
		GetLogger().GetStream(LOG_ERROR) << args[0] << RC.S(ERR_KIS_FILE_LOAD_FAILED) << filename << endl;
		return("");
	}

	for(unsigned int i=2;i<args.size();i++) {
		TEntry entry=Engine->CreateEntry(args[i]);
		unsigned int size=entry.Size();

		for(unsigned int l=0;l<size;l++) {
			ofs << Engine->IndexParse(entry,l) << endl;
		}
	}

	ofs.close();

	return("");
}
//---------------------------------------------------------------------------
string KIS_readdir::Function(const vector<string>& args)
{
	if(!AssertArgument(args, 3, 3)) return ("");

	string dirname=CanonicalPath(Engine->GetDataPath(), args[2]);

	DIR *dirhandle;
	struct dirent *direntry;
	if((dirhandle=opendir(dirname.c_str()))==NULL) return("");

	Engine->ClearEntry(args[1]);

	while((direntry=readdir(dirhandle))!=NULL) {
		string entry=direntry->d_name;
		if((entry!=".")&&(entry!="..")) Engine->CreateEntry(args[1]).Push(Engine->CreateStrWord(entry));
	}

	closedir(dirhandle);

	return("");
}
//---------------------------------------------------------------------------
string KIS_cncpath::Function(const vector<string>& args)
{
	if(!AssertArgument(args, 2, 3)) return ("");

	if (args.size()==2){
		return CanonicalPath(args[1]);
	}else{
		return CanonicalPath(args[1], args[2]);
	}
}
//---------------------------------------------------------------------------
string KIS_dirname::Function(const vector<string>& args)
{
	if(!AssertArgument(args, 2, 2)) return ("");

	return PathToBaseDir(CanonicalPath(args[1]));
}
//---------------------------------------------------------------------------
string KIS_filename::Function(const vector<string>& args)
{
	if(!AssertArgument(args, 2, 2)) return ("");

	return PathToFileName(CanonicalPath(args[1]));
}
//---------------------------------------------------------------------------
string KIS_isdir::Function(const vector<string>& args)
{
	if(!AssertArgument(args, 2, 2)) return ("");

	string dirname=CanonicalPath(Engine->GetDataPath(), args[1]);
	struct stat buf;

	if(stat(dirname.c_str(),&buf)) return("");

	if(S_ISDIR(buf.st_mode)) {
		return("1");
	}else{
		return("0");
	}
}
//---------------------------------------------------------------------------
string KIS_isfile::Function(const vector<string>& args)
{
	if(!AssertArgument(args, 2, 2)) return ("");

	string pathname=CanonicalPath(Engine->GetDataPath(), args[1]);
	struct stat buf;

	if(stat(pathname.c_str(),&buf)) return("");

	if(S_ISREG(buf.st_mode)) {
		return("1");
	}else{
		return("0");
	}
}
//---------------------------------------------------------------------------
string KIS_isexist::Function(const vector<string>& args)
{
	if(!AssertArgument(args, 2, 2)) return ("");

	string dirname=PathToBaseDir(CanonicalPath(Engine->GetDataPath(), args[1]));
	string filename=PathToFileName(CanonicalPath(Engine->GetDataPath(), args[1]));

	unsigned int pos=ctow(dirname).rfind(FILE_SEPARATOR);
	if(pos==string::npos) {
		// フルパスなのにdirnameにFILE_SEPARATORがない場合、推定でルート
		// FILE_SEPARATORを追加しカレントディレクトリとの誤認を防ぐ
		dirname+=FILE_SEPARATOR;
	}

	DIR *dirhandle;
	struct dirent *direntry;
	if((dirhandle=opendir(dirname.c_str()))==NULL) return("");

	string str="0";
	while((direntry=readdir(dirhandle))!=NULL) {
		string entry=direntry->d_name;
		if((entry!=".")&&(entry!="..")&&(entry==filename)) {
			str="1";
			break;
		}
	}

	closedir(dirhandle);

	return(str);
}
//---------------------------------------------------------------------------
