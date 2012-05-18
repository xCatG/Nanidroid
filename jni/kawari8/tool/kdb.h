//---------------------------------------------------------------------------
//
// 華和梨デバッガ
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.05.24  Phase 0.10    コンソール版
//  2001.08.12  Phase 6.2.1   幸水と統合
//
//---------------------------------------------------------------------------
#ifndef KDB_H
#define KDB_H
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include <string>
#include <map>
using namespace std;
//---------------------------------------------------------------------------
#include <windows.h>
//---------------------------------------------------------------------------
#include "tool/kosui_base.h"
//---------------------------------------------------------------------------
// SakuraAPI版
class TKawariInterfaceSakuraAPI : public TKawariInterface_base {
private:

	HWND Target;

public:

	TKawariInterfaceSakuraAPI(HWND target) : Target(target) {}

	// SakuraAPI/1.3 イベント送信
	void SakuraEvent(const string& event,const string& param);

	// 情報を得る
	virtual string GetInformation(void)
	{
		return("UNKNOWN/0.0.0");
	}

	// 与えられたスクリプトを解釈・実行する
	virtual string Parse(const string& script)
	{
		SakuraEvent("ShioriEcho",script);
		return("");
	}
};
//---------------------------------------------------------------------------
// 認識可能な他ゴーストのHWnd一覧を返す
void GetGhostList(map<HWND,string> &ghostlist);
//---------------------------------------------------------------------------
#endif

