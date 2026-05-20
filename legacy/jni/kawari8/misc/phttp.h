//---------------------------------------------------------------------------
//
// TPHMessage - 偽HTTPメッセージ -
//
//      Programed by Suikyo.
//
//---------------------------------------------------------------------------
#ifndef PHTTP_H
#define PHTTP_H
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "misc/mmap.h"
//---------------------------------------------------------------------------
#include <iostream>
#include <string>
//---------------------------------------------------------------------------
class TPHMessage : public TMMap<std::string, std::string> {
private:
	std::string startline;
public:
	// スタートラインの設定
	void SetStartline(const std::string &line) { startline=line; }

	// スタートラインを得る
	std::string GetStartline(void) const { return startline; }

	// シリアライズ
	std::string Serialize(void) const;

	// デシリアライズ
	void Deserialize(const std::string &mes);

	// ダンプ
	void Dump(std::ostream &os) const;
};
//---------------------------------------------------------------------------
#endif // PHTTP_H
