//---------------------------------------------------------------------------
//
// KOSUI - Direct SSTP Adapter
//
//      Programed by Suikyo.
//
//  2002.05.04  Phase 8.0.0   FirstVersion
//
//---------------------------------------------------------------------------
#ifndef KOSUI_DSSTP_H
#define KOSUI_DSSTP_H
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include <windows.h>
#include <string>
//---------------------------------------------------------------------------
class TKosuiDSSTPInterface : public TKawariInterface_base {
public:
	// コンストラクタ
	// hwnd : DAのウィンドウハンドル
	// event : 使用するイベント名
	TKosuiDSSTPInterface (HWND hwnd, const string& evt);

	// デストラクタ
	virtual ~TKosuiDSSTPInterface();

	// 情報を得る
	virtual std::string GetInformation(void);

	// 与えられたソースを解釈・実行する
	virtual std::string Parse(const std::string& script);

private:
	// リクエストタイプ
	string event;

	// DAのウィンドウハンドル
	HWND dahwnd;

	// DSSTPに使うこちら側のダミーウィンドウハンドル
	HWND dumhwnd;
};
//---------------------------------------------------------------------------
#endif // KOSUI_DSSTP_H
