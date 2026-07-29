//---------------------------------------------------------------------------
//
// 抽象化華和梨インターフェース
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.08.12  Phase 6.2.1   FirstVersion
//
//---------------------------------------------------------------------------
#ifndef KOSUI_BASE_H
#define KOSUI_BASE_H
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include <string>
#include <iostream>
using namespace std;
//---------------------------------------------------------------------------
class TKawariInterface_base {
public:
	// デストラクタ
	virtual ~TKawariInterface_base() {}

	// 情報を得る
	virtual string GetInformation(void)=0;

	// 与えられたスクリプトを解釈・実行する
	virtual string Parse(const string& script)=0;
};
//---------------------------------------------------------------------------
#endif

