//---------------------------------------------------------------------------
//
// Base64エンコーダ/デコーダ
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.03.15  Revision 1    FirstVersion
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "misc/base64.h"
//---------------------------------------------------------------------------
// Base64のエンコード
string EncodeBase64(string& str)
{
	const char *table="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

	string encodedstr;

	int rest=str.size()%3;
	if(rest==1) str+='\0';	// ダミーデータ
	if(rest>0) str+='\0';	// ダミーデータ

	unsigned int len=str.size()/3;
	for(unsigned int i=0;i<len;i++) {
		unsigned int dat
		 =(((unsigned int)str[i*3  ])&0xff)<<16
		 |(((unsigned int)str[i*3+1])&0xff)<<8
		 |(((unsigned int)str[i*3+2])&0xff);

		encodedstr+=table[(dat>>18)&0x3f];
		encodedstr+=table[(dat>>12)&0x3f];
		encodedstr+=table[(dat>> 6)&0x3f];
		encodedstr+=table[(dat    )&0x3f];
	}

	if(rest==1) {
		encodedstr[(len-1)*4+2]='=';
		encodedstr[(len-1)*4+3]='=';
		str=str.erase(str.size()-2,2);
	}
	if(rest==2) {
		encodedstr[(len-1)*4+3]='=';
		str=str.erase(str.size()-1,1);
	}

	return(encodedstr);
}
//---------------------------------------------------------------------------
// Base64のデコード
#if defined(STRING_APPEND_INATOMIC)
string DecodeBase64(const string& encodedstr)
{
	string str;
	char *s_top = new char[encodedstr.size()];	// ほんとは４分の３でよい 
	char *s = s_top;

	unsigned int rest=0;

	unsigned int len=encodedstr.size()/4;
	for(unsigned int i=0;i<len;i++) {
		unsigned int dat=0;
		for(unsigned int j=0;j<4;j++) {
			dat=dat<<6;

			char c=encodedstr[i*4+j];
			if((0x30<=c)&&(c<=0x39)) {
				dat|=c-0x30+52;
			} else if((0x41<=c)&&(c<=0x5a)) {
				dat|=c-0x41;
			} else if((0x61<=c)&&(c<=0x7a)) {
				dat|=c-0x61+26;
			} else if(c==0x2b) {
				dat|=62;
			} else if(c==0x2f) {
				dat|=63;
			} else if(c==0x3d) {
				rest++;
			}
		}

		*s =(char)((dat>>16)&0x0ff);
		s[1] =(char)((dat>> 8)&0x0ff);
		s[2] =(char)((dat    )&0x0ff);
		s += 3;
	}

	str.assign(s_top, s - s_top - rest);
	
	delete[] s_top;
	return(str);
}
#else
string DecodeBase64(const string& encodedstr)
{
	string str;

	unsigned int rest=0;

	unsigned int len=encodedstr.size()/4;
	for(unsigned int i=0;i<len;i++) {
		unsigned int dat=0;
		for(unsigned int j=0;j<4;j++) {
			dat=dat<<6;

			char c=encodedstr[i*4+j];
			if((0x30<=c)&&(c<=0x39)) {
				dat|=c-0x30+52;
			} else if((0x41<=c)&&(c<=0x5a)) {
				dat|=c-0x41;
			} else if((0x61<=c)&&(c<=0x7a)) {
				dat|=c-0x61+26;
			} else if(c==0x2b) {
				dat|=62;
			} else if(c==0x2f) {
				dat|=63;
			} else if(c==0x3d) {
				rest++;
			}
		}

		str+=(char)((dat>>16)&0x0ff);
		str+=(char)((dat>> 8)&0x0ff);
		str+=(char)((dat    )&0x0ff);
	}

	str=str.erase(str.size()-rest,rest);

	return(str);
}
#endif
//---------------------------------------------------------------------------
