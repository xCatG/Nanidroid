//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// ロガー
//
//      Programed by Suikyo
//
//  2002.04.12  Phase 8.0.0   作成
//  2008.01.23  Phase 8.2.5   include <cstdio> to define [EOF] (PaulLiu)
//
//---------------------------------------------------------------------------
#include "libkawari/kawari_log.h"
#include <cstdio>
//---------------------------------------------------------------------------
class nullstreambuf : public streambuf {
public:
	nullstreambuf (): streambuf() {}
	virtual int overflow(int p_iChar=EOF){
		return 0;
	}
	virtual int uflow(void){
		return EOF;
	}
	virtual int pbackfail(int p_iChar=EOF){
		return p_iChar;
	}
	virtual int underflow(void){
		return EOF;
	}
	virtual int sync(){
		return 0;
	}
};
//---------------------------------------------------------------------------
TKawariLogger::TKawariLogger(void){
	errlevel=0;
	LogStream=NullStream=new ostream(new nullstreambuf);
}
//---------------------------------------------------------------------------
TKawariLogger::~TKawariLogger(void){
	delete NullStream;
}
//---------------------------------------------------------------------------
//TKawariLogger Logger;
//---------------------------------------------------------------------------
