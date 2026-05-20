//---------------------------------------------------------------------------
//
// TPHMessage - 偽HTTPメッセージ -
//
//      Programed by Suikyo.
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "misc/phttp.h"
//---------------------------------------------------------------------------
#ifdef HAVE_SSTREAM_H
#include <sstream>
#else
#include <strstream>
#endif
#include <iostream>
#include <string>
using namespace std;
//---------------------------------------------------------------------------
// シリアライズ
string TPHMessage::Serialize(void) const{
	string ret=startline;
	ret+="\x0d\x0a";

	for(TPHMessage::const_iterator it=begin();it!=end();it++) {
		if(it->second.size())
			ret+=it->first+": "+it->second+"\x0d\x0a";
	}
	ret+="\x0d\x0a";
	return ret;
}
//---------------------------------------------------------------------------
// デシリアライズ
void TPHMessage::Deserialize(const string &mes){
#ifdef HAVE_SSTREAM_H
	istringstream *ifs=new istringstream(mes.c_str());
#else
	istrstream *ifs=new istrstream(mes.c_str());
#endif
	string buff;

	getline(*ifs,buff,'\x0a');
	if(buff[buff.size()-1]=='\x0d') buff.erase(buff.size()-1);	// 0x0dを取り除く
	startline=buff;

	// ヘッダを分解してmapに積む
	while(getline(*ifs,buff,'\x0a')) {
		if(buff.size()==0) break;
		if(buff[buff.size()-1]=='\x0d') buff.erase(buff.size()-1);	// 0x0dを取り除く
		if(buff.size()==0) break;

		unsigned int pos=buff.find(':');
		string key=buff.substr(0,pos);

		pos++;
		while(buff[pos]==' ') pos++;

		Add(key,buff.substr(pos));
	}

	delete ifs;
}
//---------------------------------------------------------------------------
// ダンプ
void TPHMessage::Dump(ostream &os) const{
	os << startline << endl;
	for (TPHMessage::const_iterator it=begin(); it!=end(); it++){
		os << it->first << ": " << it->second << endl;
	}
	os << endl;
}
//---------------------------------------------------------------------------
