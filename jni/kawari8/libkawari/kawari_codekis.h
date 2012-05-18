//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// 中間コード(KIS構文)
//
//      Programed by Suikyo.
//
//  2002.04.18  Phase 8.0.0   KIS構文中間コード作成
//
//---------------------------------------------------------------------------
#ifndef KAWARI_CODEKIS_H__
#define KAWARI_CODEKIS_H__
//---------------------------------------------------------------------------
#include "libkawari/kawari_code.h"
//---------------------------------------------------------------------------
// KIS IF文
class TKVMKISCodeIF : public TKVMCode_base {
	std::vector<TKVMCode_base *> condlist;
	std::vector<TKVMCode_base *> list;
public:
	// 実行
	virtual std::string Run(class TKawariVM &vm);
	// 逆コンパイル
	virtual std::string DisCompile(void) const;
	// デバッグ用ツリー表示
	virtual std::ostream &Debug(std::ostream& os, unsigned int level=0) const;
	// 序列 同じクラスの場合のみ呼ばれる。
	virtual bool Less(const TKVMCode_base& R_) const;
	// コンストラクタ
	TKVMKISCodeIF(
		const std::vector<TKVMCode_base *> &clist,
		const std::vector<TKVMCode_base *> &l);
	// デストラクタ
	virtual ~TKVMKISCodeIF();
};
#if 0
//---------------------------------------------------------------------------
// KIS WHILE文
class TKVMKIS_WHILE : public TKVMCodeScriptStatement {
public:
	virtual std::string Run(class TKawariVM &vm);
	virtual ~TKVMKIS_IF() { }
	TKVMKIS_WHILE(const std::vector<TKVMCode_base *> &tmplist): TKVMCodeScriptStatement(tmplist) {}
};

#endif
//---------------------------------------------------------------------------
#endif // KAWARI_CODEKIS_H__
