//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// ロガー
//
//      Programed by Suikyo
//
//  2002.04.12  Phase 8.0.0   Engineから分離
//
//---------------------------------------------------------------------------
#ifndef KAWARI_LOG_H
#define KAWARI_LOG_H
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include <string>
#include <vector>
#include <iostream>
using namespace std;

namespace kawari_log {
	enum LOGLEVEL {
		LOG_ERROR		=0x0001,
		LOG_WARNING		=0x0002,
		LOG_INFO		=0x0004,
		LOG_DECL		=0x0008,
		LOG_DUMP		=0x0010,
		LOG_BASEEVENTS	=0x0100,
		LOG_RSCEVENTS	=0x0200,
		LOG_MOUSEEVENTS	=0x0400,
		LOG_TIMEEVENTS	=0x0800
	};
}

//---------------------------------------------------------------------------
class TKawariLogger {
private:
	// ログストリーム
	std::ostream *LogStream;

	// どこにも入出力しないストリーム
	std::ostream *NullStream;

	// エラーレベル (論理和)
	unsigned int errlevel;

public:
	// 出力ストリーム取得
	std::ostream& GetStream(void) const;

	// 出力ストリーム取得
	std::ostream& GetStream(kawari_log::LOGLEVEL lvl) const;

	// 出力ストリームの設定
	void SetStream(std::ostream *outstream);

	// 出力ストリームを標準出力に設定
	void SetStreamStdOut(void);

	// エラーレベルを得る
	unsigned int ErrLevel(void) const { return errlevel; }

	// エラーレベルの設定
	void SetErrLevel(unsigned int level);

	// エラーレベルのチェック
	bool Check(unsigned int lvl) const{
		return ((errlevel&lvl)!=0);
	}
	TKawariLogger (void);
	~TKawariLogger ();
};
//---------------------------------------------------------------------------
// 出力ストリーム取得
inline std::ostream& TKawariLogger::GetStream(void) const
{
	return(*LogStream);
}
//---------------------------------------------------------------------------
// 出力ストリーム取得
inline std::ostream& TKawariLogger::GetStream(kawari_log::LOGLEVEL lvl) const{
	if ((errlevel&lvl)!=0)
		return(*LogStream);
	else
		return(*NullStream);
}
//---------------------------------------------------------------------------
// 出力ストリームの設定
inline void TKawariLogger::SetStream(std::ostream *outstream)
{
	if (outstream)
		LogStream=outstream;
	else
		LogStream=NullStream;
}
//---------------------------------------------------------------------------
// エラーレベルの設定
inline void TKawariLogger::SetErrLevel(unsigned int level){
	errlevel=level;
}
//---------------------------------------------------------------------------
// 出力ストリームを標準出力に設定
inline void TKawariLogger::SetStreamStdOut(void){
	LogStream=&cout;
}
//---------------------------------------------------------------------------
//extern TKawariLogger Logger;
//---------------------------------------------------------------------------
#endif // KAWARI_LOG_H
