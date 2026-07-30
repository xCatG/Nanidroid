//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// KawariInlineScript -- SHIORI/2.5 URLリスト --
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.08.08  Phase 6.2     First version
//  2001.08.12  Phase 6.2.1   末尾に[2]追加
//                            仕様書バグにやられた(;_;)
//  2001.08.25  Phase 6.3     chr追加・・・華和梨らしい？
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "kis/kis_urllist.h"
//---------------------------------------------------------------------------
#include <cstdlib>
using namespace std;
//---------------------------------------------------------------------------
string KIS_urllist::Function(const vector<string>& args)
{
	if(!AssertArgument(args, 4)) return ("");
	if((args.size()%3)!=1) return("");

	string retstr;
	for(unsigned int i=1;i<args.size();i+=3) {
		if(args[i]!="-") retstr=retstr+args[i]+"\x1"+args[i+1]+"\x1"+args[i+2]+"\x2";
		 else retstr+="-\x2";
	}

	return(retstr);
}
//---------------------------------------------------------------------------
string KIS_chr::Function(const vector<string>& args)
{
	if(!AssertArgument(args, 2, 2)) return ("");

	char buff[3]={'\0','\0','\0'};

	unsigned int code=(unsigned int)atoi(args[1].c_str());

	if(code<=255) {
		buff[0]=(char)(code&0xff);
		return(string(buff,1));
	} else {
		buff[0]=(char)((code>>8)&0xff);
		buff[1]=(char)(code&0xff);
		return(string(buff,2));
	}
}
//---------------------------------------------------------------------------

