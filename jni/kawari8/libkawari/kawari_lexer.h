//--------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// 字句解析
//
//      Programed by Suikyo
//
//  2002.04.18  Phase 8.0.0   書き直し
//
//--------------------------------------------------------------------------
#ifndef LEXER_H__
#define LEXER_H__
//--------------------------------------------------------------------------
#include "config.h"
//--------------------------------------------------------------------------
#include "libkawari/kawari_log.h"
//--------------------------------------------------------------------------
#include <iostream>
#include <string>
#include <vector>
//--------------------------------------------------------------------------
class Token;
class TKawariLexer;
class TKawariPreProcessor;
//--------------------------------------------------------------------------
class Token {
public:
	// 特殊文字は、全て文字コードがそのままintになって返る。
	enum Type {
		T_ERROR = 0x100,	//
		T_LITERAL,		//
		T_QLITERAL,		//
		T_SPACE,		// ' ', '\t'
		T_EOL,			// CR, LF, CRLF
		T_SPECIAL,		// special marks (in peek() only)
		T_MODESWITCH,	// '=xxxxx'
		T_EOF,
		T_DUMMY
	} type;
	std::string str;
	Token (Type t, const std::string &s) : type(t), str(s) {}
	Token (void) : type(T_DUMMY), str("") {}
	Token &set (Type t) { type=t; return *this; }
	Token &set (const std::string &s) { str=s; return *this; }
	Token &set (Type t, const std::string &s)
		{ type=t; str=s; return *this; }
	Token &set (Type t, const char &ch)
		{ type=t; str+=ch; return *this; }
};

//-------------------------------------------------------------------------
// class TKawariLexer;
//
class TKawariLexer{
public:
	// コンストラクタ
	TKawariLexer (std::istream &input, TKawariLogger &lgr,
				  std::string filename="<unknown>",
				  bool preprocess=true, int lineno=0);

	// Lexerのモード
	enum Mode {
		ID_MODE = 0,			// エントリ名モード
		LITERAL_MODE = 1,		// 文字列モード(エントリ定義中)
		LITERAL_MODE2 = 2,		// 文字列モード(インラインスクリプト中)
		LITERAL_MODE3 = 3		// 文字列モード(ブロック中)
	};

	// 次のトークンがあるか
	bool hasNext(void);

	// 次のトークンのタイプを返す。
	// 読むだけでポインタを進めない
	// mode : Lexerのモード指定(LITERAL_MODE, ID_MODE)
	Token::Type peek(Mode mode=LITERAL_MODE);

	// 次のトークンを返す
	// mode : Lexerのモード指定(LITERAL_MODE, ID_MODE)
	Token next(Mode mode=LITERAL_MODE);

	// !!! check type before use this !!!
	// 次のリテラルトークンを返す
	// 次がベアリテラルでなかったら、""が返る。予め分かっている場合にだけ使うこと。
	// mode : Lexerのモード指定(LITERAL_MODE, ID_MODE)
	std::string getLiteral(Mode mode=LITERAL_MODE);

	// !!! check type, before use this !!!
	// 次のクォートリテラルトークン(非デコード)を返す
	// 次がクォートリテラルでなかったら""が返る。
	std::string getQuotedLiteral(void);

	// !!! check type before use this !!!
	// [0-9]+
	// 整数文字列
	std::string getDecimalLiteral(void);

	// 1文字スキップ
	int skip(void);

	// ある文字の手前までスキップ (文法エラーからの復旧用)
	// noret : 改行を許さない場合true
	bool simpleSkipTo(char ch, bool noret=true);

	// ファイル名を返す
	const std::string &getFileName(void) const;

	// 現在の行番号を返す
	int getLineNo(void) const;

	// 現在行の残りを全て返す
	std::string getRestOfLine(void);

	// モードスイッチのリセット
	inline void ResetModeSwitch(void);

	// サービスメソッド
	// 空白(空白文字と改行文字)のトークンを読み飛ばす。
	void skipWS(void);

	// サービスメソッド
	// 空白(空白文字と改行文字)のトークンを読み飛ばし、
	// その次のトークンのタイプを返す
	Token::Type skipWS(Mode m);

	// サービスメソッド
	// 空白文字のトークンを読み飛ばし、その次のトークンのタイプを返す
	// 改行文字は飛ばさない。
	Token::Type skipS(Mode m=LITERAL_MODE);

	// 指定長さだけリードポインタを戻す
	bool UngetChars(unsigned int length);

	// サービスメソッド
	// クォート文字列をデコード
	static std::string DecodeQuotedString(const std::string &orgsen);

	// サービスメソッド
	// 文字列をエントリ名で使用可能な文字列にエンコードする
	static std::string EncodeEntryName(const std::string &orgsen);

	~TKawariLexer ();

	// コンパイルエラー
	void error(const std::string &message);

	// コンパイルエラー
	void warning(const std::string &message);

private:
	// 文字からトークンタイプを得る
	Token::Type checkType(Mode m, char ch) const;

	class TKawariPreProcessor *pp;
	std::string fn;

	TKawariLogger &logger;
};
//---------------------------------------------------------------------------
// プリプロセッサ
//
class TKawariPreProcessor {
public:
	TKawariPreProcessor(std::istream &input, int lineno=0, bool preprocess=true)
		: is(input), pp(preprocess), mc(false), isMS(false), ln(lineno), pos(0) {}
	bool peek(char &ch);
	bool getch(char &ch);
	bool eof(void);
	std::string getline(void);
	bool unget(void);
	int getLineNo(void){ return ln; }

	int getColumn(void){ return pos; }

	// バッファの一部を切り出す
	std::string substring(int index, int length);

	virtual ~TKawariPreProcessor() {}

	// 現在、モード切替待ちか
	bool IsWaitingModeSwitch(void){ return isMS; }

	// モードフラグのリセット
	void ResetModeSwitch(void){ isMS=false; }

private:
	bool processNextLine(void);
	// 入力ストリーム
	std::istream &is;
	// pre-processするか否かのフラグ
	bool pp;
	// 複数行コメントのフラグ
	bool mc;

	// Mode Switch
	bool isMS;
	// 現在行の行数
	unsigned int ln;
	// 現在カラム
	unsigned int pos;
	// 出力バッファ
	std::string linebuf;
};
//---------------------------------------------------------------------------
// 次のトークンがあるか
inline bool TKawariLexer::hasNext(void){
	return !pp->eof();
}
//---------------------------------------------------------------------------
inline void TKawariLexer::ResetModeSwitch(void){
	pp->ResetModeSwitch();
}
//---------------------------------------------------------------------------
inline void TKawariLexer::error(const std::string &message){
	logger.GetStream(kawari_log::LOG_ERROR) << getFileName() << " " << getLineNo()
		<< ": error: " << message << std::endl;
}
//---------------------------------------------------------------------------
inline void TKawariLexer::warning(const std::string &message){
	logger.GetStream(kawari_log::LOG_WARNING) << getFileName() << " " << getLineNo()
		<< ": warning: " << message << std::endl;
}
//---------------------------------------------------------------------------
inline bool TKawariPreProcessor::peek(char &ch){
	bool ret = getch(ch);
	unget();
	return ret;
}
//---------------------------------------------------------------------------
inline bool TKawariPreProcessor::getch(char &ch){
	if (pos>=linebuf.size())
		if (!processNextLine())
			return false;
	ch=linebuf[pos++];
	return true;
}
//---------------------------------------------------------------------------
inline bool TKawariPreProcessor::unget (void){
	if (pos==0) return false;
	pos--;
	return true;
}
//---------------------------------------------------------------------------
inline bool TKawariPreProcessor::eof (void){
	return ((pos>=linebuf.size())&&(is.eof()));
}
//---------------------------------------------------------------------------
#endif  // LEXER_H__
