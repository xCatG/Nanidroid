//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// 偽AI代用品
//
//      Programed by NAKAUE.T (Meister)
//
//  2001.02.03  Phase 0.3     ノーコメント
//  2001.02.11  Phase 0.31    再帰定義実装
//  2001.02.23  Phase 0.4     卒論戦争終戦祈念
//                            召薫造蕕�にお眠り下さい、過ちは繰り返しませあ�
//                                                                バージョン
//                            kawari.ini導入
//                            複数辞書ファイル
//                            sentence.txt廃止
//  2001.03.15  Phase 0.42    unload予約
//  2001.04.25  Phase 0.50a1  レスポンス対応
//  2001.04.27  Phase 0.50a2  SHIORI/2.1対応
//  2001.04.28  Phase 0.50a3  COMMUNICATE戦争
//       |
//  2001.05.02
//  2001.05.03  Phase 0.50a4  インラインスクリプト
//  2001.05.12  Phase 0.50    Piroject-X 完結編
//                            SHIORI/2.2対応
//                            SHIORI/1.1切り捨て
//  2001.05.27  Phase 5.1     インタープリタ・コンパイラ化
//  2001.05.31  Phase 5.2     保守的piro
//  2001.06.10  Phase 5.3.1   GET Version対策
//                            偽SHIORI/2.4実装 (^_^;
//  2001.06.18  Phase 5.4     インラインスクリプト大幅強化
//  2001.07.10  Phase 6.0     getmoduleversion追加
//  2001.07.14  Phase 6.1     BCCメモリ浪費問題対策
//  2001.07.19  Phase 6.2     SHIORI/2.5実装
//  2001.08.08  Phase 6.2     幸水用にLoadSubを新設
//  2001.08.25  Phase 6.3     セキュリティーホール対策
//  2001.08.25  Phase 7.0     セキュリティ対策(WriteProtect)
//  2001.09.23  Phase 7.0.1   セキュリティ対策をredo34以降の方式に対応
//                            Sender経路情報追加(System.Sender.Path)
//                            3エントリ以上でのAND検索バグを修正
//                            SHIORI/2.3b対応
//                            インラインスクリプト強化
//                            起動速度の高速化
//                            ハイフンを使用する旧エントリの対応停止
//  2002.01.12  Phase 8.0     なかなか出来ないPhase8
//                            NIDからフィードバック
//                            kpcg Phase7.3.1からフィードバック
//  2002.03                   Phase 8.0 (Rhapsody in the Blue)は破棄
//  2002.03.10  Phase 7.9.0   Philedelphia実験
//                            kawari_engine_base.h廃止
//  2002.03.20                SAORI統合
//  2002.04.12  Phase 8.0.0   インタプリタ・コンパイラ書き直し
//                            インターフェース切り直し
//  2002.12.30  Phase 8.1.0   Winter Comicket Version
//                            複数インスタンスを許可(内部的に)
//
//
//---------------------------------------------------------------------------
#ifndef KAWARI_SHIORI_H
#define KAWARI_SHIORI_H
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include <string>
//---------------------------------------------------------------------------
#include "libkawari/kawari_engine.h"
#include "misc/phttp.h"
#include "libkawari/kawari_version.h"
//---------------------------------------------------------------------------
#define SHIORIVER	"3.0"
#define SAORIVER	"1.0"
//---------------------------------------------------------------------------
// あれ以外の何か・偽AI仕様インターフェース
// 旧 TNS_KawariANI
class TKawariShioriAdapter{
private:

	// 華和梨エンジン
	TKawariEngine Engine;

	// 識別用のゴースト名
	// NOTIFYで送られてくる名前
	std::string GhostName;

	// セキュリティレベル
	class TSecurityLevel {
	private:
		unsigned int Level;
	public:
		// 伝達経路情報
		enum TSenderPath {
			tsidSystem=0,
			tsidLocal=1,
			tsidUnknown=2,
			tsidExternal=3
		};

		// セキュリティレベルを設定する
		// 3 : LOCALを許可する
		// 2 : LOCAL, UNKNOWNを許可する
		// 1 : LOCAL, UNKNOWNを許可する(2と同じ)
		// 0 : LOCAL, UNKNOWN, EXTERNALを許可する
		bool Set(unsigned int lv)
		{
			Level=(lv<=3)?lv:Level;
			return(Level==lv);
		}

		unsigned int Get(void)
		{
			return(Level);
		}

		// Senderが許可されているかどうかチェックする
		bool Check(TSenderPath path)
		{
			switch(Level) {
			case 3: return(path==tsidLocal);
			case 2: return(path<=tsidUnknown);
			case 1: return(path<=tsidUnknown);
			case 0: return(path<=tsidExternal);
			default: return(path<=tsidUnknown);
			}
		}

		TSecurityLevel(unsigned int lv)
		{
			Set(lv);
		}
	} SecurityLevel;


	// Senderを伝達経路情報と分離
	void GetSenderPath(const std::string &senderstr,
	 TSecurityLevel::TSenderPath &sender_path,std::string &sender_path_name);

	// 指定したエントリの単語を全て呼び出す
	std::string EnumExec(const std::string& key);

	// 辞書内のレスポンスを全て読み出す
	int GetResponse(TPHMessage &response);

	// Loadが呼ばれたか
	bool initialized;

public:
	// 複数インスタンスを許可
	TKawariShioriAdapter(void) : SecurityLevel(2), initialized(false) { }

	virtual ~TKawariShioriAdapter() {}

	// SHIORI/2.x API

	// DLLロード時に呼ばれる
	// const std::string& datapath : DLLのディレクトリパス
	// 戻り値 bool : 成功でtrue
	bool Load(const std::string& datapath);

	// DLLアンロード時に呼ばれる
	// 戻り値 bool : 成功でtrue
	bool Unload(void);

	// 偽AIリクエスト
	// TPHMessage &request : リクエストメッセージ
	// TPHMessage &response : レスポンスメッセージ
	void Request(TPHMessage &request, TPHMessage &response);

	// SHIORI for POSIX 2.4

	// 偽AIモジュールのバージョン番号を返す
	// 戻り値 std::string : "基本名称[.補助名称[.補助名称]]/バージョン番号"
	// ex. "KAWARI.meister/7.9.0" Meister版 華和梨 Phase7.9.0
	static inline std::string GetModuleVersion(void)
	{
		return(KAWARI_NAME "/" KAWARI_MAJOR "." KAWARI_MINOR "." KAWARI_SUBMINOR);
	}
};
//---------------------------------------------------------------------------
class TKawariShioriFactory{
private:
	// Singleton
	static TKawariShioriFactory *instance;
	TKawariShioriFactory (void) {}

	// 栞のリスト
	std::vector<TKawariShioriAdapter *> list;

public:
	// ファクトリの獲得
	static TKawariShioriFactory &GetFactory(void){
		if (!instance)
			instance=new TKawariShioriFactory();
		return (*instance);
	}
	// ファクトリの破棄
	// 破棄後に再生成も可能
	static void DisposeFactory(void){
		// delete NULL is safe.
		delete instance;
		instance=NULL;
	}

	// デストラクタ
	~TKawariShioriFactory();

	// モジュールバージョン
	static inline std::string GetModuleVersion(void){
		return TKawariShioriAdapter::GetModuleVersion();
	}
	// 新規インスタンスの作成
	// 戻り値 : ハンドル
	unsigned int CreateInstance(const std::string &datapath);
	// インスタンスの削除
	bool DisposeInstance(unsigned int handle);
	// インスタンスへのリクエスト
	std::string RequestInstance(unsigned int handle, const std::string &reqstr);
};
//---------------------------------------------------------------------------
#endif // KAWARI_SHIORI_H
//---------------------------------------------------------------------------
