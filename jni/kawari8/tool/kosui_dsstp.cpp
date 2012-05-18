//---------------------------------------------------------------------------
//
// KOSUI - Direct SSTP Adapter
//
//      Programed by Suikyo.
//
//  2002.05.04  Phase 8.0.0   FirstVersion
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include <process.h>
//---------------------------------------------------------------------------
#include "tool/kosui_base.h"
#include "tool/kosui_dsstp.h"
#include "misc/phttp.h"
//---------------------------------------------------------------------------
#include <string>
#include <cstdio>
#include <iostream>
using namespace std;
//---------------------------------------------------------------------------
#if defined(__CYGWIN__)
static void MsgLoopThread(void *);
DWORD WINAPI ThreadWrapper(void *arg){
	MsgLoopThread(arg);
	return 1;
}
unsigned long _beginthread(void (*start_address)(void *), unsigned stack_size, void *arglist)
{
	DWORD thid;
	::CreateThread(NULL, stack_size, &ThreadWrapper, arglist, 0, &thid);
	return thid;
}
void _endthread (void)
{
	::ExitThread(0);
}
#endif
//---------------------------------------------------------------------------
namespace{
	const unsigned int DIRECT_SSTP_PORT=9801;

	// 戻り値が有効かどうか
	bool isResponseValid=false;

	// 戻り値受け渡し場所
	string response;

	// ミューテックスオブジェクト
	HANDLE mutex;
}
//---------------------------------------------------------------------------
static LRESULT CALLBACK MainWndProc(
		HWND hwnd,
		UINT message,
		WPARAM wParam,
		LPARAM lParam)
{
	COPYDATASTRUCT* pcds;
	switch(message){
	case WM_DESTROY:
		PostQuitMessage(0);
		break;
	case WM_COPYDATA:
		pcds=(COPYDATASTRUCT*)lParam;
		if((pcds!=NULL)&&(pcds->dwData==DIRECT_SSTP_PORT)){
			string tmpstr((LPCSTR)pcds->lpData, 0, (DWORD)pcds->cbData);
			if(::WaitForSingleObject(mutex, 1000)!=WAIT_TIMEOUT){
				response=tmpstr;
				isResponseValid=true;
				::ReleaseMutex(mutex);
			}else{
				// 強引
				isResponseValid=true;
			}
			return TRUE;
		}else{
			return FALSE;
		}
	default:
		return DefWindowProc(hwnd, message, wParam, lParam);
	}
	return 0L;
}
//---------------------------------------------------------------------------
static void MsgLoopThread(void *){
	MSG msg;

	/* メッセージを受け取って発行するループ */
	while( GetMessage(&msg, NULL, 0, 0) ){
		TranslateMessage(&msg);
		DispatchMessage(&msg);
	}

	_endthread();
}
//---------------------------------------------------------------------------
// コンストラクタ
// hwnd : DAのウィンドウハンドル
TKosuiDSSTPInterface::TKosuiDSSTPInterface (HWND hwnd, const string& evt)
	: event(evt), dahwnd(hwnd), dumhwnd(0) {
	WNDCLASS wndclass;
	memset(&wndclass, 0, sizeof(WNDCLASS));

	char *szClassName="KOSUI dummy window";
	char *szAppName="KOSUI";

	// 現在のインスタンスを得る
	HINSTANCE hInstance=(HINSTANCE)::GetModuleHandle(NULL);

	// ダミーのウィンドウを作る
	wndclass.lpfnWndProc = MainWndProc;
	wndclass.hInstance = hInstance;
	wndclass.lpszClassName = szClassName;
	RegisterClass(&wndclass);
	dumhwnd = CreateWindow(
			szClassName, szAppName,
			WS_POPUP,
			CW_USEDEFAULT,CW_USEDEFAULT,CW_USEDEFAULT,CW_USEDEFAULT,
			NULL,NULL, hInstance, NULL);

	// Mutex Objectを作成
	mutex=::CreateMutex(NULL, FALSE, NULL);

	// メッセージループスレッドの開始
	_beginthread(&MsgLoopThread, 0, NULL);
}
//---------------------------------------------------------------------------
// デストラクタ
TKosuiDSSTPInterface::~TKosuiDSSTPInterface(){
	// ウィンドウの削除
	DestroyWindow(dumhwnd);
	// ミューテックスオブジェクトの削除
	::CloseHandle(mutex);
}
//---------------------------------------------------------------------------
// 情報を得る
string TKosuiDSSTPInterface::GetInformation(void){
	return "Can not get Information.(Direct SSTP mode)";
}
//---------------------------------------------------------------------------
// 与えられたソースを解釈・実行する
string TKosuiDSSTPInterface::Parse(const string& script){
	// 橋本孔明さんの 簡易SSTPクライアント を参考
	TPHMessage request;

	char szHWnd[100];
	sprintf(szHWnd, "%d", (unsigned int)dumhwnd);

	request.SetStartline("NOTIFY SSTP/1.0");
	request["Charset"]="Shift_JIS";
	request["Sender"]="KOSUI";
	request["Event"]=event;
	request["HWnd"]=szHWnd;
	request["Reference0"]=script;

	string reqmes=request.Serialize();

	COPYDATASTRUCT cds;
	cds.dwData = DIRECT_SSTP_PORT;
	cds.cbData = reqmes.size();
	cds.lpData = (LPVOID)reqmes.c_str();

	DWORD dwret;
	::SendMessageTimeout(
		dahwnd, WM_COPYDATA, (WPARAM)dumhwnd,
		(LPARAM)&cds, SMTO_ABORTIFHUNG, 1000, &dwret);

	string respmes="";

	if((::WaitForSingleObject(mutex, 1000)!=WAIT_TIMEOUT)&&isResponseValid){
		respmes=response;
		isResponseValid=false;
		::ReleaseMutex(mutex);
	}
	return respmes;
}
//---------------------------------------------------------------------------
