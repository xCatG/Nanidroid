//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// コンパイラ(電次元
//
//      Programed by NAKAUE.T (Meister)
//
//  2002.03.18                KIUに合わせてTKawariCompiler分離
//                            ・・・でも、インターフェースがちょっと違う(涙)
//  2002.04.18  Phase 8.0.0   走るカウンタック。コンパイラ特集。
//
//---------------------------------------------------------------------------
#ifndef COMPILER_H__
#define COMPILER_H__
//---------------------------------------------------------------------------
#include <string>
#include <vector>
//---------------------------------------------------------------------------
class TKVMCode_base;		// 中間コード
class TKVMExprCode_base;	// 式中間コード
class TKVMSetCode_base;		// 集合演算式中間コード
//-------------------------------------------------------------------------
//
// コンパイラ特集
//
//-------------------------------------------------------------------------
class TKawariCompiler {
public:
	//=====================================================================
	// 公開メソッド
	//=====================================================================

	enum Mode {
		M_DICT,		// エントリ定義モード
		M_KIS,		// KISモード
		M_END,		// モードを1つ終了
		M_UNKNOWN,	// ???
		M_EOF		// EOF
	};

	// コンストラクタ
	TKawariCompiler(std::istream &is, class TKawariLogger &lgr, const std::string &filename, bool pp=true);

	// デストラクタ
	~TKawariCompiler();

	// 次モードを得る
	Mode GetNextMode(void);

	// エントリ定義を読む
	// 戻り値 : ファイルの終了かモード切り替えでfalse. それ以外はtrue.
	bool LoadEntryDefinition(std::vector<std::string> &entries, std::vector<TKVMCode_base *> &sentences);

	// １つのKIS定義複文を全て読む
	TKVMCode_base *LoadInlineScript(void);


	// 文字列をStatementとして中間コードへコンパイル
	static TKVMCode_base *Compile(const std::string &src, class TKawariLogger &logger);

	// 文字列を、そのままStringとして中間コード化する
	static TKVMCode_base *CompileAsString(const std::string &src);

	// 文字列を集合演算式('$['召]'の中身)としてコンパイル
	static TKVMSetCode_base *CompileAsEntryExpression(const std::string &src, class TKawariLogger &logger);

	// 文字列をエントリ名で使用可能な文字列にエンコードする
	static std::string EncodeEntryName(const std::string &orgsen);

private:
	// 字句解析器
	class TKawariLexer *lexer;

	// ロガー
	class TKawariLogger &logger;

	//=====================================================================
	// 非公開メソッド
	//=====================================================================

	// エントリ名並び ( IdLiteral S | IdLiteral S ',' S EntryIdList )
	// return : 得られたエントリ名の数
	int compileEntryIdList(std::vector<std::string> &list);

	// 改行不可文並び。(注意)改行コードを読み込んで終了。
	// return : 得られた文の数
	int compileNRStatementList(std::vector<TKVMCode_base *> &list);

	// 可文並び。')'で終了。
	// return : 得られた文の数
	int compileStatementList(std::vector<TKVMCode_base *> &list);

	//=====================================================================
	// 単語

	// 文 ( (Word WS)* )
	// 要素数が０の時もインスタンスを返す。これは$(NULL)に相当する。
	// 
	// noret : 改行を許さない場合true.
	// mode : TKawariLexer::Modeと同値。デフォルトはエントリ定義モード。
	TKVMCode_base *compileStatement(bool noret, int mode=1);

	// 文字列 ( Literal )にはコンパイラは存在しない。

	// 単語 ( (Literal | Block | Subst )* )
	// 要素数が０だった場合、NULLを返す。
	// mode : TKawariLexer::Modeと同値
	TKVMCode_base *compileWord(int mode);

	// 置換 ( '$' ( EntryCallSubst | EntryIndexSubst | InlineScriptSubst | ExprSubst ) )
	// 必ず先頭に'$'が来ること。
	TKVMCode_base *compileSubst(void);

	// スクリプト文 ( WS ( Word WS ) * )
	TKVMCode_base *compileScriptStatement(void);

	// インラインスクリプト ( '(' ScriptStatementSeq ') )
	TKVMCode_base *compileInlineScriptSubst(void);

	// ブロック ( '(' WS Statement ')' )
	// 要素数が０の時もインスタンスを返す。これは$(NULL)に相当する。
	TKVMCode_base *compileBlock(void);

	// 添え字付きエントリ呼び出し簡易版
	// ( ( EntryName | Subst ) '[' WS Expr WS ']' )
	TKVMCode_base *compileEntryIndexSubst(void);

	// エントリ名 ( (IdLiteral | Subst)+ WS )
	TKVMCode_base *compileEntryWord(void);

	//=====================================================================
	// スクリプト構文

	// if
	TKVMCode_base *compileScriptIF(void);


	//=====================================================================
	// 式

	// 式 ( '[' Expression  ']' )
	TKVMCode_base *compileExprSubst(void);

	// 論理和 ( '||' )
	TKVMExprCode_base *compileExpr0(void);

	// 論理積 ( '&&' )
	TKVMExprCode_base *compileExpr1(void);

	// 等式 ( '=' | '==' | '!=' )
	TKVMExprCode_base *compileExpr2(void);

	// 比較 ( '<' | '<=' | '>' | '>=' )
	TKVMExprCode_base *compileExpr3(void);

	// ビットOR ('|' | '^')
	TKVMExprCode_base *compileExpr4(void);

	// ビットAND ('&')
	TKVMExprCode_base *compileExpr5(void);

	// 加減算 ('+' | '-')
	TKVMExprCode_base *compileExpr6(void);

	// 乗除算 ('*' | '/' | '%')
	TKVMExprCode_base *compileExpr7(void);

	// 単項演算子 ('+' | '-' | '!' | '~')
	TKVMExprCode_base *compileExpr8(void);

	// 累乗 ( '**' )
	TKVMExprCode_base *compileExpr9(void);

	// Expr要素 ( '(' Expression ')' | ExprWord )
	TKVMExprCode_base *compileExprFactor(void);

	// Expr単語 ( DecimalLiteral WS | (QuotedLiteral | Subst)+ WS )
	TKVMExprCode_base *compileExprWord(void);

	//=====================================================================
	// エントリ集合演算式

	// エントリ呼び出し ( '{' EntryExpr '}' )
	TKVMCode_base *compileEntryCallSubst(void);

	// 和差 ('+' | '-')
	TKVMSetCode_base *compileSetExpr0(void);

	// 積 ('&')
	TKVMSetCode_base *compileSetExpr1(void);

	// 集合演算式要素 ( '(' EntryExpr ')' | EntryWord )
	TKVMSetCode_base *compileSetExprFactor(void);

	// 集合演算式単語 ( (IdLiteral | Subst)+ WS )
	TKVMSetCode_base *compileSetExprWord(void);
};
//---------------------------------------------------------------------------
#endif // COMPILER_H__
