#include	"satori.h"

#ifdef POSIX
/* POSIXではstricmpは定義されていない。代わりにstrcasecmpが使える。 */
#  define stricmp strcasecmp
#  include <string.h>
#endif

int	Satori::request(
	const string& i_protocol,
	const string& i_protocol_version,
	const string& i_command,
	const strpairvec& i_data,
	
	string& o_protocol,
	string& o_protocol_version,
	strpairvec& o_data)
{
	//-------------------------------------------------
	// リクエスト単位のクラスメンバを初期化

	mRequestMap.clear();
	mRequestID = "";
	mReferences.clear();
	mReferences.resize(8); // 最小値

	// 引数をクラスメンバに設定
	mRequestCommand = i_command;
	mRequestType = i_protocol;
	mRequestVersion = i_protocol_version;
	mStatusLine = i_command + " " + i_protocol + "/" + i_protocol_version;

	// 返すプロトコルのデフォルト値
	o_protocol = "SHIORI";
	o_protocol_version = "3.0";

	// プロトコルを判別
	if ( i_protocol=="SAORI" && i_protocol_version[0]>='1' )
	{
		o_protocol = i_protocol;
		o_protocol_version = "1.0";
		mRequestMode = SAORI;
	}
	else if ( i_protocol=="SHIORI" && i_protocol_version[0]>='3' )
	{
		mRequestMode = SHIORI3;
	}
	else if ( i_protocol=="SHIORI" && i_protocol_version[0]=='2' )
	{
		mRequestMode = SHIORI2;
			// 2.xにもバージョンとしては3.0を返す
	}
	else if ( i_protocol=="MAKOTO" && i_protocol_version[0]>='2' )
	{
		o_protocol = i_protocol;
		o_protocol_version = "2.0";
		mRequestMode = MAKOTO2;
	}
	else if ( i_protocol=="UNKNOWN" )
	{
		mRequestMode = UNKNOWN;
	}
	else
	{
		// 未対応のプロトコルだった。
		return	400;
	}

	// データ部をmRequestMapに格納。
	// SHOIRI/3.0以外のプロトコルの場合、SHOIRI/3.0に変換を行う。
	for ( strpairvec::const_iterator it = i_data.begin() ; it != i_data.end() ; ++it )
	{
		string key = it->first;
		const string& value = it->second;

		switch ( mRequestMode ) {
		case SAORI:
			if ( compare_head(key, "Argument") ) {
				int	n = stoi(key.c_str()+8);
				if ( n==0 )
					key = "ID";
				else
					key = "Reference" + itos(n-1);
			}
			break;
		case SHIORI2:	// こっちはてきとー
			if ( key=="Event" )
				key="ID";
			break;
		case MAKOTO2:
			if ( key=="String" )
				key="Reference0";
			break;
		default:
			break;
		}

		mRequestMap[key] = value;
		if ( compare_head(key, "Reference") ) {
			int	n = stoi(key.c_str()+9);
			if ( n>=0 && n<65536 ) {
				if ( n>=mReferences.size() )
					mReferences.resize(n+1);
				mReferences[n]=value;
			}
		}
	}

	if ( mRequestMode == MAKOTO2 )
	{
		mRequestMap["ID"] = "OnMakoto";
	}

	mRequestID = mRequestMap["ID"];
	mIsMateria = ( mRequestMap["Sender"] == "embryo" );


	//-------------------------------------------------
	// リクエストを解釈

	if ( mRequestCommand=="GET Version" )
	{
		if ( mRequestMode == SHIORI2 )
		{
			o_data.push_back( strpair("ID", gSatoriName) );
			o_data.push_back( strpair("Craftman", gSatoriCraftman) );
			o_data.push_back( strpair("Version", gSatoriVersion) );
		}
		return 200;
	}


	// 選択分岐記録を変数に代入。ref0を元に戻す。
	if ( compare_head(mRequestID, "OnChoice") ) // OnChoiceSelect/OnChoiceEnterの両方
	{
		strvec	vec;
		int	ref_no = ( mRequestID=="OnChoiceEnter" )?1:0;
		string&	info = mRequestMap[string("Reference")+itos(ref_no)];
		if ( split(info, byte1_dlmt, vec)==3 ) // \1区切りの３文字列であるならば
		{
			info=mReferences[ref_no]=variables["選択ＩＤ"]=vec[0];
			variables["選択ラベル"]=vec[1];
			variables["選択番号"]=vec[2];
		}
	}

	// ログについて色々
	bool	logmode = !( mRequestID=="OnSurfaceChange" || mRequestID=="OnSecondChange"
		|| mRequestID=="OnMouseMove" || mRequestID=="OnTranslate" 
		/*|| compare_tail(mRequestID, "caption")*/ || compare_tail(mRequestID, "visible")
		|| compare_head(mRequestID, "menu.") || mRequestID.find(".color.")!=string::npos );

	if(fRequestLog && logmode)
	{
		sender << "--- Request ---" << endl << mStatusLine << endl; // << iRequest << endl;
		for(strmap::iterator i=mRequestMap.begin() ; i!=mRequestMap.end() ; ++i)
			if ( !i->first.empty() && !i->second.empty()
				&& i->first!="SecurityLevel" 
				&& i->first!="Sender" 
				&& i->first!="Charset" )
				sender << i->first << ": " << i->second << endl;
	}

	// せきゅあ？
	strmap::const_iterator it = mRequestMap.find("SecurityLevel");
	secure_flag = ( it!=mRequestMap.end() && stricmp(it->second.c_str(), "local")==0 );

	// メイン処理
	Sender::validate( fOperationLog && logmode );
	sender << "--- Operation ---" << endl;
	int	status_code = CreateResponse(mResponseMap);

	// 後処理１
	default_surface = next_default_surface;

	//--------------------------------------------------------------------

	// Valueに対する最終処理
	if ( status_code==200 ) {	// && compare_head(mRequestID, "On")
		strmap::iterator i = mResponseMap.find("Value");
		if ( i!=mResponseMap.end() ) {
			if ( !Translate(i->second) ) {
				status_code = 204;
				mResponseMap.erase(i);
			} 
			else {
				second_from_last_talk = 0;
				if ( compare_head(mRequestID, "On") ) {
					mResponseHistory.push_front(i->second);
					if ( mResponseHistory.size() >= RESPONSE_HISTORY_SIZE )
						mResponseHistory.pop_back();
				}
			}
		}
	}

	sender << "status code : " << itos(status_code) << endl;

	//--------------------------------------------------------------------

	for(strmap::const_iterator i=mResponseMap.begin() ; i!=mResponseMap.end() ; ++i)
	{
		string	key=i->first, value=i->second;

		switch ( mRequestMode ) {
		case SAORI:
			if ( key=="Value" )
				key = "Result";
			else if ( compare_head(key, "Reference") )
				key = string() + "Value" + (key.c_str()+9);
			break;
		case SHIORI2:
			if ( key=="Value" )
				key = "Sentence";
			break;
		case MAKOTO2:
			if ( key=="Value" )
				key = "String";
			break;
		default:
			break;
		}
		o_data.push_back( strpair(key, value) );
	}

	Sender::validate();
	if(fResponseLog && logmode)
	{
		sender << "--- Response ---" << endl << mResponseMap << endl;
	}
	mResponseMap.clear();

	//--------------------------------------------------------------------

	// リロード処理
	if ( reload_flag )
	{
		reload_flag = false;
		string	tmp = mBaseFolder;
		sender << "■■reloading." << endl;
		unload();
		load(tmp);
		sender << "■■reloaded." << endl;
	}

	return	status_code;
}
