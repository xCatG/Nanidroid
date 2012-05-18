//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// コンパイラ(電次元
//
//      Programed by NAKAUE.T (Meister) / Suikyo
//
//  2002.03.18                KIUに合わせてTKawariCompiler分離
//                            ・・・でも、インターフェースがちょっと違う(涙)
//  2002.04.18  Phase 8.0.0   走るカウンタック。コンパイラ特集。
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "libkawari/kawari_compiler.h"
#include "libkawari/kawari_lexer.h"
#include "libkawari/kawari_code.h"
#include "libkawari/kawari_codeexpr.h"
#include "libkawari/kawari_codeset.h"
#include "libkawari/kawari_codekis.h"
#include "libkawari/kawari_dict.h"
#include "libkawari/kawari_log.h"
#include "libkawari/kawari_rc.h"
#include "misc/misc.h"
using namespace kawari::resource;
using namespace kawari_log;
//---------------------------------------------------------------------------
#include <cctype>
#include <iostream>
#include <fstream>
#ifdef HAVE_SSTREAM_H
#include <sstream>
#else
#include <strstream>
#endif
using namespace std;
//---------------------------------------------------------------------------
namespace {
	const TKawariLexer::Mode ID_MODE=TKawariLexer::ID_MODE;
	const TKawariLexer::Mode LITERAL_MODE=TKawariLexer::LITERAL_MODE;
	const TKawariLexer::Mode SCRIPT_MODE=TKawariLexer::LITERAL_MODE2;
	const TKawariLexer::Mode BLOCK_MODE=TKawariLexer::LITERAL_MODE3;
}
//=========================================================================
// 公開メソッド群
//=========================================================================

//-------------------------------------------------------------------------
// コンストラクタ
TKawariCompiler::TKawariCompiler(istream &is, TKawariLogger &lgr, const string &filename, bool pp)
	 : logger(lgr) {
	lexer=new TKawariLexer(is, logger, filename, pp);
}
//-------------------------------------------------------------------------
// デストラクタ
TKawariCompiler::~TKawariCompiler (){
	if (lexer)
		delete lexer;
}
//-------------------------------------------------------------------------
// 次モードを得る
TKawariCompiler::Mode TKawariCompiler::GetNextMode(void){
	Token::Type t=lexer->skipWS(ID_MODE);
	if (t==Token::T_MODESWITCH){
		lexer->ResetModeSwitch();
		string line=lexer->getRestOfLine();
		// モード抽出作業
		line=StringTrim(line);
		if (line=="=dict")
			return M_DICT;
		else if (line=="=kis")
			return M_KIS;
		else if (line=="=end")
			return M_END;
		else{
			logger.GetStream(LOG_ERROR) << RC.S(ERR_COMPILER_UNKNOWN_MODE) << line << endl;
			return M_UNKNOWN;
		}
	}else if (t==Token::T_EOF){
		return M_EOF;
	}else{
		// 地の辞書モードと思われ
		return M_DICT;
	}
}
//-------------------------------------------------------------------------
// エントリ定義を読む
bool TKawariCompiler::LoadEntryDefinition(vector<string> &entries, vector<TKVMCode_base *> &sentences){

	Token::Type t=lexer->skipWS(ID_MODE);
	if ((t==Token::T_EOF)||(t==Token::T_MODESWITCH)){
		// ファイルの終了かモード切替
		return false;
	}else{
		// 単語定義

		// エントリ名リストを得る。
		if(!compileEntryIdList(entries)){
			// エントリ名指定が無い(何故)
			lexer->error(RC.S(ERR_COMPILER_NO_ENTRYNAMES));
			lexer->getRestOfLine();
			return true;
		}

		// ':'を読む。無ければエラーだけどそのまま続行。
		t=lexer->skipS(ID_MODE);
		if (t==(int)':'){
			lexer->skip();
			lexer->skipS();
			// 改行不可文並びを得る。
			if (!compileNRStatementList(sentences))
				lexer->warning(RC.S(WARN_COMPILER_BLANK_DEFINITION));
		}else if (t==(int)'('){
			lexer->skip();
			lexer->skipS();
			// 文並びを得る。
			if (!compileStatementList(sentences))
				lexer->warning(RC.S(WARN_COMPILER_BLANK_DEFINITION));
			t=lexer->skipWS(ID_MODE);
			if (t==(int)')'){
				lexer->skip();
			}else{
				lexer->error(RC.S(ERR_COMPILER_MLENTRYDEF_NOT_CLOSED));
			}
		}else{
			lexer->error(RC.S(ERR_COMPILER_INVALID_ENTRYDEF));
		}

		if (logger.Check(LOG_DUMP)){
			// ロギング
			ostream &log=logger.GetStream();
			log << "EntryNames(" << endl;
			for (vector<string>::const_iterator it=entries.begin();it!=entries.end();it++){
				log << "    " << (*it) << endl;
			}
			log << ")" << endl;

			for (TCodePVector::iterator it=sentences.begin();it!=sentences.end();it++){
				if (*it)
					(*it)->Debug(log, 0);
			}
		}

		return true;
	}
}
//-------------------------------------------------------------------------
// KIS定義を読む
TKVMCode_base *TKawariCompiler::LoadInlineScript(void){
	vector<TKVMCode_base *> tmplist;

	// 最初の行を読む
	TKVMCode_base *code = compileScriptStatement();
	if (code)
		tmplist.push_back(code);

	while (lexer->hasNext()){
		Token::Type t=lexer->skipWS(SCRIPT_MODE);
		if (t==(int)';'){
			// 次の行
			lexer->skip();
			TKVMCode_base *code = compileScriptStatement();
			if (code)
				tmplist.push_back(code);
		}else if ((t==Token::T_EOF)||(t==Token::T_MODESWITCH)){
			break;
		}else{
			lexer->error(RC.S(ERR_COMPILER_ILLCHAR_IN_SCRIPT));
			break;
		}
	}
	if (tmplist.size())
		return new TKVMCodeInlineScript(tmplist);
	else
		return new TKVMCodeString("");
}
//-------------------------------------------------------------------------
// 文字列をStatementとして中間コードへコンパイル
TKVMCode_base *TKawariCompiler::Compile(const string &src, TKawariLogger &logger){
#ifdef HAVE_SSTREAM_H
	istringstream is(src.c_str());
#else
	istrstream is(src.c_str());
#endif
	TKawariCompiler compiler(is, logger, "<unknown>", false);
	return compiler.compileStatement(true, BLOCK_MODE);
}

//-------------------------------------------------------------------------
// 文字列を、そのままStringとして中間コード化する
TKVMCode_base *TKawariCompiler::CompileAsString(const string &src){
	return new TKVMCodeString(src);
}
//-------------------------------------------------------------------------
// 文字列を集合演算式('$[' 〜 ']'の中身)としてコンパイル
TKVMSetCode_base *TKawariCompiler::CompileAsEntryExpression(const string &src, TKawariLogger &logger){
#ifdef HAVE_SSTREAM_H
	istringstream is(src.c_str());
#else
	istrstream is(src.c_str());
#endif
	TKawariCompiler compiler(is, logger, "<unknown>", false);
	return compiler.compileSetExpr0();
}

//-------------------------------------------------------------------------
// サービスメソッド
// 文字列をエントリ名で使用可能な文字列にエンコードする
string TKawariCompiler::EncodeEntryName(const string &orgsen){
	return TKawariLexer::EncodeEntryName(orgsen);
}
//-------------------------------------------------------------------------

//=========================================================================
// 非公開メソッド群
//=========================================================================

//-------------------------------------------------------------------------
// エントリ名並び ( IdLiteral S | IdLiteral S ',' S EntryIdList )
// return : 得られたエントリ名の数
int TKawariCompiler::compileEntryIdList(vector<string> &list){
	vector<string> tmplist;
	if (!lexer->hasNext())
		return 0;
	Token::Type t=lexer->peek(ID_MODE);
	if (t==Token::T_LITERAL){
		// 最初のエントリ名を読む
		tmplist.push_back(lexer->getLiteral(ID_MODE));
	}else{
		// それは許されないだろう
		lexer->error(RC.S(ERR_COMPILER_ENTRYID_NOT_FOUND));
		return 0;
	}
	while (lexer->hasNext()){
		Token::Type t=lexer->skipS();
		if (t==(int)','){
			lexer->skip();
			if (lexer->skipS()==Token::T_LITERAL){
				tmplist.push_back(lexer->getLiteral(ID_MODE));
			}else{
				// おかしい。','で終わっているんじゃないかな。
				lexer->warning(RC.S(WARN_COMPILER_ENTRYIDLIST_ENDS_WITH_COMMA));
				break;
			}
		}else{
			break;
		}
	}
	list.insert(list.end(), tmplist.begin(), tmplist.end());
	return tmplist.size();
}

//-------------------------------------------------------------------------
// 改行不可文並び。(注意)改行コードを読み込んで終了。
// return : 得られた文の数
int TKawariCompiler::compileNRStatementList(vector<TKVMCode_base *> &list){
	vector<TKVMCode_base *> tmplist;
	if (!lexer->hasNext())
		return 0;
	Token::Type t=lexer->skipS();
	if (t==Token::T_EOL){
		// いきなり終わってしまった
		lexer->skip();
		return 0;
	}else if (t!=(int)','){
		// 最初の文を読む
		TKVMCode_base *stmt=compileStatement(true);
		if (stmt){
			tmplist.push_back(stmt);
		}else{
			lexer->error(RC.S(ERR_COMPILER_FIRST_STATEMENT_NOT_FOUND));
			lexer->getRestOfLine();
			return 0;
		}
	}
	while (lexer->hasNext()){
		Token::Type t=lexer->skipS();
		if (t==(int)','){
			lexer->skip();
			lexer->skipS();
			TKVMCode_base *stmt=compileStatement(true);
			if (stmt)
				tmplist.push_back(stmt);
			// else ','で行が終わっている？ もしくは ', ,'みたいになってる？
			// ここで「NULL文字列」を入れるわけにはいかない。
		}else if (t==Token::T_EOL){
			// 終了
			break;
		}else{
			lexer->error(RC.S(ERR_COMPILER_STATEMENTLIST_SEPARATOR_NOT_FOUND));
			lexer->simpleSkipTo(',');
			if (lexer->peek()!=(int)',')
				break;
		}
	}
	// この行を全て読み込む
	lexer->getRestOfLine();
	list.insert(list.end(), tmplist.begin(), tmplist.end());
	return tmplist.size();
}

//-------------------------------------------------------------------------
// 文並び。')'で終了
// return : 得られた文の数
int TKawariCompiler::compileStatementList(vector<TKVMCode_base *> &list){
	vector<TKVMCode_base *> tmplist;
	if (!lexer->hasNext())
		return 0;
	Token::Type t=lexer->skipWS(ID_MODE);
	if (t==')'){
		// いきなり終わってしまった
		return 0;
	}else if (t!=(int)','){
		// 最初の文を読む
		TKVMCode_base *stmt=compileStatement(false);
		if (stmt){
			tmplist.push_back(stmt);
		}else{
			lexer->error(RC.S(ERR_COMPILER_FIRST_STATEMENT_NOT_FOUND));
			lexer->getRestOfLine();
			return 0;
		}
	}
	while (lexer->hasNext()){
		Token::Type t=lexer->skipWS(ID_MODE);
		if (t==(int)','){
			lexer->skip();
			lexer->skipS();
			TKVMCode_base *stmt=compileStatement(false);
			if (stmt)
				tmplist.push_back(stmt);
			// else ','で行が終わっている？ もしくは ', ,'みたいになってる？
			// ここで「NULL文字列」を入れるわけにはいかない。
		}else if (t==')'){
			// 終了
			break;
		}else{
			lexer->error(RC.S(ERR_COMPILER_STATEMENTLIST_SEPARATOR_NOT_FOUND));
			lexer->simpleSkipTo(',');
			if (lexer->peek()!=(int)',')
				break;
		}
	}
	list.insert(list.end(), tmplist.begin(), tmplist.end());
	return tmplist.size();
}

//-------------------------------------------------------------------------
// 文 ( WS (Word WS)* / S (Word S)* )
// NULLは返さない。
TKVMCode_base *TKawariCompiler::compileStatement(bool noret, int m){
	vector<TKVMCode_base *> tmplist;
	if (noret){
		// 改行を許さない
		while (lexer->hasNext()){
			// 違いはこの行のみ。
			lexer->skipS();
			TKVMCode_base *code=compileWord(m);
			if (code)
				tmplist.push_back(code);
			else
				break;
		}
	}else{
		// 改行を許す(恐らくブロック中のみ)
		while (lexer->hasNext()){
			// 違いはこの行のみ。
			lexer->skipWS();
			TKVMCode_base *code=compileWord(m);
			if (code)
				tmplist.push_back(code);
			else
				break;
		}
	}
	if (tmplist.size()==0){
		return new TKVMCodeString("");
	}else if (tmplist.size()==1){
		return tmplist[0];
	}else{
		return new TKVMCodeList(tmplist);
	}
}

//-------------------------------------------------------------------------
// スクリプト文 ( WS (Word WS)* )
// NULLは返さない。
TKVMCode_base *TKawariCompiler::compileScriptStatement(void){
	vector<TKVMCode_base *> tmplist;

	// 最初の単語を読む。
	Token::Type t=lexer->skipWS(SCRIPT_MODE);
	if (t==Token::T_LITERAL){
		string str=lexer->getLiteral(SCRIPT_MODE);
		if (str=="if"){
			return compileScriptIF();
		}else{
			lexer->UngetChars(str.size());
		}
	}

	// 改行を許す
	while (lexer->hasNext()){
		lexer->skipWS();
		TKVMCode_base *code=compileWord(SCRIPT_MODE);
		if (code){
			tmplist.push_back(code);
		}else{
			break;
		}
	}
	if (!tmplist.size()) return NULL;
	return new TKVMCodeScriptStatement(tmplist);
}
//-------------------------------------------------------------------------
// if
TKVMCode_base *TKawariCompiler::compileScriptIF(void){
	// 'if'の後から始まる
	vector<TKVMCode_base *> condlist;
	vector<TKVMCode_base *> list;

	while (lexer->hasNext()){
		// 条件文
		lexer->skipWS();
		TKVMCode_base *code=compileWord(SCRIPT_MODE);
		if (!code) break;
		condlist.push_back(code);

		// 実行文
		lexer->skipWS();
		code=compileWord(SCRIPT_MODE);
		if (!code) break;
		list.push_back(code);

		// 予約語探し
		Token::Type t=lexer->skipWS(SCRIPT_MODE);
		if (t!=Token::T_LITERAL) break;

		string str=lexer->getLiteral(SCRIPT_MODE);
		if (str=="else"){
			t=lexer->skipWS(SCRIPT_MODE);
			if (t==Token::T_LITERAL){
				str=lexer->getLiteral(SCRIPT_MODE);
				if (str=="if"){
					continue;
				}else{
					lexer->UngetChars(str.size());
					code=compileWord(SCRIPT_MODE);
					if (!code) break;
					list.push_back(code);
					break;
				}
			}else{
				code=compileWord(SCRIPT_MODE);
				if (!code) break;
				list.push_back(code);
				break;
			}
		}else{
			lexer->UngetChars(str.size());
			break;
		}
	}

	return new TKVMKISCodeIF(condlist, list);
}

//-------------------------------------------------------------------------
// 単語 ( (QLiteral | Literal | Block | Subst | S)* )
// スクリプト中単語 ( (QLiteral | Literal | Block | Subst)* )
// NULLを返す。
TKVMCode_base *TKawariCompiler::compileWord(int m){
	TKawariLexer::Mode mode=(TKawariLexer::Mode)m;
	vector<TKVMCode_base *> tmplist;
	bool escape=false;	// ループ脱出フラグ
	while (lexer->hasNext()&&(!escape)){
		Token::Type t=lexer->peek(mode);
		TKVMCode_base *code=NULL;
		if((t==Token::T_LITERAL)||(t==Token::T_QLITERAL)){
			string s;
			bool force=false;
			while (true){
				Token::Type t=lexer->peek(mode);
				if (t==Token::T_LITERAL){
					string tmps=lexer->getLiteral(mode);
					if (!tmps.size()){
						// 今でもあり得るんだっけ？
						escape=true;
						break;
					}
					s+=tmps;
				}else if (t==Token::T_QLITERAL){
					s+=TKawariLexer::DecodeQuotedString(lexer->getQuotedLiteral());
					force=true;
				}else{
					break;
				}
			}
			if (s.size()||force)
				code=new TKVMCodeString(s);
			else
				break;	// 無限ループ避け
		}else if(t==(int)'('){
			// Block
			code=compileBlock();
		}else if(t==(int)'$'){
			// Subst
			code=compileSubst();
		}else{
			break;
		}
		if (code)
			tmplist.push_back(code);
	}
	if (tmplist.size()==0){
		return NULL;
	}else if(tmplist.size()==1){
		return tmplist[0];
	}else{
		return new TKVMCodeList(tmplist);
	}
}
//-------------------------------------------------------------------------
// 置換 ( '$' ( EntryCallSubst | EntryIndexSubst | InlineScriptSubst | ExprSubst ) )
// NULLを返す
TKVMCode_base *TKawariCompiler::compileSubst(void){
	if (lexer->peek(ID_MODE)!=(int)'$'){
		lexer->error(RC.S(ERR_COMPILER_INTERNAL_SUBST));
		lexer->getRestOfLine();
		return NULL;
	}
	lexer->skip();
	Token::Type t=lexer->peek(ID_MODE);
	if (t==(int)'{'){
		// Macro Entry
		return compileEntryCallSubst();
	}else if(t==(int)'('){
		// Macro Inline
		return compileInlineScriptSubst();
	}else if((t==Token::T_LITERAL)||(t==(int)'$')){
		// Macro Array
		return compileEntryIndexSubst();
	}else if(t==(int)'['){
		// Macro Expr
		return compileExprSubst();
	}else{
		return NULL;
	}
}

//-------------------------------------------------------------------------
// インラインスクリプト ( '(' ScriptStatementSeq ') )
// NULLは返さない
TKVMCode_base *TKawariCompiler::compileInlineScriptSubst(void) {
	if (lexer->peek(ID_MODE)!=(int)'('){
		lexer->error(RC.S(ERR_COMPILER_INTERNAL_INLINE_SCRIPT));
		lexer->getRestOfLine();
		return NULL;
	}
	lexer->skip();

	vector<TKVMCode_base *> tmplist;

	// 最初の行を読む
	TKVMCode_base *code = compileScriptStatement();
	if (code)
		tmplist.push_back(code);

	bool closed=false;
	while (lexer->hasNext()){
		Token::Type t=lexer->skipWS(SCRIPT_MODE);
		if (t==(int)';'){
			// 次の行
			lexer->skip();
			TKVMCode_base *code = compileScriptStatement();
			if (code)
				tmplist.push_back(code);
		}else if (t==(int)')'){
			// That's end. もう終わり
			lexer->skip();
			closed=true;
			break;
		}else{
//			lexer->error("Illegal character in Inline Script");
			break;
		}
	}
	if (!closed)
		lexer->error(RC.S(ERR_COMPILER_SCRIPT_SUBST_NOT_CLOSED));

	return new TKVMCodeInlineScript(tmplist);
}

//-------------------------------------------------------------------------
// ブロック ( '(' WS Statement ')' )
// 必ず次に'('が来なければならない
// NULLを返すことがある。
TKVMCode_base *TKawariCompiler::compileBlock(void) {
	if (lexer->peek(ID_MODE)!=(int)'('){
		lexer->error(RC.S(ERR_COMPILER_INTERNAL_BLOCK));
		lexer->getRestOfLine();
		return NULL;
	}
	lexer->skip();
	Token::Type t=lexer->skipWS(BLOCK_MODE);

	if (t==(int)')'){
		// いきなり終了
		lexer->skip();
		return NULL;
	}

	TKVMCode_base *code=compileStatement(false, BLOCK_MODE);

	t=lexer->skipWS(BLOCK_MODE);
	if (t==(int)')'){
		// 終了
		lexer->skip();
	}else{
		lexer->error(RC.S(ERR_COMPILER_BLOCK_NOT_CLOSED));
	}
	return code;
}


//--------------------------------------------------------------------------
// 添え字付きエントリ呼び出し簡易版
// ( EntryWord '[' WS Expr WS ']' )
// 先頭の'$'は既に消えている。
TKVMCode_base *TKawariCompiler::compileEntryIndexSubst(void){
	// ここはかなり例外的。エラーがあるといきなり抜ける。

	TKVMCode_base *tmp_id=compileEntryWord();
	if (!tmp_id){
		lexer->error(RC.S(ERR_COMPILER_INTERNAL_INDEX));
		return NULL;
	}

	Token::Type t=lexer->skipWS(ID_MODE);
	if (t!=(int)'['){
		lexer->error(RC.S(ERR_COMPILER_INDEX_BRACKET_NOT_FOUND));
		delete tmp_id;
		return NULL;
	}

	TKVMCode_base *tmp_expr=compileExprSubst();
	if (!tmp_expr){
		lexer->error(RC.S(ERR_COMPILER_INDEX_EXPR_NOT_FOUND));
		delete tmp_id;
		return NULL;
	}

	return new TKVMCodeEntryIndex(tmp_id, tmp_expr);
}

//--------------------------------------------------------------------------
// エントリ名 ( (IdLiteral | Subst)+ WS )
TKVMCode_base *TKawariCompiler::compileEntryWord(void){
	vector<TKVMCode_base *> tmplist;

	lexer->skipWS();
	while (lexer->hasNext()){
		Token::Type t=lexer->peek(ID_MODE);
		if (t==Token::T_LITERAL){
			TKVMCode_base *code=new TKVMCodeIDString(lexer->getLiteral(ID_MODE));
			tmplist.push_back(code);
		}else if (t==(int)'$'){
			tmplist.push_back(compileSubst());
		}else{
			break;
		}
	}
	if (!tmplist.size()){
		return NULL;
	}else if (tmplist.size()==1){
		return tmplist[0];
	}else{
		return new TKVMCodeList(tmplist);
	}
}

//=====================================================================
// 式
//=====================================================================

// 式 ( '[' Expression  ']' )
// NULLが返るかも。
TKVMCode_base *TKawariCompiler::compileExprSubst(void){
	if (lexer->peek(ID_MODE)!=(int)'['){
		lexer->error(RC.S(ERR_COMPILER_INTERNAL_EXPR));
		lexer->getRestOfLine();
		return NULL;
	}
	lexer->skip();
	TKVMExprCode_base *code=compileExpr0();
	if (!code) {
		lexer->simpleSkipTo(']');
		lexer->skip();
		return NULL;
	}

	TKVMCodeExpression *ret=new TKVMCodeExpression(code);

	if (lexer->peek(ID_MODE)!=(int)']'){
		lexer->error(RC.S(ERR_COMPILER_EXPR_NOT_CLOSED));
	}else{
		lexer->skip();
	}
	return ret;
}

// 論理和 ( '||' )
TKVMExprCode_base *TKawariCompiler::compileExpr0(void){
	TKVMExprCode_base *l=compileExpr1();
	if (!l) return NULL;
	while(true){
		lexer->skipWS();
		Token token=lexer->next(ID_MODE);
		if (token.str=="||"){
			TKVMExprCode_base *r=compileExpr1();
			if (!r) { lexer->error(RC.S(ERR_COMPILER_PARSE_ERROR_AFTER)+"'||'"); return l; }
			l=new TKVMExprCodeLOR(l, r);
		}else{
			lexer->UngetChars(token.str.size());
			return l;
		}
	}
}

// 論理積 ( '&&' )
TKVMExprCode_base *TKawariCompiler::compileExpr1(void){
	TKVMExprCode_base *l=compileExpr2();
	if (!l) return NULL;
	while(true){
		lexer->skipWS();
		Token token=lexer->next(ID_MODE);
		if (token.str=="&&"){
			TKVMExprCode_base *r=compileExpr2();
			if (!r) { lexer->error(RC.S(ERR_COMPILER_PARSE_ERROR_AFTER)+"'&&'"); return l; }
			l=new TKVMExprCodeLAND(l, r);
		}else{
			lexer->UngetChars(token.str.size());
			return l;
		}
	}
}

// 等式 ( '=' | '==' | '!=' | '=~' | '!~" )
TKVMExprCode_base *TKawariCompiler::compileExpr2(void){
	TKVMExprCode_base *l=compileExpr3();
	if (!l) return NULL;
	lexer->skipWS();
	Token token=lexer->next(ID_MODE);
	if ((token.str=="=")||(token.str=="==")){
		TKVMExprCode_base *r=compileExpr3();
		if (!r) { lexer->error(RC.S(ERR_COMPILER_PARSE_ERROR_AFTER)+"'=='"); return l; }
		return new TKVMExprCodeEQ(l, r);
	}else if (token.str=="!="){
		TKVMExprCode_base *r=compileExpr3();
		if (!r) { lexer->error(RC.S(ERR_COMPILER_PARSE_ERROR_AFTER)+"'!='"); return l; }
		return new TKVMExprCodeNEQ(l, r);
	}else if (token.str=="=~"){
		TKVMExprCode_base *r=compileExpr3();
		if (!r) { lexer->error(RC.S(ERR_COMPILER_PARSE_ERROR_AFTER)+"'=~'"); return l; }
		return new TKVMExprCodeMATCH(l, r);
	}else if (token.str=="!~"){
		TKVMExprCode_base *r=compileExpr3();
		if (!r) { lexer->error(RC.S(ERR_COMPILER_PARSE_ERROR_AFTER)+"'!~'"); return l; }
		return new TKVMExprCodeNMATCH(l, r);
	}else{
		lexer->UngetChars(token.str.size());
		return l;
	}
}

// 比較 ( '<' | '<=' | '>' | '>=' )
TKVMExprCode_base *TKawariCompiler::compileExpr3(void){
	// 並列することはあり得ない
	TKVMExprCode_base *l=compileExpr4();
	if (!l) return NULL;
	lexer->skipWS();
	Token token=lexer->next(ID_MODE);
	if (token.str=="<"){
		TKVMExprCode_base *r=compileExpr4();
		if (!r) { lexer->error(RC.S(ERR_COMPILER_PARSE_ERROR_AFTER)+"'<'"); return l; }
		return new TKVMExprCodeLT(l, r);
	}else if (token.str=="<="){
		TKVMExprCode_base *r=compileExpr4();
		if (!r) { lexer->error(RC.S(ERR_COMPILER_PARSE_ERROR_AFTER)+"'<='"); return l; }
		return new TKVMExprCodeLTE(l, r);
	}else if (token.str==">"){
		TKVMExprCode_base *r=compileExpr4();
		if (!r) { lexer->error(RC.S(ERR_COMPILER_PARSE_ERROR_AFTER)+"'>'"); return l; }
		return new TKVMExprCodeGT(l, r);
	}else if (token.str==">="){
		TKVMExprCode_base *r=compileExpr4();
		if (!r) { lexer->error(RC.S(ERR_COMPILER_PARSE_ERROR_AFTER)+"'>='"); return l; }
		return new TKVMExprCodeGTE(l, r);
	}else{
		lexer->UngetChars(token.str.size());
		return l;
	}
}

// ビットOR ('|' | '^')
TKVMExprCode_base *TKawariCompiler::compileExpr4(void){
	TKVMExprCode_base *l=compileExpr5();
	if (!l) return NULL;
	lexer->skipWS();
	while(true){
		Token token=lexer->next(ID_MODE);
		if (token.str=="|"){
			TKVMExprCode_base *r=compileExpr5();
			if (!r) { lexer->error(RC.S(ERR_COMPILER_PARSE_ERROR_AFTER)+"'|'"); return l; }
			l=new TKVMExprCodeBOR(l, r);
		}else if (token.str=="^"){
			TKVMExprCode_base *r=compileExpr5();
			if (!r) { lexer->error(RC.S(ERR_COMPILER_PARSE_ERROR_AFTER)+"'~'"); return l; }
			l=new TKVMExprCodeBXOR(l, r);
		}else{
			lexer->UngetChars(token.str.size());
			return l;
		}
	}
}

// ビットAND ('&')
TKVMExprCode_base *TKawariCompiler::compileExpr5(void){
	TKVMExprCode_base *l=compileExpr6();
	if (!l) return NULL;
	while(true){
		lexer->skipWS();
		Token token=lexer->next(ID_MODE);
		if (token.str=="&"){
			TKVMExprCode_base *r=compileExpr6();
			if (!r) { lexer->error(RC.S(ERR_COMPILER_PARSE_ERROR_AFTER)+"'&'"); return l; }
			l=new TKVMExprCodeBAND(l, r);
		}else{
			lexer->UngetChars(token.str.size());
			return l;
		}
	}
}

// 加減算 ('+' | '-')
TKVMExprCode_base *TKawariCompiler::compileExpr6(void){
	TKVMExprCode_base *l=compileExpr7();
	if (!l) return NULL;
	while(true){
		lexer->skipWS();
		Token token=lexer->next(ID_MODE);
		if (token.str=="+"){
			TKVMExprCode_base *r=compileExpr7();
			if (!r) { lexer->error(RC.S(ERR_COMPILER_PARSE_ERROR_AFTER)+"'+'"); return l; }
			l=new TKVMExprCodePLUS(l, r);
		}else if (token.str=="-"){
			TKVMExprCode_base *r=compileExpr7();
			if (!r) { lexer->error(RC.S(ERR_COMPILER_PARSE_ERROR_AFTER)+"'-'"); return l; }
			l=new TKVMExprCodeMINUS(l, r);
		}else{
			lexer->UngetChars(token.str.size());
			return l;
		}
	}
}

// 乗除算 ('*' | '/' | '%')
TKVMExprCode_base *TKawariCompiler::compileExpr7(void){
	TKVMExprCode_base *l=compileExpr8();
	if (!l) return NULL;
	while(true){
		lexer->skipWS();
		Token token=lexer->next(ID_MODE);
		if (token.str=="*"){
			TKVMExprCode_base *r=compileExpr8();
			if (!r) { lexer->error(RC.S(ERR_COMPILER_PARSE_ERROR_AFTER)+"'*'"); return l; }
			l=new TKVMExprCodeMUL(l, r);
		}else if (token.str=="/"){
			TKVMExprCode_base *r=compileExpr8();
			if (!r) { lexer->error(RC.S(ERR_COMPILER_PARSE_ERROR_AFTER)+"'/'"); return l; }
			l=new TKVMExprCodeDIV(l, r);
		}else if (token.str=="%"){
			TKVMExprCode_base *r=compileExpr8();
			if (!r) { lexer->error(RC.S(ERR_COMPILER_PARSE_ERROR_AFTER)+"'%'"); return l; }
			l=new TKVMExprCodeMOD(l, r);
		}else{
			lexer->UngetChars(token.str.size());
			return l;
		}
	}
}

// 単項演算子 ('+' | '-' | '!' | '~')
TKVMExprCode_base *TKawariCompiler::compileExpr8(void){
	// 単項演算子自体は右結合
	lexer->skipWS();
	Token token=lexer->next(ID_MODE);
	if (token.str=="+"){
		TKVMExprCode_base *c=compileExpr8();
		if (!c) return NULL;
		return new TKVMExprCodeUPLUS(c);
	}else if (token.str=="-"){
		TKVMExprCode_base *c=compileExpr8();
		if (!c) return NULL;
		return new TKVMExprCodeUMINUS(c);
	}else if (token.str=="!"){
		TKVMExprCode_base *c=compileExpr8();
		if (!c) return NULL;
		return new TKVMExprCodeNOT(c);
	}else if (token.str=="~"){
		TKVMExprCode_base *c=compileExpr8();
		if (!c) return NULL;
		return new TKVMExprCodeCOMP(c);
	}else{
		lexer->UngetChars(token.str.size());
		return compileExpr9();
	}
}

// 累乗 ( '**' )
TKVMExprCode_base *TKawariCompiler::compileExpr9(void){
	TKVMExprCode_base *l=compileExprFactor();
	if (!l) return NULL;
	while (true){
		lexer->skipWS();
		Token token=lexer->next(ID_MODE);
		if (token.str=="**"){
			TKVMExprCode_base *r=compileExprFactor();
			if (!r) { lexer->error(RC.S(ERR_COMPILER_PARSE_ERROR_AFTER)+"'**'"); return l; }
			l=new TKVMExprCodePOW(l, r);
		}else{
			lexer->UngetChars(token.str.size());
			return l;
		}
	}
}

// Expr要素 ( '(' Expression ')' | ExprWord )
TKVMExprCode_base *TKawariCompiler::compileExprFactor(void){
	Token::Type t=lexer->skipWS(ID_MODE);
	if (t==(int)'('){
		lexer->skip();
		TKVMExprCode_base *c=compileExpr0();
		if (!c) return NULL;
		t=lexer->skipWS(ID_MODE);
		if (t!=(int)')'){
			lexer->error(RC.S(ERR_COMPILER_EXPR_BLOCK_NOT_CLOSED));
		}else{
			lexer->skip();
		}
		return new TKVMExprCodeGroup(c);
	}else{
		return compileExprWord();
	}
}

// Expr単語 ( (NumericLiteral | QuotedLiteral | Subst)+ WS )
TKVMExprCode_base *TKawariCompiler::compileExprWord(void){
	lexer->skipWS();
	vector<TKVMCode_base *> tmplist;
	bool escape=false;	// ループ脱出フラグ
	while (lexer->hasNext()&&(!escape)){
		Token::Type t=lexer->peek(ID_MODE);
		if ((t==Token::T_QLITERAL)||(t==Token::T_LITERAL)){
			string s;
			while (true){
				Token::Type t=lexer->peek(ID_MODE);
				if (t==Token::T_QLITERAL){
					s+=TKawariLexer::DecodeQuotedString(lexer->getQuotedLiteral());
				}else if (t==Token::T_LITERAL){
					// 十進数値しか許されない
					string tmps=lexer->getDecimalLiteral();
					if (!tmps.size()){
						lexer->error(RC.S(ERR_COMPILER_ILLCHAR_IN_EXPRESSION));
						escape=true;
						break;
					}
					s+=tmps;
				}else{
					break;
				}
			}
			TKVMCode_base *code=new TKVMCodeString(s);
			tmplist.push_back(code);
		}else if (t==(int)'$'){
			tmplist.push_back(compileSubst());
		}else{
			break;
		}
	}
	if (!tmplist.size()){
		return NULL;
	}else if (tmplist.size()==1){
		return new TKVMExprCodeWord(tmplist[0]);
	}else{
		return new TKVMExprCodeWord(new TKVMCodeList(tmplist));
	}
}

//=====================================================================
// エントリ集合演算式
//=====================================================================

//-------------------------------------------------------------------------
// エントリ呼び出し ( '{' EntryExpr '}' )
TKVMCode_base *TKawariCompiler::compileEntryCallSubst(void) {
	if (lexer->peek(ID_MODE)!=(int)'{'){
		lexer->error(RC.S(ERR_COMPILER_INTERNAL_ENTRYCALL));
		lexer->getRestOfLine();
		return NULL;
	}
	lexer->skip();
	if (lexer->skipWS(ID_MODE)==(int)'-'){
		// 負の数の履歴参照
		lexer->skip();
		string fnum=lexer->getDecimalLiteral();
		if (lexer->skipWS(ID_MODE)!=(int)'}'){
			lexer->error(RC.S(ERR_COMPILER_ENTRYCALL_NOT_CLOSED));
		}else{
			lexer->skip();
		}
		return new TKVMCodeHistoryCall(-1*atoi(fnum.c_str()));
	}

	TKVMSetCode_base *code=compileSetExpr0();

	if (lexer->peek(ID_MODE)!=(int)'}'){
		lexer->error(RC.S(ERR_COMPILER_ENTRYCALL_NOT_CLOSED));
	}else{
		lexer->skip();
	}

	if (!code) return NULL;

	// 内容チェック
	const TKVMSetCodeWord *cw=dynamic_cast<const TKVMSetCodeWord *>(code);
	if (cw){
		const TKVMCodeIDString *cs=cw->GetIfPVW();
		if (cs){
			if (IsInteger(cs->s)){
				// 履歴参照
				TKVMCode_base *ret=new TKVMCodeHistoryCall(atoi(cs->s.c_str()));
				delete code;
				return ret;
			}else{
				// 純粋仮想単語
				TKVMCode_base *ret=new TKVMCodePVW(cs->s);
				delete code;
				return ret;
			}
		}
	}

	return new TKVMCodeEntryCall(code);
}

// 和差 ('+' | '-')
TKVMSetCode_base *TKawariCompiler::compileSetExpr0(void){
	TKVMSetCode_base *l=compileSetExpr1();
	if (!l) return NULL;
	lexer->skipWS();
	Token token=lexer->next(ID_MODE);
	if (token.str=="+"){
		TKVMSetCode_base *r=compileSetExpr0();
		if (!r) { lexer->error(RC.S(ERR_COMPILER_PARSE_ERROR_AFTER)+"'+'"); return l; }
		return new TKVMSetCodePLUS(l, r);
	}else if (token.str=="-"){
		TKVMSetCode_base *r=compileSetExpr0();
		if (!r) { lexer->error(RC.S(ERR_COMPILER_PARSE_ERROR_AFTER)+"'-'"); return l; }
		return new TKVMSetCodeMINUS(l, r);
	}else{
		lexer->UngetChars(token.str.size());
		return l;
	}
}

// 積 ('&')
TKVMSetCode_base *TKawariCompiler::compileSetExpr1(void){
	TKVMSetCode_base *l=compileSetExprFactor();
	if (!l) return NULL;
	lexer->skipWS();
	Token token=lexer->next(ID_MODE);
	if (token.str=="&"){
		TKVMSetCode_base *r=compileSetExpr1();
		if (!r) { lexer->error(RC.S(ERR_COMPILER_PARSE_ERROR_AFTER)+"'&'"); return l; }
		return new TKVMSetCodeAND(l, r);
	}else{
		lexer->UngetChars(token.str.size());
		return l;
	}
}

// 集合演算式要素 ( '(' EntryExpr ')' | EntryWord )
TKVMSetCode_base *TKawariCompiler::compileSetExprFactor(void){
	Token::Type t=lexer->skipWS(ID_MODE);
	if (t==(int)'('){
		lexer->skip();
		TKVMSetCode_base *c=compileSetExpr0();
		if (!c) return NULL;
		t=lexer->skipWS(ID_MODE);
		if (t!=(int)')'){
			lexer->error(RC.S(ERR_COMPILER_SETEXPR_BLOCK_NOT_CLOSED));
		}else{
			lexer->skip();
		}
		return c;
	}else{
		return compileSetExprWord();
	}
}

// 集合演算式単語 ( (IdLiteral | Subst)+ WS )
TKVMSetCode_base *TKawariCompiler::compileSetExprWord(void){
	vector<TKVMCode_base *> tmplist;

	lexer->skipWS();
	while (lexer->hasNext()){
		Token::Type t=lexer->peek(ID_MODE);
		if (t==Token::T_LITERAL){
			TKVMCode_base *code=new TKVMCodeIDString(lexer->getLiteral(ID_MODE));
			tmplist.push_back(code);
		}else if (t==(int)'$'){
			tmplist.push_back(compileSubst());
		}else{
			break;
		}
	}
	if (!tmplist.size()){
		return NULL;
	}else if (tmplist.size()==1){
		return new TKVMSetCodeWord(tmplist[0]);
	}else{
		return new TKVMSetCodeWord(new TKVMCodeList(tmplist));
	}
}
//---------------------------------------------------------------------------
