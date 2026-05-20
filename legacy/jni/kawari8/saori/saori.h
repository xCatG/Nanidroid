//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// SAORI インターフェース
//
//      Programed by Suikyo.
//
//  2002.04.15  Phase 8.0.0   えびさわさんバージョンを参考に導入
//
//---------------------------------------------------------------------------
#ifndef SAORI_H
#define SAORI_H
//---------------------------------------------------------------------------
#include "config.h"
#include "misc/phttp.h"
#include "libkawari/kawari_log.h"
//---------------------------------------------------------------------------
#include <string>
#include <map>
#include <vector>
//---------------------------------------------------------------------------
namespace saori{
enum LOADTYPE {
	PRELOAD,
	LOADONCALL,
	NORESIDENT
};
class SAORIInfo{
public:
	std::string path;
	int bindcount;
	int loadcount;

	SAORIInfo(const std::string &p, int bc, int lc): path(p), bindcount(bc), loadcount(lc) {}
};
//---------------------------------------------------------------------------
// SAORIたん縛り
class TBind{
public:
	// SAORI/1.0 リクエストを発行
	// ライブラリのロード＋リンクは必要に応じて行われる。
	// 戻り値 : request/responseプロトコルが成功すればTRUE
	bool Request(const TPHMessage &request, TPHMessage &response);

	TBind(class IModuleFactory &fac, TKawariLogger &lgr, const std::string &path, const saori::LOADTYPE t);

	~TBind();

private:
	saori::LOADTYPE type;
	std::string libpath;
	class TModule *module;
	class IModuleFactory &factory;
	TKawariLogger &logger;

	bool Query(const TPHMessage &request, TPHMessage &response);
	void Attach(void);
	void Detach(void);

};
//---------------------------------------------------------------------------
// Facadeクラス
class TSaoriPark {
public:
	// モジュールの登録
	void RegisterModule(const std::string &alias, const std::string &path, const LOADTYPE type);

	// モジュール登録の削除
	void EraseModule(const std::string &alias);

	// モジュールを得る
	TBind *const GetModule(const std::string &alias);

	// 登録済みモジュール名のリストを得る
	int ListModule(std::vector<std::string> &list);

	// コンストラクタ
	TSaoriPark(TKawariLogger &logger);

	// 全モジュールのアンロード
	~TSaoriPark();

private:
	class TModuleFactoryMaster *factory;
	TKawariLogger &logger;

	// alias -> Module
	std::map<std::string, TBind *> aliasmap;

	friend class TBind;
};
//---------------------------------------------------------------------------
} // namespace saori
//---------------------------------------------------------------------------
#endif // SAORI_H
