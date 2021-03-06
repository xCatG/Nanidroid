/*---------------------------------------------------------------------------

	『里々（さとり）』

---------------------------------------------------------------------------*/
#ifndef SATORI_H
#define SATORI_H


//---------------------------------------------------------------------------
// 汎用のツール類
#include	"../_/stltool.h"
#include	"../_/simple_stack.h"

// れしばへの送信
#include	"../_/sender.h"

// SAORI関連
#include	"shiori_plugin.h"

// SakuraDLLとして呼び出されるための構造
#include	"SakuraDLLHost.h"

//---------------------------------------------------------------------------
// 辞書を格納する構造

typedef string Word;
typedef strvec Talk;

#include "Families.h"
class AllWords : public Families<Word> {};
class AllTalks : public Families<Talk> {};

//---------------------------------------------------------------------------
#ifdef	_DEBUG
	#define	DBG(a)	a
#else
	#define	DBG(a)	NULL
#endif	//	_DEBUG

//---------------------------------------------------------------------------
// 定数的な

static const char	ret_dlmt[3] = { 13, 10, 0 };
static const char	byte1_dlmt[2] = { 1, 0 };

// バージョン文字列とか
extern const char* gSatoriName;
extern const char* gSatoriNameW;
extern const char* gSatoriCraftman;
extern const char* gSatoriCraftmanW;
extern const char* gSatoriVersion;
extern const char* gShioriVersion;
extern const char* gSaoriVersion;

static const int RESPONSE_HISTORY_SIZE=64;

//---------------------------------------------------------------------------

class escaper
{
	// エスケープ後文字。
	// 置き換え機能を持たないカッコは、一旦これ+id+半角空白に置き換える。
	// 文字コード上はsjisの一文字として扱われるが、該当する文字は無い。ダメな案。
	static const char sm_escape_sjis_code[3];

	//map<string, int> m_str2id;
	vector<string> m_id2str;

public:
	// 引数文字列を受け取り、メンバに格納し、「エスケープされた文字列」を返す。
	string insert(const string& i_str);
	// 対象文字列中に含まれる「エスケープされた文字列」を元に戻す。
	void unescape(string& io_str);
	// メンバをクリア
	void clear();
};

//---------------------------------------------------------------------------
class Satori : public Evalcator, public SakuraDLLHost
{

private:
	string	mBaseFolder;	// satori.dllの存在するフォルダ
	string	mExeFolder;		// 本体.exeの存在するフォルダ

	// リクエスト内容
	string	mStatusLine;	// リクエストの一行目
	strmap	mRequestMap;	// : セパレートされてたkey:value
	string	mRequestID;		// SHIORI/3.0 ID
	string	mRequestCommand;	// GET, NOTIFY, ...
	string	mRequestType;		// SHIORI / SAORI / MAKOTO
	string	mRequestVersion;	// 1.0, 2.x, 3.0
	bool	mIsMateria;	// まてりあは特殊処理が要る
	strvec	mReferences;
	enum { SAORI, SHIORI2, SHIORI3, MAKOTO2, UNKNOWN } mRequestMode;
	// 格納されたメンバからResponseを作成。返値はステータスコード。
	int		CreateResponse(strmap& oResponse);
	// SHIORI/3.0 IDがOnで始まってた場合、CreateResponseから呼ばれる
	int		EventOperation(string iEvent, map<string,string> &oResponse);

	// 戻り値map
	strmap mResponseMap;

	// 置き換え辞書
	strmap	replace_before_dic;
	strmap	replace_after_dic;

	inimap	mCharacters;	// characters.iniの内容

	// 文字列のエスケープと書き戻し
	escaper m_escaper;

	// SAORI/内部命令を呼び出す際の引数区切りとなる文字列群
	set<string>	mDelimiters;

	// 全ての＊トーク
	AllTalks	talks;
	// ＠定義 ...ジャンル分けされた複数の単語。
	AllWords	words;


	// ＄変数
	strmap	variables;
	// 自動アンカー
	set<string>	anchors;

	// 変数の消去。何かと問題があるらしいよ？
	void	erase_var(const string& key);

	// 動的に登録された単語。wordsにも収録する。satori_savedata.txtに保存するのが目的。
	map<string, vector<const Word*> >	mAppendedWords; // setでなくvectorなのは「重複回避：昇順/降順」に備えて。

	// 戻したトークの履歴
	deque<string>	mResponseHistory;

	// call引数stack 実装自体は再帰。
	simple_stack<strvec>	mCallStack;

	// 単語チェイン
	//map<string, set<string> >	mRelationalWord;	// <単語, <属性> > 
	//set<string>	mUsedRelation;	// <使用された属性> １トークでリセット。明示リセットも可。

	// 選択分岐の記録    map<ID, pair<NUMBER, LABEL> >　
	// よく考えたら、選択項目とるだけなら全く不要だった。まぁ全体取得できても悪くは無いけど
	map<string, pair<int, string> >	question_record;

	int	second_from_last_talk;	// 最後に喋ってからの経過時間

	// なでられ回数。keyはなでられ箇所、valueは回数。
	strintmap	nade_count;
	// なでられ有効期間
	int	nade_valid_time;
		// SecondChange毎に減少、３秒間moveがこないとnade_countを全クリア。
		// nade_countがnade_sensitivityを越えるとそのイベントが発生、nade_countを全クリア。
	bool	insert_nade_talk_at_other_talk;	// 喋ってる最中のなで反応有無
	int		nade_valid_time_initializer;	// なでられ持続秒数（なでセッションの期限）
	int		nade_sensitivity;				// なでられ我慢回数（発動までの回数）


	strintmap	koro_count;	// ころころ回数
	int	koro_valid_time;	// ころころ有効期間

	// 喋り間隔（秒）。０なら黙る。＄変数
	int	talk_interval;
	// 喋り間隔誤差（間隔に対する％）＄変数
	int	talk_interval_random;
	// 次回自発喋りまでの時間。喋るとリセット。＄変数
	int	talk_interval_count;
	// 自動挿入ウェイトの倍率。省略時100。
	int	rate_of_auto_insert_wait;

	// 付加文字列
	string	append_at_scope_change;
	string	append_at_scope_change_with_sakura_script;
	string	append_at_talk_start;
	string	append_at_talk_end;


	// しゃべり管理。SentenceToSakuraScriptの未再帰呼び出し時に初期化。
	int		speaker;		// 話者
	set<int>	speaked_speaker;		// 少しでも喋った？
	bool	is_speaked(int n) { return speaked_speaker.find(n) != speaked_speaker.end(); }
	bool	is_speaked_anybody() { return speaked_speaker.size()>0; }
	int		characters;
	int		question_num;
	set<int>	surface_changed_before_speak;	// 会話前にサーフェスが切り替え指示があった？


	// 過去のカッコ置き換えを記憶。反復（Ｈ？）で使用
	// SentenceToSakuraScriptの再帰に同期する。
	simple_stack<strvec>	kakko_replace_history;	

	// 会話時サーフェス戻し・＄変数
	bool	surface_restore_at_talk;
	map<int, int>	default_surface;
	map<int, int>	surface_add_value;
	map<int, int>	next_default_surface; // 途中でdef_surfaceを切り換えても、そのrequestでは使わない
	string	surface_restore_string();

	// ばるーん位置
	map<int, bool>	validBalloonOffset;	// 1回でも設定されたら有効 つーか片方だけだと意味無かった。むぅ。
	map<int, string>	BalloonOffset;

	// 時間系情報取得用
#ifdef POSIX
	unsigned long tick_count_at_load, tick_count_total;
#else
	DWORD	tick_count_at_load, tick_count_total;
#endif
	
	// サーフェス
	map<int, int>	cur_surface;
	map<int, int>	last_talk_exiting_surface;
	// 毎秒更新される「状態」
	bool	mikire_flag,kasanari_flag,can_talk_flag;

	// COMMUNICATE周り
#ifdef POSIX
	bool updateGhostsInfo() { return true; } // 何もしない。
#else
	bool	updateGhostsInfo();	// FMOから情報取得
#endif
	vector<strmap>	ghosts_info;	// FMOの内容そのまま。0は自分自身、1〜は順番どおり。
	strmap*	find_ghost_info(string name);	// ghosts_infoを検索、特定のゴーストの情報を得る

	string	mCommunicateFor;	// 話しかけ対象ゴースト。→で設定されresponseにToをつける
	set<string>	mCommunicateLog;	// 会話ログ。繰り返しがあった場合は会話打ち切り

	// 各セクションのログ吐き有無。
	bool	fRequestLog, fOperationLog, fResponseLog;

	// 変数によりリロードが指示されたらON。
	bool	reload_flag;
	// 辞書フォルダ
	strvec	dic_folder;

	// TEACHされる変数名
	string	teach_genre;

	// タイマ名：発話までの秒数
	strintmap	timer;

	// 計算時は対象のないカッコを０に置き換える。そのためのフラグ。
	bool	unkakko_for_calcurate;

	// トークの予約
	map<int, string>	reserved_talk;

	// 「独自イベントによるmateriaイベントの置き換え」のためのスクリプト入れ
	string	on_loaded_script;
	string	on_unloading_script;

	// 栞プラグイン
	ShioriPlugins	mShioriPlugins;
	string	inc_call(const string&, const strvec&, strvec&, bool is_secure);

	// 安全？
	bool	secure_flag;

	// 直前の表示選択肢
	string	last_choice_name;

	// セーブデータ保存時の暗号化有無
	bool	fEncodeSavedata;
	// さおり引数を計算するか
	enum { SACM_ON, SACM_OFF, SACM_AUTO } mSaoriArgumentCalcMode;

	// 自動セーブ間隔
	int	mAutoSaveInterval;
	int	mAutoSaveCurrentCount;

	// 辞書情報
	int	numWord, numParentheres, numSurfaceChange,
		numDictionary, numDictionarySize;

	// システム情報系 ----------

	enum { UNDEFINED, WIN95, WIN98, WINME, WINNT, WIN2K, WINXP } mOSType;

#ifndef POSIX
	// マルチモニタ
	bool	is_single_monitor;	// 最上位。これがfalseならば以下を使ってはいけない
	RECT	desktop_rect;
	RECT	max_screen_rect;
	map<int, HWND>	characters_hwnd;
#endif

	// メンバ関数

	void	InitMembers();

	bool	LoadDicFolder(const string& path);
	bool	LoadDictionary(const string& filename);

	string	GetWord(const string& name);

	string	KakkoSection(const char*& p);
	string	UnKakko(const char* p);

	bool	GetURLList(const string& name, string& result);
	bool	GetRecommendsiteSentence(const string& name, string& result);

	// 指定イベント無い場合、イベント名の置き換えもしてioeventを返す。それでも無いならfalse
	bool	FindEventTalk(string& ioevent);

	// Communicate形式検索。該当なしならfalse。and_modeがtrueなら全単語一致以外は無効とする
	bool	TalkSearch(const string& iSentence, string& oScript, bool iAndMode);

	// システム変数設定時の動作
	bool	system_variable_operation(string key, string value, string* result=NULL);

	// 内部。返値は続行の有無。続行時はSentenceNameをGetSentence。
	bool	GetSentence(string& ioSentenceName, string& oResultScript);

	// 式を評価し、結果の真偽値を返す
	bool evalcate_to_bool(const Condition& i_cond);

public:

	Satori() {}
	~Satori() {}

	// SHIORI/3.0インタフェース
	virtual bool load(const string& i_base_folder);
	virtual bool unload();
	virtual int	request(
		const string& i_protocol,
		const string& i_protocol_version,
		const string& i_command,
		const strpairvec& i_data,
		
		string& o_protocol,
		string& o_protocol_version,
		strpairvec& o_data);

	// 変数等のデータをファイルに保存
	bool	Save(bool isOnUnload=false);	

	// strvecからさくらスクリプトを生成する
	string	SentenceToSakuraScript(const strvec& vec);
	// strvecにプリプロセスを掛けた後、さくらスクリプトを生成する。さとりて用
	string	SentenceToSakuraScript_with_PreProcess(const strvec& vec);
	// 指定された名前の＊文を取得する
	string	GetSentence(const string& name);
	// 引数に渡されたものを何かの名前であるとし、置き換え対象があれば置き換える。
	bool	Call(const string& word, string& result);
	string	Call(const string& word) { string result; Call(word, result); return result; }
	// 里々レベルでの計算を行う。戻り値は成否。
	bool calculate(const string& iExpression, string& oResult);

	// 最終置き換え処理。置換後のスクリプトが中身が無い（実行してもしなくても一緒）と判断したらfalseを返す。
	bool	Translate(string& script);

};

//---------------------------------------------------------------------------
string	int2zen(int i);
string	zen2han(string str);
bool	calc(string&);
void	diet_script(string&);
#ifndef POSIX
int		random();
#endif




//---------------------------------------------------------------------------
#endif


