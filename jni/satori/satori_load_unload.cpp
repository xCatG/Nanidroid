#ifdef _MSC_VER 

	// マルチモニタ関連
	//#define WINVER 0x0500
	#include	<windows.h>
	#include	<multimon.h>
	#define SM_CXVIRTUALSCREEN      78
	#define SM_CYVIRTUALSCREEN      79

#endif	// _MSC_VER


#include	"satori.h"

#include	<fstream>
#include	<cassert>
#include	<ctime>	// for randomize

#include	"../_/Utilities.h"
#include	"../_/stltool.h"

#ifdef POSIX
#  include      "posix_utils.h"
#endif


//---------------------------------------------------------------------------
#ifndef POSIX
BOOL CALLBACK MonitorEnumFunc(HMONITOR hMonitor,HDC hdc,LPRECT rect,LPARAM lParam) {

    MONITORINFOEX MonitorInfoEx;
    MonitorInfoEx.cbSize=sizeof(MonitorInfoEx);

	BOOL (WINAPI* pGetMonitorInfo)(HMONITOR,LPMONITORINFO);
	(FARPROC&)pGetMonitorInfo = ::GetProcAddress(::LoadLibrary("user32.dll"), "GetMonitorInfoA");
	if ( pGetMonitorInfo==NULL )
		return	FALSE;
	if ( !(*pGetMonitorInfo)(hMonitor,&MonitorInfoEx) ) {
		sender << "'GetMonitorInfo' was failed." << endl;
        return FALSE;
    }

	sender << "\x83\x82\x83\x6A\x83\x5E: " << MonitorInfoEx.szDevice << " / (" << 
		rect->left << "," << rect->top << "," << rect->right << "," << rect->bottom << ") / " <<
		((MonitorInfoEx.dwFlags==MONITORINFOF_PRIMARY) ? "primary" : "extra") << endl;

	RECT&	max_screen_rect = *((RECT*)lParam);
	if ( rect->left < max_screen_rect.left )
		max_screen_rect.left = rect->left;
	if ( rect->top < max_screen_rect.top )
		max_screen_rect.top = rect->top;
	if ( rect->right > max_screen_rect.right )
		max_screen_rect.right = rect->right;
	if ( rect->bottom > max_screen_rect.bottom )
		max_screen_rect.bottom = rect->bottom;

    return TRUE;
}
#endif

//---------------------------------------------------------------------------

#ifdef	_DEBUG
	//memory leakの検出(下の3行をこの順番で記述してください。)
	#define _CRTDBG_MAP_ALLOC
	#include <stdlib.h>
	#include <crtdbg.h>
#endif // _DEBUG

bool	Satori::load(const string& iBaseFolder)
{
	Sender::initialize();

#ifdef	_DEBUG
	int tmpDbgFlag;
	tmpDbgFlag = _CrtSetDbgFlag(_CRTDBG_REPORT_FLAG);
	tmpDbgFlag |= _CRTDBG_ALLOC_MEM_DF;
	tmpDbgFlag |= _CRTDBG_LEAK_CHECK_DF;
	_CrtSetDbgFlag(tmpDbgFlag);
#endif // _DEBUG


	mBaseFolder = iBaseFolder;
	sender << "\x81\xA1SATORI::Load on " << mBaseFolder << "" << endl;

#if POSIX
	// 「/」で終わっていなければ付ける。
	if (mBaseFolder[mBaseFolder.size() - 1] != '/') {
	    mBaseFolder += '/';
	}
#endif


#ifdef	_MSC_VER
	// 本体のあるフォルダをサーチ
	{
		TCHAR	buf[MAX_PATH+1];
		::GetModuleFileName(NULL, buf, MAX_PATH);
		char*	p = FindFinalChar(buf, DIR_CHAR);
		if ( p==NULL )
			mExeFolder = "";
		else {
			*(++p) = '\0';
			mExeFolder = buf;
		}
	}
	sender << "\x96\x7B\x91\xCC\x82\xCC\x8F\x8A\x8D\xDD: " << mExeFolder << "" << endl;
#endif // _MSC_VER

	// メンバ初期化
	InitMembers();

#ifdef	_MSC_VER
	// システムの設定を読んでおく
    OSVERSIONINFO	ovi;
    ovi.dwOSVersionInfoSize = sizeof(OSVERSIONINFO);
	::GetVersionEx(&ovi);
	string	os;
	if ( ovi.dwPlatformId == VER_PLATFORM_WIN32_WINDOWS ) {
		if ( ovi.dwMinorVersion == 0 ) { mOSType=WIN95; os="Windows 95"; }
		else if ( ovi.dwMinorVersion == 10 ) { mOSType=WIN98; os="Windows 98"; }
		else if ( ovi.dwMinorVersion == 90 ) { mOSType=WINME; os="Windows Me"; }
		else { mOSType = UNDEFINED; os="undefined"; }
	} else {
		if ( ovi.dwMinorVersion == 0 ) {
			if ( ovi.dwMajorVersion == 4 ) { mOSType=WINNT; os="Windows NT"; }
			else if ( ovi.dwMajorVersion == 5 ) { mOSType=WIN2K; os="Windows 2000"; }
		}
		else { mOSType = WINXP; os="Windows XP or later"; }
	}
	sender << "\x82\x6E\x82\x72\x8E\xED\x95\xCA: " << os << endl;
	if ( mOSType==WIN95 ) {
		is_single_monitor = true;
	} else {
		BOOL (WINAPI* pEnumDisplayMonitors)(HDC,LPRECT,MONITORENUMPROC,LPARAM);
		(FARPROC&)pEnumDisplayMonitors = ::GetProcAddress(::LoadLibrary("user32.dll"), "EnumDisplayMonitors");
		if ( pEnumDisplayMonitors==NULL ) {
			is_single_monitor = true;
		}
		else {
			(*pEnumDisplayMonitors)(NULL,NULL,(MONITORENUMPROC)MonitorEnumFunc,(LPARAM)(&max_screen_rect));
			::GetWindowRect(::GetDesktopWindow(), &desktop_rect);
			RECT*	rect;
			rect = &desktop_rect;
			sender << "\x83\x76\x83\x89\x83\x43\x83\x7D\x83\x8A\x83\x66\x83\x58\x83\x4E\x83\x67\x83\x62\x83\x76: (" << 
				rect->left << "," << rect->top << "," << rect->right << "," << rect->bottom << ")" << endl;
			rect = &max_screen_rect;
			sender << "\x89\xBC\x91\x7A\x83\x66\x83\x58\x83\x4E\x83\x67\x83\x62\x83\x76: (" << 
				rect->left << "," << rect->top << "," << rect->right << "," << rect->bottom << ")" << endl;
			is_single_monitor = ( ::EqualRect(&max_screen_rect, &desktop_rect)!=FALSE );
			sender << (is_single_monitor ? 
				"\x83\x82\x83\x6A\x83\x5E\x82\xCD\x88\xEA\x82\xC2\x82\xBE\x82\xAF\x82\xC6\x94\xBB\x92\x66\x81\x41\x8C\xA9\x90\xD8\x82\xEA\x94\xBB\x92\xE8\x82\xF0\x8C\xC4\x82\xD1\x8F\x6F\x82\xB5\x8C\xB3\x82\xC9\x94\x43\x82\xB9\x82\xDC\x82\xB7\x81\x42" : 
				"\x95\xA1\x90\x94\x82\xCC\x83\x82\x83\x6A\x83\x5E\x82\xAA\x90\xDA\x91\xB1\x82\xB3\x82\xEA\x82\xC4\x82\xA2\x82\xE9\x82\xC6\x94\xBB\x92\x66\x81\x41\x8C\xA9\x90\xD8\x82\xEA\x94\xBB\x92\xE8\x82\xCD\x97\xA2\x81\x58\x82\xAA\x8D\x73\x82\xA2\x82\xDC\x82\xB7\x81\x42") << endl;
		}
	}
#endif // _MSC_VER

	// 置換辞書読み取り
	strmap_from_file(replace_before_dic, mBaseFolder+"replace.txt", "\t");
	strmap_from_file(replace_after_dic, mBaseFolder+"replace_after.txt", "\t");

	// キャラデータ読み込み
	try {
	mCharacters.load(mBaseFolder + "characters.ini");
	for ( inimap::const_iterator i=mCharacters.begin() ; i!=mCharacters.end() ; ++i ) {
		const strmap& m = i->second;
		strmap::const_iterator j;

		// 置換辞書に追加
		j = m.find("popular-name");
		if ( j != m.end() && j->second.size()>0 ) 
			replace_before_dic[j->second + "\x81\x46"] = string() + "\\p[" + i->first + "]";
		j = m.find("initial-letter");
		if ( j != m.end() && j->second.size()>0 ) 
			replace_before_dic[j->second + "\x81\x46"] = string() + "\\p[" + i->first + "]";

		j = m.find("base-surface");
		if ( j != m.end() && j->second.size()>0 )
			system_variable_operation( string("\x83\x54\x81\x5B\x83\x74\x83\x46\x83\x58\x89\xC1\x8E\x5A\x92\x6C") + i->first, j->second);
	}
	}
	catch(...){

	}
	//for ( strmap::const_iterator j=replace_before_dic.begin() ; j!=replace_before_dic.end() ; ++j )
	//	cout << j->first << ": " << j->second << endl;

	// ランダマイズ
	randomize(time(NULL));


	//------------------------------------------

	// コンフィグ読み込み
	LoadDictionary(mBaseFolder + "satori_conf.txt");

	// 変数初期化実行
	GetSentence("\x8F\x89\x8A\xFA\x89\xBB");	

	// SAORI読み込み
	Family<Word>* f = words.get_family("SAORI");
	if ( f != NULL )
	{
		list<const Word*> els;
		f->get_elements_pointers(els);

		mShioriPlugins.load(mBaseFolder);
		for ( list<const Word*>::const_iterator i=els.begin(); i!=els.end() ; ++i)
		{
			if ( (*i)->size()>0 && !mShioriPlugins.load_a_plugin(**i) )
			{
				sender << "SAORI\x93\xC7\x82\xDD\x8D\x9E\x82\xDD\x92\x86\x82\xC9\x83\x47\x83\x89\x81\x5B\x82\xAA\x94\xAD\x90\xB6: " << **i << endl;
			}
		}
	}
	talks.clear();
	words.clear();

	//------------------------------------------

	// セーブデータ読み込み
	LoadDictionary(mBaseFolder + "satori_savedata.txt");

	GetSentence("\x83\x5A\x81\x5B\x83\x75\x83\x66\x81\x5B\x83\x5E");
	talks.clear();
	
	reload_flag = false;

	tick_count_total = stoi(variables["\x83\x53\x81\x5B\x83\x58\x83\x67\x8B\x4E\x93\xAE\x8E\x9E\x8A\xD4\x97\xDD\x8C\x76(ms)"]);
	variables["\x8B\x4E\x93\xAE\x89\xF1\x90\x94"] = itos( stoi(variables["\x8B\x4E\x93\xAE\x89\xF1\x90\x94"])+1 );

	// 「単語の追加」で登録された単語を覚えておく
	const map< string, Family<Word> >& m = words.compatible();
	for ( map< string, Family<Word> >::const_iterator it = m.begin() ; it != m.end() ; ++it )
	{
		vector<const Word*> v;
		it->second.get_elements_pointers(v);
		mAppendedWords[it->first] = v;
	}

	//------------------------------------------

	// 指定フォルダの辞書を読み込み
	strvec::iterator i = dic_folder.begin();
	if ( i==dic_folder.end() ) {
		LoadDicFolder(mBaseFolder);	// ルートフォルダの辞書
	} else {
		for ( ; i!=dic_folder.end() ; ++i )
			LoadDicFolder(mBaseFolder + *i + DIR_CHAR);	// サブフォルダの辞書
	}

	//------------------------------------------

	secure_flag = true;

	system_variable_operation("\x92\x50\x8C\xEA\x8C\x51\x81\x75\x81\x96\x81\x76\x82\xCC\x8F\x64\x95\xA1\x89\xF1\x94\xF0", "\x97\x4C\x8C\xF8\x81\x41\x83\x67\x81\x5B\x83\x4E\x92\x86");
	system_variable_operation("\x83\x67\x81\x5B\x83\x4E\x81\x75\x81\x96\x81\x76\x82\xCC\x8F\x64\x95\xA1\x89\xF1\x94\xF0", "\x97\x4C\x8C\xF8");
	//system_variable_operation("単語群「季節の食べ物」の重複回避", "有効、トーク中");

	GetSentence("OnSatoriLoad");
	on_loaded_script = GetSentence("OnSatoriBoot");
	diet_script(on_loaded_script);

	sender << "loaded." << endl;
	return	true;
}


//---------------------------------------------------------------------------
#define	ENCODE(x)	(fEncodeSavedata ? encode(encode(x)) : (x))

#ifdef POSIX
#  include <time.h>
#endif
bool	Satori::Save(bool isOnUnload) {

	// メンバ変数を里々変数化
	for (map<int, string>::iterator it=reserved_talk.begin(); it!=reserved_talk.end() ; ++it)
		variables[string("\x8E\x9F\x82\xA9\x82\xE7")+itos(it->first)+"\x89\xF1\x96\xDA\x82\xCC\x83\x67\x81\x5B\x83\x4E"] = it->second;
	// 起動時間累計を設定
#ifdef POSIX
	variables["\x83\x53\x81\x5B\x83\x58\x83\x67\x8B\x4E\x93\xAE\x8E\x9E\x8A\xD4\x97\xDD\x8C\x76(ms)"] =
	    itos(posix_get_current_millis() - tick_count_at_load + tick_count_total);
#else
	variables["\x83\x53\x81\x5B\x83\x58\x83\x67\x8B\x4E\x93\xAE\x8E\x9E\x8A\xD4\x97\xDD\x8C\x76(ms)"] = itos( ::GetTickCount() - tick_count_at_load + tick_count_total );
#endif

	if ( isOnUnload ) {
		secure_flag = true;
		(void)GetSentence("OnSatoriUnload");
	}

	string	theFullPath = mBaseFolder + "satori_savedata." + (fEncodeSavedata?"sat":"txt");
	ofstream	out(theFullPath.c_str());
	bool	temp = Sender::is_validated();
	Sender::validate();
	sender << "saving " << theFullPath << "... " ;
	Sender::validate(temp);
	if ( !out.is_open() )
	{
		sender << "failed." << endl;
		return	false;
	}
	string	line = "\x81\x96\x83\x5A\x81\x5B\x83\x75\x83\x66\x81\x5B\x83\x5E";
	out << ENCODE(line) << endl;
	for (strmap::const_iterator it=variables.begin() ; it!=variables.end() ; ++it) {
		string	zen2han(string str);
		string	str = zen2han(it->first);
		if ( str[0]=='S' && aredigits(str.c_str()+1) )
			continue;
		string	line = string("\x81\x90")+it->first+"\t"+it->second; // 変数を保存
		out << ENCODE(line) << endl;
	}

	for ( map<string, vector<const Word*> >::const_iterator i=mAppendedWords.begin() ; i!=mAppendedWords.end() ; ++i )
	{
		out << endl << ENCODE( string("\x81\x97") + i->first ) << endl;
		for ( vector<const Word*>::const_iterator j=i->second.begin() ; j!=i->second.end() ; ++j )
		{
			out << ENCODE(**j) << endl;
		}
	}

	sender << "ok." << endl;
	return	true;
}

//---------------------------------------------------------------------------
bool	Satori::unload() {

	// ファイルに保存
	this->Save(true);

	// プラグイン解放
	mShioriPlugins.unload();

	sender << "\x81\xA1SATORI::Unload ---------------------" << endl;
	return	true;
}

