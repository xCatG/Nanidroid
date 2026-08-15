#include	"satori.h"
#ifdef POSIX
#  include      "Utilities.h"
#else
#  include	<mbctype.h>	// for _ismbblead,_ismbbtrail
#endif


#include	<fstream>
#include	<cassert>

#ifdef POSIX
#  include <iostream>
#  include <climits>
#endif

#ifndef POSIX
// ファイルの最終更新日時を取得
bool	GetLastWriteTime(LPCSTR iFileName, SYSTEMTIME& oSystemTime) {
	HANDLE	theFile = ::CreateFile( iFileName, 
		GENERIC_READ, FILE_SHARE_READ|FILE_SHARE_WRITE, NULL,
		OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL );
	if ( theFile==INVALID_HANDLE_VALUE )
		return	false;

	BY_HANDLE_FILE_INFORMATION	theInfo;
	::GetFileInformationByHandle(theFile, &theInfo);
	::CloseHandle(theFile);

	FILETIME	FileTime;
	::FileTimeToLocalFileTime(&(theInfo.ftLastWriteTime), &FileTime);
	::FileTimeToSystemTime(&FileTime, &oSystemTime);
	return	true;
}
#endif


//----------------------------------------------------------------------
//	ファイルの更新日時を比較。
//	返値が正ならば前者、負ならば後者のほうが新しいファイル。
//----------------------------------------------------------------------
#ifdef POSIX
#include <sys/types.h>
#include <sys/stat.h>
int CompareTime(const string& file1, const string& file2) {
    // file1の方が新しければ1、同じなら0、古ければ-1。
    struct stat s1, s2;
    int r1 = ::stat(file1.c_str(), &s1);
    int r2 = ::stat(file2.c_str(), &s2);
    if (r1 == 0) {
	if (r2 != 0) {
	    return 1;
	}
    }
    else {
	if (r2 == 0) {
	    return -1;
	}
	else {
	    return 0;
	}
    }
    if (s1.st_mtime > s2.st_mtime) {
	return 1;
    }
    else if (s1.st_mtime < s2.st_mtime) {
	return -1;
    }
    else {
	return 0;
    }
}
#else
int	CompareTime(LPCSTR szL, LPCSTR szR) {
	assert(szL!=NULL && szR!=NULL);

	SYSTEMTIME	stL, stR;
	BOOL		fexistL, fexistR;

	// 更新日付を得る。
	fexistL = GetLastWriteTime(szL, stL);
	fexistR	= GetLastWriteTime(szR, stR);
	// 存在しないファイルは「古い」と見なす。
	if ( fexistL ) {
		if ( !fexistR)
			return	1;
	} else {
		if ( fexistR )
			return	-1;
		else
			return	0;	// どっちもありゃしねぇ
	}

	// 最終更新日付を比較
	if ( stL.wYear > stR.wYear )	return	1;
	else if ( stL.wYear < stR.wYear )	return	-1;
	if ( stL.wMonth > stR.wMonth )	return	1;
	else if ( stL.wMonth < stR.wMonth )	return	-1;
	if ( stL.wDay > stR.wDay )	return	1;
	else if ( stL.wDay < stR.wDay )	return	-1;
	if ( stL.wHour > stR.wHour )	return	1;
	else if ( stL.wHour < stR.wHour )	return	-1;
	if ( stL.wMinute > stR.wMinute )	return	1;
	else if ( stL.wMinute < stR.wMinute )	return	-1;
	if ( stL.wSecond > stR.wSecond )	return	1;
	else if ( stL.wSecond < stR.wSecond )	return	-1;
	if ( stL.wMilliseconds > stR.wMilliseconds )	return	1;
	else if ( stL.wMilliseconds < stR.wMilliseconds )	return	-1;
	// 制作日時の完全一致
	return	0;
}
#endif


string	zen2han(string str) {
	static const char	before[] = "\x82\x4F\x82\x50\x82\x51\x82\x52\x82\x53\x82\x54\x82\x55\x82\x56\x82\x57\x82\x58\x82\x60\x82\x61\x82\x62\x82\x63\x82\x64\x82\x65\x82\x66\x82\x67\x82\x68\x82\x69\x82\x6A\x82\x6B\x82\x6C\x82\x6D\x82\x6E\x82\x6F\x82\x70\x82\x71\x82\x72\x82\x73\x82\x74\x82\x75\x82\x76\x82\x77\x82\x78\x82\x79\x82\x81\x82\x82\x82\x83\x82\x84\x82\x85\x82\x86\x82\x87\x82\x88\x82\x89\x82\x8A\x82\x8B\x82\x8C\x82\x8D\x82\x8E\x82\x8F\x82\x90\x82\x91\x82\x92\x82\x93\x82\x94\x82\x95\x82\x96\x82\x97\x82\x98\x82\x99\x82\x9A\x81\x7C\x81\x7B";
	static const char	after[] = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-+";
	char	buf1[3]="\0\0", buf2[2]="\0";
	for (int n=0 ; n<sizeof(after) ; ++n) {
		buf1[0]=before[n*2];
		buf1[1]=before[n*2+1];
		buf2[0]=after[n];
		replace(str, buf1, buf2);
	}
	return	str;
}

string int2zen(int i) {
	static const char*	ary[] = {"\x82\x4F","\x82\x50","\x82\x51","\x82\x52","\x82\x53","\x82\x54","\x82\x55","\x82\x56","\x82\x57","\x82\x58"};

	string	zen;
	if ( i<0 ) {
		zen += "\x81\x7C";
		i = -i; // INT_MINの時は符号が反転しない
	}
	string	han=itos(i);
	const char* p=han.c_str();
	if ( i==INT_MIN )
		++p;
	for (  ; *p != '\0' ; ++p ) {
		assert(*p>='0' && *p<='9');
		zen += ary[*p-'0'];
	}
	return	zen;
}


string	Satori::GetWord(const string& name) {
	return "\x82\xA2\x82\xCA";
}

string	Satori::surface_restore_string() { 
	string	str="";
	if ( !surface_restore_at_talk )	// そもそも必要なし、の場合
		return	"\\1";

	//for ( set<int>::const_iterator i=surface_changed_before_speak.begin() ; i!=surface_changed_before_speak.end() ; ++i )

	for ( map<int, int>::const_iterator i=default_surface.begin() ; i!=default_surface.end() ; ++i ) {
		if ( mIsMateria ) {
			if ( i->first >= 2 )
				continue;
			else if ( surface_changed_before_speak.find(i->first) == surface_changed_before_speak.end() )
				str += string() + "\\" + itos(i->first) + "\\s[" + itos(i->second) + "]";
		} else
			if ( surface_changed_before_speak.find(i->first) == surface_changed_before_speak.end() )
				str += string() + "\\p[" + itos(i->first) + "]\\s[" + itos(i->second) + "]";
	}

	surface_changed_before_speak.clear();
	return	str;
}


// ある名前により指定される「全ての」URL及び付帯情報、のリスト
bool	Satori::GetURLList(const string& name, string& result)
{
	Family<Talk>* f = talks.get_family(name);
	if ( f == NULL )
		return false;

	vector<const Talk*> tg;
	f->get_elements_pointers(tg);
	for ( vector<const Talk*>::iterator it = tg.begin() ; it != tg.end() ; ++it )
	{
		const Talk& vec = **it;
		if ( vec.size() < 1 )
			continue;
		string	menu = vec[0];
		string	url = (vec.size()<2) ? ("") : (vec[1]);
		string	banner = (vec.size()<3) ? ("") : (vec[2]);
		int	len = menu.size()+1+url.size()+1+banner.size()+1;
		char*	buf=new char[len+1];
		sprintf(buf, "%s%c%s%c%s%c", menu.c_str(), 1, url.c_str(), 1, banner.c_str(), 2);
		result += buf;
		delete [] buf;
	}
	return	true;
}

// ある名前により指定されるURL中の指定サイトのスクリプトを取得
bool	Satori::GetRecommendsiteSentence(const string& name, string& result)
{
	Family<Talk>* f = talks.get_family(name);
	if ( f == NULL )
		return false;

	vector<const Talk*> tg;
	f->get_elements_pointers(tg);
	for ( vector<const Talk*>::iterator it = tg.begin() ; it != tg.end() ; ++it )
	{
		const Talk& t = **it;
		if ( t.size() >= 4 && t[0]==mReferences[0] )
		{
			result = SentenceToSakuraScript( Talk(t.begin()+3, t.end()) );
			return	true;
		}
	}
	return	false;
}

strmap*	Satori::find_ghost_info(string name) {
	vector<strmap>::iterator i=ghosts_info.begin();
	for ( ; i!=ghosts_info.end() ; ++i )
		if ( (*i)["name"] == name )
			return	&(*i);
	return	NULL;
}




// 文章の中で （ を見つけた場合、pが （ の次の位置まで進められた上でこれが実行される。
// pはこの内部で ） の次の位置まで進められる。
// 返値はカッコの解釈結果。
string	Satori::KakkoSection(const char*& p) {
	string	kakko_str;
	while (true) {
		if ( p[0] == '\0' )
			return	string("\x81\x69") + kakko_str;	// 閉じカッコが無かった

		string c = get_a_chr(p);
		if ( c=="\x81\x6A" )
			break;
		else if ( c=="\x81\x69" ) {
			kakko_str += KakkoSection(p);
		}
		else
			kakko_str += c;
	}

	string	result;
	if ( Call(kakko_str, result) )
		return	result;
	if ( unkakko_for_calcurate )
		return	string("\x82\x4F");
	else
		return	string("\x81\x69") + kakko_str + "\x81\x6A";
}

string	Satori::UnKakko(const char* p) {
	assert(p!=NULL);
	string	result;
	while ( p[0] != '\0' ) {
		string c=get_a_chr(p);
		result += (c=="\x81\x69") ? KakkoSection(p) : c;
	}
	return	result;
}

void	Satori::erase_var(const string& key) {
	if ( key == "\x83\x58\x83\x52\x81\x5B\x83\x76\x90\xD8\x82\xE8\x8A\xB7\x82\xA6\x8E\x9E" )
		append_at_scope_change = "";
	else if ( key == "\x82\xB3\x82\xAD\x82\xE7\x83\x58\x83\x4E\x83\x8A\x83\x76\x83\x67\x82\xC9\x82\xE6\x82\xE9\x83\x58\x83\x52\x81\x5B\x83\x76\x90\xD8\x82\xE8\x8A\xB7\x82\xA6\x8E\x9E" )
		append_at_scope_change_with_sakura_script = "";
	variables.erase(key);
}

bool	Satori::system_variable_operation(string key, string value, string* result)
{
	// mapにしようよ。

	if ( key == "\x92\x9D\x82\xE8\x8A\xD4\x8A\x75" ) {
		talk_interval = stoi( zen2han(value) );
		if ( talk_interval<3 ) talk_interval=0; // 3未満は喋らない

		// 喋りカウント初期化
		int	dist = int(talk_interval*(talk_interval_random/100.0));
		talk_interval_count = ( dist==0 ) ? talk_interval : 
			(talk_interval-dist)+(random()%(dist*2));
	}
	else if ( key == "\x92\x9D\x82\xE8\x8A\xD4\x8A\x75\x8C\xEB\x8D\xB7" ) {
		talk_interval_random = stoi( zen2han(value) );
		if ( talk_interval_random>100 ) talk_interval_random=100;
		if ( talk_interval_random<0 ) talk_interval_random=0;

		// 喋りカウント初期化
		int	dist = int(talk_interval*(talk_interval_random/100.0));
		talk_interval_count = ( dist==0 ) ? talk_interval : 
			(talk_interval-dist)+(random()%(dist*2));
	}
	else if ( key == "\x83\x58\x83\x52\x81\x5B\x83\x76\x90\xD8\x82\xE8\x8A\xB7\x82\xA6\x8E\x9E" ) {
		append_at_scope_change = zen2han(value);
	}
	else if ( key == "\x82\xB3\x82\xAD\x82\xE7\x83\x58\x83\x4E\x83\x8A\x83\x76\x83\x67\x82\xC9\x82\xE6\x82\xE9\x83\x58\x83\x52\x81\x5B\x83\x76\x90\xD8\x82\xE8\x8A\xB7\x82\xA6\x8E\x9E" ) {
		append_at_scope_change_with_sakura_script = zen2han(value);
	}
	else if ( key == "\x83\x67\x81\x5B\x83\x4E\x8A\x4A\x8E\x6E\x8E\x9E" ) {
		append_at_talk_start = zen2han(value);
	}
	else if ( key == "\x83\x67\x81\x5B\x83\x4E\x8F\x49\x97\xB9\x8E\x9E" ) {
		append_at_talk_end = zen2han(value);
	}
	else if ( key == "\x89\xEF\x98\x62\x8E\x9E\x83\x54\x81\x5B\x83\x74\x83\x46\x83\x58\x96\xDF\x82\xB5" ) {
		surface_restore_at_talk=(value=="\x97\x4C\x8C\xF8");
	}
	else if ( compare_head(key,  "\x83\x54\x81\x5B\x83\x74\x83\x46\x83\x58\x89\xC1\x8E\x5A\x92\x6C") && aredigits(key.c_str() + strlen("\x83\x54\x81\x5B\x83\x74\x83\x46\x83\x58\x89\xC1\x8E\x5A\x92\x6C")) ) {
		int n = atoi(key.c_str() + strlen("\x83\x54\x81\x5B\x83\x74\x83\x46\x83\x58\x89\xC1\x8E\x5A\x92\x6C"));
		surface_add_value[n]=stoi( zen2han(value) );

		variables[string()+"\x83\x66\x83\x74\x83\x48\x83\x8B\x83\x67\x83\x54\x81\x5B\x83\x74\x83\x46\x83\x58"+itos(n)] = value;
		next_default_surface[n]=stoi( zen2han(value) );
		if ( !is_speaked_anybody() )
			default_surface[n]=next_default_surface[n];
	}
	else if ( compare_head(key,  "\x83\x66\x83\x74\x83\x48\x83\x8B\x83\x67\x83\x54\x81\x5B\x83\x74\x83\x46\x83\x58") && aredigits(key.c_str() + strlen("\x83\x66\x83\x74\x83\x48\x83\x8B\x83\x67\x83\x54\x81\x5B\x83\x74\x83\x46\x83\x58")) ) {
		int n = atoi(key.c_str() + strlen("\x83\x66\x83\x74\x83\x48\x83\x8B\x83\x67\x83\x54\x81\x5B\x83\x74\x83\x46\x83\x58"));
		next_default_surface[n]=stoi( zen2han(value) );
		if ( !is_speaked_anybody() )
			default_surface[n]=next_default_surface[n];
	}
	else if ( compare_head(key,  "BalloonOffset") && aredigits(key.c_str() + strlen("BalloonOffset")) ) {
		int n = atoi(key.c_str() + strlen("BalloonOffset"));
		BalloonOffset[n] = value;
		validBalloonOffset[n] = true;
	}
	else if ( key == "\x83\x67\x81\x5B\x83\x4E\x92\x86\x82\xCC\x82\xC8\x82\xC5\x82\xE7\x82\xEA\x94\xBD\x89\x9E") {
		insert_nade_talk_at_other_talk= (value=="\x97\x4C\x8C\xF8");
	}
	else if ( key == "\x82\xC8\x82\xC5\x82\xE7\x82\xEA\x8E\x9D\x91\xB1\x95\x62\x90\x94") {
		nade_valid_time_initializer = stoi( zen2han(value) );
	}
	else if ( key == "\x82\xC8\x82\xC5\x82\xE7\x82\xEA\x94\xBD\x89\x9E\x89\xF1\x90\x94") {
		nade_sensitivity = stoi( zen2han(value) );
	}
	else if ( key == "Log" ) {
		Sender::validate(value=="\x97\x4C\x8C\xF8");
	}
	else if ( key == "RequestLog" ) {
		fRequestLog = (value=="\x97\x4C\x8C\xF8");
	}
	else if ( key == "OperationLog" ) {
		fOperationLog = (value=="\x97\x4C\x8C\xF8");
	}
	else if ( key == "ResponseLog" ) {
		fResponseLog = (value=="\x97\x4C\x8C\xF8");
	}
	else if ( key == "\x8E\xA9\x93\xAE\x91\x7D\x93\xFC\x83\x45\x83\x46\x83\x43\x83\x67\x82\xCC\x94\x7B\x97\xA6" ) {
		rate_of_auto_insert_wait=stoi( zen2han(value) );
		rate_of_auto_insert_wait = min(1000, max(0, rate_of_auto_insert_wait));
		variables["\x8E\xA9\x93\xAE\x91\x7D\x93\xFC\x83\x45\x83\x46\x83\x43\x83\x67\x82\xCC\x94\x7B\x97\xA6"] = itos(rate_of_auto_insert_wait);
	}
	else if ( key == "\x8E\xAB\x8F\x91\x83\x74\x83\x48\x83\x8B\x83\x5F" ) {
		strvec	words;
		split(value, ",",dic_folder);
		reload_flag=true;
	}
	else if ( key == "\x83\x5A\x81\x5B\x83\x75\x83\x66\x81\x5B\x83\x5E\x88\xC3\x8D\x86\x89\xBB" ) {
		fEncodeSavedata = (value=="\x97\x4C\x8C\xF8");
	}
	else if ( compare_head(key,"\x92\x50\x8C\xEA\x8C\x51\x81\x75") && compare_tail(key,"\x81\x76\x82\xCC\x8F\x64\x95\xA1\x89\xF1\x94\xF0") ) {
		variables.erase(key);
		words.setOC( string(key.c_str()+8, key.length()-8-12), value );
	}
	else if ( compare_head(key,"\x95\xB6\x81\x75") && compare_tail(key,"\x81\x76\x82\xCC\x8F\x64\x95\xA1\x89\xF1\x94\xF0") ) {
		variables.erase(key);
		talks.setOC( string(key.c_str()+4, key.length()-4-12), value );
	}
	else if ( key == "\x8E\x9F\x82\xCC\x83\x67\x81\x5B\x83\x4E" ) {
		variables.erase(key);
		int	count=1;
		while ( reserved_talk.find(count) != reserved_talk.end() )
			++count;
		reserved_talk[count] = value;
		sender << "\x8E\x9F\x89\xF1\x82\xCC\x83\x89\x83\x93\x83\x5F\x83\x80\x83\x67\x81\x5B\x83\x4E\x82\xAA\x81\x75" << value << "\x81\x76\x82\xC9\x97\x5C\x96\xf1\x82\xB3\x82\xEA\x82\xDC\x82\xB5\x82\xBD\x81\x42" << endl;
	}
	else if ( compare_head(key,"\x8E\x9F\x82\xA9\x82\xE7") && compare_tail(key,"\x89\xF1\x96\xDA\x82\xCC\x83\x67\x81\x5B\x83\x4E") ) {
		variables.erase(key);
		int	count = stoi( zen2han( string(key.c_str()+6, key.length()-6-12) ) );
		if ( count<=0 ) {
			sender << "\x83\x67\x81\x5B\x83\x4E\x97\x5C\x96\xf1\x81\x41\x90\xDD\x92\xE8\x92\x6C\x82\xAA\x83\x77\x83\x93\x82\xC5\x82\xB7\x81\x42" << endl;
		}
		else {
			while ( reserved_talk.find(count) != reserved_talk.end() )
				++count;
			reserved_talk[count] = value;
			sender << count << "\x89\xF1\x8C\xE3\x82\xCC\x83\x89\x83\x93\x83\x5F\x83\x80\x83\x67\x81\x5B\x83\x4E\x82\xAA\x81\x75" << value << "\x81\x76\x82\xC9\x97\x5C\x96\xf1\x82\xB3\x82\xEA\x82\xDC\x82\xB5\x82\xBD\x81\x42" << endl;
		}
	}
	else if ( key=="\x83\x67\x81\x5B\x83\x4E\x97\x5C\x96\xf1\x82\xCC\x83\x4C\x83\x83\x83\x93\x83\x5A\x83\x8B" ) {
		if ( value=="\x81\x96" )
			reserved_talk.clear();
		else
			for (map<int, string>::iterator it=reserved_talk.begin(); it!=reserved_talk.end() ; )
				if ( value == it->second )
					reserved_talk.erase(it++);
				else
					++it;
	}
	else if ( key == "SAORI\x88\xF8\x90\x94\x82\xCC\x8C\x76\x8E\x5A" ) {
		if (value=="\x97\x4C\x8C\xF8")
			mSaoriArgumentCalcMode = SACM_ON;
		else if (value=="\x96\xB3\x8C\xF8")
			mSaoriArgumentCalcMode = SACM_OFF;
		else
			mSaoriArgumentCalcMode = SACM_AUTO;
	}
	else if ( key == "\x8E\xAB\x8F\x91\x83\x8A\x83\x8D\x81\x5B\x83\x68" && value=="\x8E\xC0\x8D\x73") {
		variables.erase(key);
		reload_flag=true;
	}
	else if ( key == "\x8E\xE8\x93\xAE\x83\x5A\x81\x5B\x83\x75" && value=="\x8E\xC0\x8D\x73") {
		variables.erase(key);
		this->Save();
	}
	else if ( key == "\x8E\xA9\x93\xAE\x83\x5A\x81\x5B\x83\x75\x8A\xD4\x8A\x75" ) {
		mAutoSaveInterval = stoi(zen2han(value));
		mAutoSaveCurrentCount = mAutoSaveInterval;
		if ( mAutoSaveInterval > 0 )
			sender << ""  << itos(mAutoSaveInterval) << "\x95\x62\x8A\xD4\x8A\x75\x82\xC5\x8E\xA9\x93\xAE\x83\x5A\x81\x5B\x83\x75\x82\xF0\x8D\x73\x82\xA2\x82\xDC\x82\xB7\x81\x42" << endl;
		else
			sender << "\x8E\xA9\x93\xAE\x83\x5A\x81\x5B\x83\x75\x82\xCD\x8D\x73\x82\xA2\x82\xDC\x82\xB9\x82\xF1\x81\x42" << endl;
	}
	else if ( key == "\x91\x53\x83\x5E\x83\x43\x83\x7D\x89\xF0\x8F\x9C" && value=="\x8E\xC0\x8D\x73") {
		variables.erase(key);
		for (strintmap::iterator i=timer.begin();i!=timer.end();++i)
			variables.erase(i->first + "\x83\x5E\x83\x43\x83\x7D");
		timer.clear();
	}
	else if ( key == "\x8B\xB3\x82\xED\x82\xE9\x82\xB1\x82\xC6" ) {
		variables.erase(key);
		teach_genre=value;
		if ( result != NULL )
			*result += "\\![open,teachbox]";
	}
	else if ( key.size()>6 && compare_tail(key, "\x83\x5E\x83\x43\x83\x7D") ) {
		string	name(key.c_str(), strlen(key.c_str())-6);
		/*if ( sentences.find(name) == sentences.end() ) {
			result = string("※　タイマ終了時のジャンプ先 ＊")+name+" がありません　※";
			// セーブデータ復帰時を考慮
		}
		else */{
			int sec = stoi(zen2han(value));
			if ( sec < 1 ) {
				variables.erase(key);
				if ( timer.find(name)!=timer.end() ) {
					timer.erase(name);
					sender << "\x83\x5E\x83\x43\x83\x7D\x81\x75"  << name << "\x81\x76\x82\xCC\x97\x5C\x96\xf1\x82\xAA\x83\x4C\x83\x83\x83\x93\x83\x5A\x83\x8B\x82\xB3\x82\xEA\x82\xDC\x82\xB5\x82\xBD\x81\x42" << endl;
				} else
					sender << "\x83\x5E\x83\x43\x83\x7D\x81\x75"  << name << "\x81\x76\x82\xCD\x8C\xB3\x82\xA9\x82\xE7\x97\x5C\x96\xf1\x82\xB3\x82\xEA\x82\xC4\x82\xA2\x82\xDC\x82\xB9\x82\xF1\x81\x42" << endl;
			} else {
				timer[name] = sec;
				sender << "\x83\x5E\x83\x43\x83\x7D\x81\x75"  << name << "\x81\x76\x82\xAA" << sec << "\x95\x62\x8C\xE3\x82\xC9\x97\x5C\x96\xf1\x82\xB3\x82\xEA\x82\xDC\x82\xB5\x82\xBD\x81\x42" << endl;
			}
		}
	}
	else if ( key == "\x88\xF8\x90\x94\x8B\xE6\x90\xD8\x82\xE8\x92\xC7\x89\xC1" && value.size()>0 ) {
		variables.erase(key);
		mDelimiters.insert(value);
	}
	else if ( key == "\x88\xF8\x90\x94\x8B\xE6\x90\xD8\x82\xE8\x8D\xED\x8F\x9C" && value.size()>0 ) {
		variables.erase(key);
		mDelimiters.erase(value);
	}
	else if ( compare_head(key, "Value") && aredigits(key.substr(5)) )
	{
		variables.erase(key);
		mResponseMap[string()+"Reference"+key.substr(5)] = value;
	}
	else
		return	false;

	return	true;
}


bool	Satori::calculate(const string& iExpression, string& oResult) {

	bool	tmp = unkakko_for_calcurate;
	unkakko_for_calcurate = true;
	oResult = UnKakko(iExpression.c_str());
	unkakko_for_calcurate = tmp;

	bool r = calc(oResult);
	if ( !r ) {
#ifdef POSIX
	        std::cerr <<
		    "error on Satori::calculate" << std::endl <<
		    "Error in expression: " << iExpression << std::endl;
#else
		// もうちょっと抽象化を……
		::MessageBox(NULL, (string() + "\x8E\xAE\x82\xAA\x8C\x76\x8E\x5A\x95\x73\x94\x5C\x82\xC5\x82\xB7\x81\x42\n" + iExpression).c_str(), "error on Satori::calculate" , MB_OK);
#endif
	}
	return	r;
}

