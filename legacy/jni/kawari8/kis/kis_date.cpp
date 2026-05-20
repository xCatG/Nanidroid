//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// KawariInlineScript -- 日時 --
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.05.14  Phase 0.50    First version
//  2001.05.19  Phase 5.1     少し強化
//  2001.06.17  Phase 6.0     もう少し強化
//  2001.08.08  Phase 6.2     ostrstreamバグ修正
//  2002.07.10  Phase 8.1.0   %s追加
//  2003.11.17  Phase 8.2.0   epocからの経過秒数をフォーマット出力可能に
//  2005.06.21  Phase 8.2.3   gcc3.xのwarning対応 (suikyo)
//                            mktime追加 (さとー)
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "kis/kis_date.h"
//---------------------------------------------------------------------------
#include "misc/misc.h"
//---------------------------------------------------------------------------
#include <string>
#include <vector>
#include <ctime>
#ifdef HAVE_SSTREAM_H
#include <sstream>
#else
#include <strstream>
#endif
#include <iomanip>
using namespace std;
//---------------------------------------------------------------------------
string KIS_date::Function(const vector<string>& args)
{
	time_t nowtt;
	if(args.size()>=3) {
		nowtt=(time_t)atoi(args[2].c_str());
	} else {
		nowtt=time(NULL);
	}
	struct tm *now=localtime(&nowtt);

	string format="%y/%m/%d %H:%M:%S";
	if(args.size()>=2) format=args[1];

#ifdef HAVE_SSTREAM_H
	ostringstream ret;
#else
	ostrstream ret;
#endif
	format+=' ';	// ダミー
	for(unsigned int i=0;i<(format.size()-1);i++) {
		if(format[i]=='%') {
			i++;
			switch(format[i]) {
			case 'd':	// 日 (01..31)
				ret << setw(2) << setfill('0') << now->tm_mday;
				break;
			case 'e':	// 日 (1..31)
				ret << now->tm_mday;
				break;
			case 'H':	// 時 (00..23)
				ret << setw(2) << setfill('0') << now->tm_hour;
				break;
			case 'j':	// 元日からの通算日 (001..366)
				ret << setw(3) << setfill('0') << (now->tm_yday+1);
				break;
			case 'J':	// 元日からの通算日 (1..366)
				ret << (now->tm_yday+1);
				break;
			case 'k':	// 時 (0..23)
				ret << now->tm_hour;
				break;
			case 'm':	// 月 (01..12)
				ret << setw(2) << setfill('0') << (now->tm_mon+1);
				break;
			case 'M':	// 分 (00..59)
				ret << setw(2) << setfill('0') << now->tm_min;
				break;
			case 'n':	// 月 (1..12)
				ret << (now->tm_mon+1);
				break;
			case 'N':	// 分 (0..59)
				ret << now->tm_min;
				break;
			case 'r':	// 秒 (0..59)
				ret << now->tm_sec;
				break;
			case 'S':	// 秒 (00..59)
				ret << setw(2) << setfill('0') << now->tm_sec;
				break;
			case 's':	// 1970/1/1 00:00:00 UTCからの秒数
				ret << nowtt;
				break;
			case 'w':	// 曜日 (0..6) 日曜日が0
				ret << now->tm_wday;
				break;
			case 'y':	// 年 (2000...) 4桁
			case 'Y':	// 年 (2000...) 4桁
				ret << (now->tm_year+1900);
				break;
			case '%':
				ret << '%';
				break;
			default:
				ret << '%';
				i--;
			}
		} else {
			ret << format[i];
		}
	}

#ifdef HAVE_SSTREAM_H
	return string(ret.str());
#else
	ret << ends;
	string retstr(ret.str());
	ret.freeze(false);
	return(retstr);
#endif
}
//---------------------------------------------------------------------------
string KIS_mktime::Function(const vector<string>& args)
{
	if(!AssertArgument(args, 7, 7)) return("");

	struct tm tt;
	tt.tm_year =atoi(args[1].c_str())-1900;
	tt.tm_mon  =atoi(args[2].c_str())-1;
	tt.tm_mday =atoi(args[3].c_str());
	tt.tm_hour =atoi(args[4].c_str());
	tt.tm_min  =atoi(args[5].c_str());
	tt.tm_sec  =atoi(args[6].c_str());
	tt.tm_isdst=0;

	// 最低限の数値の正当性を保証する
	if(tt.tm_year<0)                    tt.tm_year=0;
	if((tt.tm_mon>11)||(tt.tm_mon<0))   tt.tm_mon=0;
	if((tt.tm_mday>31)||(tt.tm_mday<1)) tt.tm_mday=1;
	if((tt.tm_hour>23)||(tt.tm_hour<0)) tt.tm_hour=0;
	if((tt.tm_min>59)||(tt.tm_min<0))   tt.tm_min=0;
	if((tt.tm_sec>59)||(tt.tm_sec<0))   tt.tm_sec=0;

	return(IntToString((int)mktime(&tt)));

}
//---------------------------------------------------------------------------
