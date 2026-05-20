//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// SAORI インターフェース(javaモジュール)
//
//      Programed by Suikyo.
//
//  2003.02.25  Phase 8.1.0   導入
//
//---------------------------------------------------------------------------
/**
	Javaさおり対応のために、Java側で必要なもの:
	- VM
	  o Java(TM)2以降
	  o 何らかの方法で起動済みVM構造体へのポインタが渡されている
	- ファクトリクラス
	  o publicな引数なしコンストラクタを持つ
	  o public Object createModule(byte[] name)メソッドを持つ
	  o システムプロパティ"jp.nanika.saoriinterface"に完全限定クラス名を登録
        ex) net/sf/kawari/SaoriFactory
	- モジュールクラス
	  o ファクトリのcreateModuleによって生成される
	  o public boolean load(byte[] libpath);
	    public boolean unload(void);
	    public byte[] request(byte[] reqstr);
	    の三つのメソッドを持つ
	*/
//---------------------------------------------------------------------------
#ifndef SAORI_JAVA_H
#define SAORI_JAVA_H
//---------------------------------------------------------------------------
#include "config.h"
#include "saori/saori_module.h"
//---------------------------------------------------------------------------
#include <jni.h>
#include <string>
#include <map>
//---------------------------------------------------------------------------
namespace saori{
//---------------------------------------------------------------------------
class TModuleFactoryJava : public IModuleFactory{
protected:
	// ファクトリへのグローバル参照
	jclass cls_SaoriFactory;
	jobject obj_SaoriFactory;

	// ファクトリクラスのメソッド、
	// "java.lang.Object createModule(java.lang.String name)"のメソッドID
	// シグネチャは "(Ljava/lang/String;)Ljava/lang/Object"
	jmethodID mid_createModule;

public:
	// モジュールの検索と生成
	// 戻り値: 生成に成功した場合、インスタンス。失敗した場合、NULL。
	virtual TModule *CreateModule(const std::string &path);

	// モジュールの完全破棄
	virtual void DeleteModule(TModule *module);

	// コンストラクタ
	TModuleFactoryJava(class TKawariLogger &lgr);

	// デストラクタ
	virtual ~TModuleFactoryJava(void);

	friend class TModuleJava;
};
//---------------------------------------------------------------------------
class TModuleJava : public TModule{
public:
	// 初期化
	virtual bool Initialize(void);
	// SAORI/1.0 Load
	virtual bool Load(void);
	// SAORI/1.0 Unload
	virtual bool Unload(void);
	// SAORI/1.0 Request
	virtual std::string Request(const std::string &reqstr);

	virtual ~TModuleJava();

protected:
	// SAORI_HANDLEは、さおりオブジェクト実体へのグローバル参照
	TModuleJava(TModuleFactoryJava &fac, const std::string &p, SAORI_HANDLE handle)
		: TModule(fac, p, handle){}

	// さおりへのグローバル参照
	jclass cls_saori;
	jobject obj_saori;

	// さおりクラスの各メソッドID
	// boolean load(byte[] libpath)		"([B)Z"
	// boolean unload(void);			"(V)Z"
	// byte[] request(byte[] reqstr);	"([B)[B"
	jmethodID mid_load;
	jmethodID mid_unload;
	jmethodID mid_request;

	friend class TModuleFactoryJava;
};
//---------------------------------------------------------------------------
} // namespace saori
//---------------------------------------------------------------------------
#endif // SAORI_JAVA_H
//---------------------------------------------------------------------------
