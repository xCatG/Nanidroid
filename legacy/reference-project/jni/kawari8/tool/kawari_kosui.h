//---------------------------------------------------------------------------
//
//  Kawari - KOSUI Adapter
//
//      Programed by Suikyo
//
//  2002.04.12  Phase 8.0.0   インターフェース切り直し
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#ifndef KAWARI_KOSUI_H
#define KAWARI_KOSUI_H
//---------------------------------------------------------------------------
#include <string>
#include <iostream>
#include <cstdlib>
#include <ctime>
using namespace std;
//---------------------------------------------------------------------------
#include "tool/kosui_base.h"
#include "libkawari/kawari_engine.h"
#include "libkawari/kawari_log.h"
#include "libkawari/kawari_version.h"
#include "misc/misc.h"
//---------------------------------------------------------------------------
// 華和梨＝幸水アダプタ
class TKawariKosuiAdapter: public TKawariInterface_base {
private:

	// 華和梨エンジン
	TKawariEngine engine;

public:

	TKawariKosuiAdapter(const string& datapath,const string& inifile)
	{
		// 乱数初期化
		SRandom((unsigned int)time(NULL));

		engine.CreateEntry("System.DataPath").Push(engine.CreateStrWord(datapath));
		engine.WriteProtect("System.DataPath");

		// セットアップ
		engine.GetLogger().SetStream(&cout);
		engine.SetDataPath(datapath);

		if (inifile.size())
			engine.LoadKawariDict(inifile);
	}

	~TKawariKosuiAdapter()
	{
		TEntry entry=engine.GetEntry("System.Callback.OnUnload");
		unsigned int size=entry.Size();
		for(unsigned int i=0;i<size;i++) engine.Parse(entry.Index(i));
	}

	// 情報を得る
	virtual string GetInformation(void)
	{
		return (
			"Version : " KAWARI_NAME "/" KAWARI_MAJOR "." KAWARI_MINOR "." KAWARI_SUBMINOR "\n"
			"Kernel Version : " KAWARI_CORE_VERSION "  / " KAWARI_CORE_CODENAME "\n"
			KAWARI_CORE_CREDITS);
	}

	// 与えられたスクリプトを解釈・実行する
	virtual string Parse(const string& script)
	{
		return(engine.Parse(script));
	}
};
//---------------------------------------------------------------------------
#endif // KAWARI_KOSUI_H
