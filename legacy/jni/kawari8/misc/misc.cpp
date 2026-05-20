//---------------------------------------------------------------------------
//
// 雑用
//
//      Programed by NAKAUE.T (Meister)
//
//  2005.05.28 isdigit() problem fixed.
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "misc/misc.h"
#include "misc/l10n.h"
//---------------------------------------------------------------------------
#include <string>
#include <cstdlib>
#include <iostream>
using namespace std;
//---------------------------------------------------------------------------
TMTRandomGenerator MTRandomGenerator;
//---------------------------------------------------------------------------
// isdigit() problem wa
static inline bool _isdigit(char ch){
	return (('0'<=ch) && (ch<='9'));
}
//---------------------------------------------------------------------------
// 整数値判定
bool IsInteger (const std::string &str){
	unsigned int max=str.length();
	if (!max) return false;
	unsigned int i=(str[0]=='-')? 1:0;
	for(; i<max; i++)
		if (!_isdigit(str[i]))
			return false;
	return true;
}
//---------------------------------------------------------------------------
// 整数値から文字列を得る
string IntToString(int n)
{
	char buff[64];	// 多めに
	char *bp=buff;
	string ret;

	if(n<0) {
		ret+='-';
		n*=-1;
	}

	do {
		*(bp++)=(n%10)+'0';
		n/=10;
	} while(n>0);

	while(bp!=buff) {
		bp--;
		ret+=*bp;
	}

	return(ret);
}
//---------------------------------------------------------------------------
// パス表現の正規化
#define cctowc(A) ((wchar_t)(((wchar_t)A)&((wchar_t)0x00ff)))

#if defined(KAWARI_MS)
#	define SEPARATOR cctowc('\\')
#	define BADSEPARATOR cctowc('/')
#else
#	define SEPARATOR cctowc('/')
#	define BADSEPARATOR cctowc('\\')
#endif

static wstring CanonicalPath(const wstring &path){
	wstring ret=path;
	unsigned int len=ret.length();
	for (unsigned int i=0; i<len; i++)
		if (ret[i]==BADSEPARATOR) ret[i]=SEPARATOR;
	return ret;
}
static bool IsAbsolutePath(const wstring &path){
#ifdef KAWARI_MS
	return (((path.size()>0)&&(path[0]==cctowc('\\')))||((path.size()>1)&&(path[1]==cctowc(':'))));
#else
	return ((path.size()>0)&&(path[0]==cctowc('/')));
#endif
}
string CanonicalPath(const string &p){
	return wtoc(CanonicalPath(ctow(p)));
}
//---------------------------------------------------------------------------
string CanonicalPath(const string &basepath, const string &path){
	static const wstring parentlink=ctow("..")+SEPARATOR;
	wstring p=CanonicalPath(ctow(path));
	wstring bp=CanonicalPath(ctow(basepath));

	if (IsAbsolutePath(p)||(!bp.size()))
		return path;
	if (!p.size())
		return basepath;
	if (bp[bp.length()-1]==SEPARATOR)
		bp=bp.substr(0, bp.length()-1);
	while (bp.size()){
		if (p[0]!=cctowc('.'))
			break;
		
		if (StringCompare<wchar_t>(p, parentlink, 0, 3)==0){
			unsigned int pos=bp.rfind(SEPARATOR);
			if (pos==string::npos){
				bp=ctow("");
			}else{
				bp=bp.substr(0, pos);
			}
			p.erase(0, 3);
		}else if (StringCompare<wchar_t>(p, ctow(".")+SEPARATOR, 0, 2)==0){
			p.erase(0, 2);
		}else{
			// '.'で始まるファイル名？
			break;
		}
	}
	if (bp.size()) bp+=SEPARATOR;
	return wtoc(bp+p);
}
//---------------------------------------------------------------------------
string PathToFileName(const string &p){
	const wstring path=ctow(p);
	unsigned int pos=path.rfind(SEPARATOR);
	if (pos==string::npos)
		return p;
	else
		return wtoc(path.substr(pos+1));
}
//---------------------------------------------------------------------------
string PathToBaseDir(const string &p){
	const wstring path=ctow(p);
	unsigned int pos=path.rfind(SEPARATOR);
	if (pos==string::npos)
		return ("");
	else
		return wtoc(path.substr(0, pos));
}
//---------------------------------------------------------------------------
