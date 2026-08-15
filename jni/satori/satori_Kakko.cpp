#include	"satori.h"
#include	"../_/Utilities.h"
#ifdef POSIX
#  include      "posix_utils.h"
#endif
#include	<time.h>


void	add_characters(const char* p, int& characters) {
	// さくらスクリプトとそれ以外を分割して処理を加える
	while (*p) {
		if (*p=='\\'||*p=='%') {
			++p;
			if (*p=='\\'||*p=='%')	// エスケープされた\, %
				continue;
			while (!_ismbblead(*p) && (isalpha(*p)||isdigit(*p)||*p=='!'||*p=='*'||*p=='&'||*p=='?'||*p=='_'))
				++p;
			if (*p=='[') {
				for (++p ; *p!=']' ;)
					if (p[0]=='\\' && p[1]==']')	// エスケープされた]
						++p;
					else
						p += _ismbblead(*p) ? 2 : 1;
			}
		}
		else {
			int len = _ismbblead(*p) ? 2 : 1;
			p += len;
			characters += len;
		}
	}
}

#ifndef POSIX
static	SYSTEMTIME	DwordToSystemTime(DWORD dw) {
	SYSTEMTIME	st = { 0, 0, 0, 0, 0, 0, 0, 0 };
	st.wMilliseconds=WORD(dw%1000); dw/=1000;
	st.wSecond=WORD(dw%60); dw/=60;
	st.wMinute=WORD(dw%60); dw/=60;
	st.wHour=WORD(dw);
	return	st;
}
#endif

string	Satori::inc_call(
	const string& iCallName, 
	const strvec& iArgv, 
	strvec& oResults, 
	bool iIsSecure) 
{
	if ( !iIsSecure ) {
		sender << "local/Local\x82\xC5\x82\xC8\x82\xA2\x82\xCC\x82\xC5\x8F\x52\x82\xE8\x82\xDC\x82\xB5\x82\xBD: " << iCallName << endl;
		return	"";
	}

	if ( iCallName=="set" ) {
		if ( iArgv.size()==2 ) {
			string	result, key=iArgv[0], value=iArgv[1];

			if ( aredigits(zen2han(key)) ) {
				sender << "\x81\x90" << key << "\x81\x40\x90\x94\x8E\x9A\x82\xCC\x82\xDD\x82\xCC\x95\xCF\x90\x94\x96\xBC\x82\xCD\x88\xB5\x82\xA6\x82\xDC\x82\xB9\x82\xF1." << endl;
				erase_var(key);	// 存在抹消
			}
			else if ( value=="" ) {
				sender << "\x81\x90" << key << "\x81\x5E" "cleared." << endl;
				erase_var(key);	// 存在抹消
			}
			else {
				sender << "\x81\x90" << key << "\x81\x81" << value << "\x81\x5E" << 
					(( variables.find(key) == variables.end() ) ?
					"writed." : "overwrited.")<< endl;

				variables[key] = value;
				system_variable_operation(key, value, &result);
			}
			return	result;
		}
	}
	else if ( iCallName=="loop" ) {
		int	init=1, max=0, step=1, arg_size=iArgv.size();
		if ( arg_size==2 )
			max=stoi(iArgv[1]);
		else if ( arg_size==3 ) {
			init=stoi(iArgv[1]);
			max=stoi(iArgv[2]);
		}
		else if ( arg_size==4 ) {
			init=stoi(iArgv[1]);
			max=stoi(iArgv[2]);
			step=stoi(iArgv[3]);
		}
		else
			return	"";
		string	name=iArgv[0];
		string	ret,temp;

		if ( step==0 )
			return	"";
		else if ( step>0 ) {
			if ( init>max )
				return	"";
			for (int i=init ; i<=max ; i+=step ) {
				variables[name+"\x83\x4A\x83\x45\x83\x93\x83\x5E"] = itos(i);
				if ( !Call(name, temp) )
					return	"";
				ret += temp;
			}
		}
		else {
			if ( init<max )
				return	"";
			for (int i=init ; i>=max ; i+=step ) {
				variables[name+"\x83\x4A\x83\x45\x83\x93\x83\x5E"] = itos(i);
				if ( !Call(name, temp) )
					return	"";
				ret += temp;
			}
		}
		variables.erase(name+"\x83\x4A\x83\x45\x83\x93\x83\x5E");
		return	ret;
	}
	else if ( iCallName=="sync" ) {
		string	str = "\\![raise,OnDirectSaoriCall";
		if ( !iArgv.empty() ) {
			string	arg;
			combine(arg, iArgv, ",");
			str += ",";
			str += arg;
		}
		str += "]";
		return	str;
	}
	else if ( iCallName=="remember" ) {
		if ( iArgv.size() == 1 ) {
			int	n = stoi(iArgv[0]);
			if ( mResponseHistory.size() > n )
				return	mResponseHistory[n];
		}
	}
	else if ( iCallName=="call" ) {
		if ( iArgv.size() >= 1 ) {
			mCallStack.push( strvec() );
			strvec&	v = mCallStack.top();
			for ( int i=1 ; i<iArgv.size() ; ++i )
				v.push_back( iArgv[i] );
			string	r = Call(iArgv[0]);
			mCallStack.pop();
			return	r;
		}
	}
	else if ( iCallName=="freeze" ) {


	}
	else if ( iCallName == "\x92\x50\x8C\xEA\x82\xCC\x92\xC7\x89\xC1" ) {

		if ( iArgv.size() == 2 )
		{
			Family<Word>* f = words.get_family(iArgv[0]);
			if ( f == NULL || false == f->is_exist_element(iArgv[1]) )
			{
				mAppendedWords[ iArgv[0] ].push_back( f->add_element(iArgv[1]) );
				sender << "\x92\x50\x8C\xEA\x8C\x51\x81\x75" << iArgv[0] << "\x81\x76\x82\xC9\x92\x50\x8C\xEA\x81\x75" << iArgv[1] << "\x81\x76\x82\xAA\x92\xC7\x89\xC1\x82\xB3\x82\xEA\x82\xDC\x82\xB5\x82\xBD\x81\x42" << endl;
			}
			else
			{
				sender << "\x92\x50\x8C\xEA\x8C\x51\x81\x75" << iArgv[0] << "\x81\x76\x82\xC9\x92\x50\x8C\xEA\x81\x75" << iArgv[1] << "\x81\x76\x82\xCD\x8A\xF9\x82\xC9\x91\xB6\x8D\xDD\x82\xB5\x82\xDC\x82\xB7\x81\x42" << endl;
			}
		}
		else
			sender << "error: '\x92\x50\x8C\xEA\x82\xCC\x92\xC7\x89\xC1' : \x88\xF8\x90\x94\x82\xAA\x95\x73\x90\xB3\x82\xC5\x82\xB7\x81\x42" << endl;

	}
	else if ( iCallName=="nop" ) {
	}
	return	"";
}


// 引数に渡されたものを何かの名前であるとし、置き換え対象があれば置き換える。
bool	Satori::Call(const string& iName, string& oResult) {
	string	hankaku;
	strvec*	p_kakko_replace_history = kakko_replace_history.empty() ? NULL : &(kakko_replace_history.top());

	bool	_pre_called_=false;

	// SAORI対応, 内蔵関数呼び出しもここで
	{
		string	thePluginName="";
		set<string>::const_iterator theDelimiter = mDelimiters.end();

		const char* p = NULL;
		enum { NO_CALL, SAORI_CALL, INC_CALL } state = NO_CALL;

		if ( mShioriPlugins.find(iName) ) {
			thePluginName=iName;
			state = SAORI_CALL;
		} else {

			static set<string> inner_commands;
			if ( inner_commands.empty() ) {
				// 本当はmap<name, function>だなー　むー
				inner_commands.insert("set");
				inner_commands.insert("nop");
				inner_commands.insert("sync");
				inner_commands.insert("loop");
				inner_commands.insert("remember");
				inner_commands.insert("\x92\x50\x8C\xEA\x82\xCC\x92\xC7\x89\xC1");
				inner_commands.insert("call");
				inner_commands.insert("freeze");
				//inner_commands.insert("単語の削除");
				//inner_commands.insert("単語の存在");
			}

			for (set<string>::const_iterator i=mDelimiters.begin() ; i!=mDelimiters.end() ; ++i) {
				p = strstr_hz(iName.c_str(), i->c_str());
				if ( p==NULL )
					continue;
				string	str(iName.c_str(), p-iName.c_str());
				if ( mShioriPlugins.find(str) ) {	// 存在確認
					thePluginName=str;
					theDelimiter=i;
					state = SAORI_CALL;
					break;
				}
				else if ( inner_commands.find(str)!=inner_commands.end() ) {
					thePluginName=str;
					theDelimiter=i;
					state = INC_CALL;
					break;
				}
			}
		}

		if ( state==NO_CALL ) {
			_pre_called_=false;
		}
		else
		{
			_pre_called_=true;
			strvec	theArguments, theResults;

			if ( p!=NULL )// 引数があるなら
			{
				assert(theDelimiter != mDelimiters.end());
				string argstr = UnKakko(p);

				while (true)
				{
					p += theDelimiter->size();
					const char* pdlmt = strstr_hz(p, theDelimiter->c_str());
					if ( pdlmt==NULL ) {
						theArguments.push_back(p);
						break;
					}
					theArguments.push_back( string(p,pdlmt-p) );
					p = pdlmt;
				}

				if ( mSaoriArgumentCalcMode!=SACM_OFF ) {
					for ( strvec::iterator i=theArguments.begin() ; i!=theArguments.end() ; ++i ) {
						if ( i->size()==0 )
							continue;
						if ( mSaoriArgumentCalcMode==SACM_AUTO ) {
							int	c = zen2han(*i).at(0);
							if ( c!='+' && c!='-' && !(c>='0' && c<='9') )
								continue;
						}

						bool calc(string&);	// declare
						string	exp = *i;
						if ( calc(exp) )
							*i=exp;
					}
				}
			}

			// 引数渡して返値を取得、と。
			if ( state==SAORI_CALL )
				oResult = mShioriPlugins.request(thePluginName, theArguments, theResults, secure_flag ? "Local" : "External" );
			else
				oResult = inc_call(thePluginName, theArguments, theResults, secure_flag);
			oResult = UnKakko(oResult.c_str());	// 返値を再度カッコ展開
			
			// 複数返値を変数にセット
			int	id=0;
			for ( strvec::iterator i=theResults.begin() ; i!=theResults.end() ; ++i ) {
				if ( i->size()==0 )
					variables.erase( string("\x82\x72")+int2zen(id++));
				else
					variables[string("\x82\x72")+int2zen(id++)] = *i;
			}
		}
	}

	const Word* w;

	if ( _pre_called_ ) {
		// 前段階ですでに対応カッコ展開済み
	}
	else if ( (w = words.select(iName, *this)) != NULL )
	{
		// 単語を選択した
		sender << "\x81\x97" << iName << endl;
		oResult = UnKakko( w->c_str() );
		speaked_speaker.insert(speaker);
		add_characters(oResult.c_str(), characters);
	}
	else if ( talks.is_exist(iName) ) {
		// ＊に定義があれば文を取得
		oResult = GetSentence(iName);
	}
	else if ( variables.find(iName) != variables.end() ) {
		// 変数名であれば変数の内容を返す
		oResult = variables[iName];
	}
	else if ( aredigits(hankaku=zen2han(iName)) || (hankaku[0]=='-' && aredigits(hankaku.c_str()+1)) ) {
		// サーフェス切り替え
		int	s = stoi(hankaku);
		if ( s != -1 ) // -1は「消し」なので特別扱い
			s += surface_add_value[speaker];
		oResult = string("\\s[") + itos(s) + "]";
		if ( !is_speaked(speaker) )
			surface_changed_before_speak.insert(speaker);
	}
	else if ( hankaku[0]=='R' && aredigits(hankaku.c_str()+1) ) {
		// Event通知時の引数取得
		int	ref=atoi(hankaku.c_str()+1);
		oResult = (ref>=0 && ref<mReferences.size()) ? mReferences[ref] : "";
		//oResult = mRequestMap[ string("Reference") + (hankaku.c_str()+1) ];
	}
	else if ( hankaku[0]=='H' && p_kakko_replace_history!=NULL && aredigits(hankaku.c_str()+1) ) {
		// 過去の置き換え履歴を参照
		int	num = atoi(hankaku.c_str() +1) - 1;
		if ( num>=0 && num < p_kakko_replace_history->size() )
			oResult = p_kakko_replace_history->at(num);
	}
	else if ( hankaku[0]=='A' && mCallStack.size()>0 && aredigits(hankaku.c_str()+1)) {
		// callによる呼び出しの引数を参照
		int	num = atoi(hankaku.c_str() +1);
		strvec&	v = mCallStack.top();
		if ( num < v.size() )
			oResult = v.at(num);
	}
	else if ( hankaku=="argc" ) {
		// callによる呼び出しの引数をまとめて
		int	num = atoi(hankaku.c_str() +1) - 1;
		strvec&	v = mCallStack.top();
		if ( num < v.size() )
			oResult = v.at(num);
	}
	else if ( hankaku=="argv" ) {
		// callによる呼び出しの引数をまとめて
		int	num = atoi(hankaku.c_str() +1) - 1;
		strvec&	v = mCallStack.top();
		if ( num < v.size() )
			oResult = v.at(num);
	}
	else if ( strncmp(iName.c_str(), "\x97\x90\x90\x94", 4)==0 && iName.size()>6 ) { 
		strvec	vec;
		if ( split( iName.c_str()+4, "\x81\x60", vec ) != 2 ) {
			oResult = "\x81\xA6\x81\x40\x97\x90\x90\x94\x82\xCC\x8E\x77\x92\xE8\x82\xAA\x95\xCF\x82\xC5\x82\xB7\x81\x40\x81\xA6";
		}
		else {
			int	bottom = stoi(zen2han(vec[0]));
			int	top = stoi(zen2han(vec[1]));
			if ( bottom > top )
				Swap(&bottom, &top);

			if ( bottom == top )
				oResult = int2zen(top);
			else 
				oResult = int2zen( ((unsigned)random())%(top-bottom+1) + bottom );
		}
	}
	else if ( iName == "\x8C\xBB\x8D\xDD\x94\x4E" ) {
#ifdef POSIX
	        time_t st = time(NULL);
	        oResult = int2zen(localtime(&st)->tm_year + 1900);
#else
		SYSTEMTIME st; ::GetLocalTime(&st); oResult=int2zen(st.wYear);
#endif
	}
	else if ( iName == "\x8C\xBB\x8D\xDD\x97\x6A\x93\xFA" ) {
#ifdef POSIX
	        time_t st = time(NULL);
		struct tm* st_tm = localtime(&st);
		static const char* const ary[7]={"\x93\xFA","\x8C\x8E","\x89\xCE","\x90\x85","\x96\xD8","\x8B\xE0","\x93\x79"};
		oResult = (st_tm->tm_wday >= 0 && st_tm->tm_wday < 7) ? ary[st_tm->tm_wday] : "\x81\x48";
#else
		SYSTEMTIME st; ::GetLocalTime(&st);
		static const char* const ary[7]={"\x93\xFA","\x8C\x8E","\x89\xCE","\x90\x85","\x96\xD8","\x8B\xE0","\x93\x79"};
		oResult = ( st.wDayOfWeek >= 0 && st.wDayOfWeek < 7 ) ? ary[st.wDayOfWeek] : "\x81\x48";
#endif
	}
#ifdef POSIX
	else if ( iName == "\x8C\xBB\x8D\xDD\x8C\x8E" ) { time_t st = time(NULL); oResult = int2zen(localtime(&st)->tm_mon + 1); }
	else if ( iName == "\x8C\xBB\x8D\xDD\x93\xFA" ) { time_t st = time(NULL); oResult = int2zen(localtime(&st)->tm_mday); }
	else if ( iName == "\x8C\xBB\x8D\xDD\x8E\x9E" ) { time_t st = time(NULL); oResult = int2zen(localtime(&st)->tm_hour); }
	else if ( iName == "\x8C\xBB\x8D\xDD\x95\xAA" ) { time_t st = time(NULL); oResult = int2zen(localtime(&st)->tm_min); }
	else if ( iName == "\x8C\xBB\x8D\xDD\x95\x62" ) { time_t st = time(NULL); oResult = int2zen(localtime(&st)->tm_sec); }
#else
	else if ( iName == "\x8C\xBB\x8D\xDD\x8C\x8E" ) { SYSTEMTIME st; ::GetLocalTime(&st); oResult=int2zen(st.wMonth); }
	else if ( iName == "\x8C\xBB\x8D\xDD\x93\xFA" ) { SYSTEMTIME st; ::GetLocalTime(&st); oResult=int2zen(st.wDay); }
	else if ( iName == "\x8C\xBB\x8D\xDD\x8E\x9E" ) { SYSTEMTIME st; ::GetLocalTime(&st); oResult=int2zen(st.wHour); }
	else if ( iName == "\x8C\xBB\x8D\xDD\x95\xAA" ) { SYSTEMTIME st; ::GetLocalTime(&st); oResult=int2zen(st.wMinute); }
	else if ( iName == "\x8C\xBB\x8D\xDD\x95\x62" ) { SYSTEMTIME st; ::GetLocalTime(&st); oResult=int2zen(st.wSecond); }
#endif
#ifdef POSIX
	else if (iName == "\x8B\x4E\x93\xAE\x8E\x9E") {
	    unsigned long msec = posix_get_current_millis() - tick_count_at_load;
	    int hour = msec / 1000 / 60 / 60;
	    oResult = int2zen(hour);
	}
	else if (iName == "\x8B\x4E\x93\xAE\x95\xAA") {
	    unsigned long msec = posix_get_current_millis() - tick_count_at_load;
	    int hour = msec / 1000 / 60 / 60;
	    msec -= hour * 60 * 60 * 1000;
	    int minute = msec / 1000 / 60;
	    oResult = int2zen(minute);
	}
	else if (iName == "\x8B\x4E\x93\xAE\x95\x62" ) {
	    unsigned long msec = posix_get_current_millis() - tick_count_at_load;
	    int hour = msec / 1000 / 60 / 60;
	    msec -= hour * 60 * 60 * 1000;
	    int minute = msec / 1000 / 60;
	    msec -= minute * 60 * 1000;
	    int second = msec / 1000;
	    oResult = int2zen(second);
	}
	else if (iName == "\x92\x50\x8F\x83\x8B\x4E\x93\xAE\x95\x62" ) {
	    unsigned long msec = posix_get_current_millis() - tick_count_at_load;
	    oResult = int2zen(msec / 1000);
	}
	else if (iName == "\x92\x50\x8F\x83\x8B\x4E\x93\xAE\x95\xAA") {
	    unsigned long msec = posix_get_current_millis() - tick_count_at_load;
	    oResult = int2zen(msec / 1000 / 60);
	}
#else
	else if ( iName == "\x8B\x4E\x93\xAE\x8E\x9E" ) { oResult=int2zen(DwordToSystemTime(::GetTickCount()-tick_count_at_load).wHour); }
	else if ( iName == "\x8B\x4E\x93\xAE\x95\xAA" ) { oResult=int2zen(DwordToSystemTime(::GetTickCount()-tick_count_at_load).wMinute); }
	else if ( iName == "\x8B\x4E\x93\xAE\x95\x62" ) { oResult=int2zen(DwordToSystemTime(::GetTickCount()-tick_count_at_load).wSecond); }
	else if ( iName == "\x92\x50\x8F\x83\x8B\x4E\x93\xAE\x95\x62" ) { oResult=int2zen( (::GetTickCount()-tick_count_at_load)/1000 ); }
	else if ( iName == "\x92\x50\x8F\x83\x8B\x4E\x93\xAE\x95\xAA" ) { oResult=int2zen( (::GetTickCount()-tick_count_at_load)/1000/60 ); }
#endif
#ifdef POSIX
	else if (iName == "\x82\x6E\x82\x72\x8B\x4E\x93\xAE\x8E\x9E" || iName == "\x82\x6E\x82\x72\x8B\x4E\x93\xAE\x95\xAA" || iName == "\x82\x6E\x82\x72\x8B\x4E\x93\xAE\x95\x62" ||
		 iName == "\x92\x50\x8F\x83\x82\x6E\x82\x72\x8B\x4E\x93\xAE\x95\x62" || iName == "\x92\x50\x8F\x83\x82\x6E\x82\x72\x8B\x4E\x93\xAE\x95\xAA") {
	    // 取得する方法が無い。
	    oResult = int2zen(0);
	}
#else
	else if ( iName == "\x82\x6E\x82\x72\x8B\x4E\x93\xAE\x8E\x9E" ) { oResult=int2zen(DwordToSystemTime(::GetTickCount()).wHour); }
	else if ( iName == "\x82\x6E\x82\x72\x8B\x4E\x93\xAE\x95\xAA" ) { oResult=int2zen(DwordToSystemTime(::GetTickCount()).wMinute); }
	else if ( iName == "\x82\x6E\x82\x72\x8B\x4E\x93\xAE\x95\x62" ) { oResult=int2zen(DwordToSystemTime(::GetTickCount()).wSecond); }
	else if ( iName == "\x92\x50\x8F\x83\x82\x6E\x82\x72\x8B\x4E\x93\xAE\x95\x62" ) { oResult=int2zen( ::GetTickCount() / 1000 ); }
	else if ( iName == "\x92\x50\x8F\x83\x82\x6E\x82\x72\x8B\x4E\x93\xAE\x95\xAA" ) { oResult=int2zen( ::GetTickCount() / 1000/60 ); }
#endif
#ifdef POSIX
	else if (iName == "\x97\xDD\x8C\x76\x8E\x9E") {
	    unsigned long msec = posix_get_current_millis() - tick_count_at_load + tick_count_total;
	    int hour = msec / 1000 / 60 / 60;
	    oResult = int2zen(hour);
	}
	else if (iName == "\x97\xDD\x8C\x76\x95\xAA" ) {
	    unsigned long msec = posix_get_current_millis() - tick_count_at_load + tick_count_total;
	    int hour = msec / 1000 / 60 / 60;
	    msec -= hour * 60 * 60 * 1000;
	    int minute = msec / 1000 / 60;
	    oResult = int2zen(minute);
	}
	else if (iName == "\x97\xDD\x8C\x76\x95\x62") {
	    unsigned long msec = posix_get_current_millis() - tick_count_at_load + tick_count_total;
	    int hour = msec / 1000 / 60 / 60;
	    msec -= hour * 60 * 60 * 1000;
	    int minute = msec / 1000 / 60;
	    msec -= minute * 60 * 1000;
	    int second = msec / 1000;
	    oResult = int2zen(second);
	}
	else if (iName == "\x92\x50\x8F\x83\x97\xDD\x8C\x76\x95\x62") {
	    unsigned long msec = posix_get_current_millis() - tick_count_at_load + tick_count_total;
	    oResult = int2zen(msec / 1000);
	}
	else if (iName == "\x92\x50\x8F\x83\x97\xDD\x8C\x76\x95\xAA") {
	    unsigned long msec = posix_get_current_millis() - tick_count_at_load + tick_count_total;
	    oResult = int2zen(msec / 1000 / 60);
	}
#else
	else if ( iName == "\x97\xDD\x8C\x76\x8E\x9E" ) { oResult=int2zen(DwordToSystemTime( ::GetTickCount() - tick_count_at_load + tick_count_total ).wHour); }
	else if ( iName == "\x97\xDD\x8C\x76\x95\xAA" ) { oResult=int2zen(DwordToSystemTime( ::GetTickCount() - tick_count_at_load + tick_count_total ).wMinute); }
	else if ( iName == "\x97\xDD\x8C\x76\x95\x62" ) { oResult=int2zen(DwordToSystemTime( ::GetTickCount() - tick_count_at_load + tick_count_total ).wSecond); }
	else if ( iName == "\x92\x50\x8F\x83\x97\xDD\x8C\x76\x95\x62" ) { oResult=int2zen( (::GetTickCount() - tick_count_at_load + tick_count_total)/1000 ); }
	else if ( iName == "\x92\x50\x8F\x83\x97\xDD\x8C\x76\x95\xAA" ) { oResult=int2zen( (::GetTickCount() - tick_count_at_load + tick_count_total)/1000/60 ); }
#endif
	else if ( iName == "time_t" ) { time_t tm; time(&tm); oResult=itos(tm); }
	else if ( iName == "\x8D\xC5\x8F\x49\x83\x67\x81\x5B\x83\x4E\x82\xA9\x82\xE7\x82\xCC\x8C\x6F\x89\xDF\x95\x62" ) { oResult=itos(second_from_last_talk); }

	else if ( compare_head(iName, "ResponseHistory") && aredigits(iName.c_str()+strlen("ResponseHistory")) ) {
	}

	else if ( compare_head(iName, "\x83\x54\x81\x5B\x83\x74\x83\x46\x83\x58") && aredigits(iName.c_str()+10) ) {
		oResult=itos(cur_surface[ atoi(iName.c_str()+10) ]);
	}
	else if ( compare_head(iName, "\x91\x4F\x89\xF1\x8F\x49\x97\xB9\x8E\x9E\x83\x54\x81\x5B\x83\x74\x83\x46\x83\x58") && aredigits(iName.c_str()+20) ) {
		oResult=itos(last_talk_exiting_surface[ atoi(iName.c_str()+20) ]);
	}

	else if ( iName == "\x97\xD7\x82\xC5\x8B\x4E\x93\xAE\x82\xB5\x82\xC4\x82\xA2\x82\xE9\x83\x53\x81\x5B\x83\x58\x83\x67" ) { 
		updateGhostsInfo();	// ゴースト情報を更新
		oResult = ( ghosts_info.size()>=2 ) ? (ghosts_info[1])["name"] : ""; 
	}
	else if ( iName == "\x8B\x4E\x93\xAE\x82\xB5\x82\xC4\x82\xA2\x82\xE9\x83\x53\x81\x5B\x83\x58\x83\x67\x90\x94" ) { 
		updateGhostsInfo();	// ゴースト情報を更新
		oResult = itos(ghosts_info.size()); 
	}
	else if ( compare_head(iName, "isempty") && iName.size()>=8 ) {
		const char* p = iName.c_str()+7;
		mbinc(p);
		oResult = (*p=='\0') ? "1" : "0";
	}
	else if ( compare_head(iName, "\x95\xB6\x81\x75") && compare_tail(iName, "\x81\x76\x82\xCC\x91\xB6\x8D\xDD") ) {
		string	str(iName, 4, iName.length()-4-8);
		oResult = talks.is_exist(str) ? "1" : "0";
	}
	else if ( compare_head(iName, "\x95\xCF\x90\x94\x81\x75") && compare_tail(iName, "\x81\x76\x82\xCC\x91\xB6\x8D\xDD") ) {
		string	str(iName, 6, iName.length()-6-8);
		oResult = (variables.find(str) != variables.end()) ? "1" : "0";
	}
	else if ( compare_head(iName, "\x92\x50\x8C\xEA\x8C\x51\x81\x75") && compare_tail(iName, "\x81\x76\x82\xCC\x91\xB6\x8D\xDD") ) {
		string	str(iName, 8, iName.length()-8-8);
		oResult = words.is_exist(str) ? "1" : "0";
	}
	else if ( compare_tail(iName, "\x82\xCC\x91\xB6\x8D\xDD") ) {
		updateGhostsInfo();	// ゴースト情報を更新
		vector<strmap>::iterator i=ghosts_info.begin();
		for ( ; i!=ghosts_info.end() ; ++i )
			if ( compare_head(iName, (*i)["name"]) )
				break;
			else if ( compare_head(iName, (*i)["keroname"]) )
				break;
		oResult = ( i==ghosts_info.end() ) ? "0" : "1";
	}
	else if ( compare_tail(iName, "\x82\xCC\x83\x54\x81\x5B\x83\x74\x83\x46\x83\x58") ) {
		updateGhostsInfo();	// ゴースト情報を更新
		vector<strmap>::iterator i=ghosts_info.begin();
		for ( ; i!=ghosts_info.end() ; ++i )
			if ( compare_head(iName, (*i)["name"]) ) {
				oResult = (*i)["sakura.surface"];
				break;
			} else if ( compare_head(iName, (*i)["keroname"]) ) {
				oResult = (*i)["kero.surface"];
				break;
			}

		if ( i==ghosts_info.end() ) {
			oResult = "-1";
		}
	}
	else if ( compare_head(iName, "FMO") && iName.size()>4 ) { // FMO?head
		updateGhostsInfo();	// ゴースト情報を更新
		if ( !isdigit(iName[3]) )
			NULL;
		else if ( iName[3]-'0' > ghosts_info.size() )
			NULL;
		else {
			strmap&	m=ghosts_info[iName[3]-'0'];
			string	value(iName.c_str()+4);
			if ( m.find(value) != m.end() )
				oResult = m[value];
		}
	}
	else if ( compare_head(iName, "count") )
	{
		string	name(iName.c_str()+5);
		if ( name=="Words" ) { oResult = itos( words.size_of_family() ); }
		else if ( name=="Variable" ) { oResult = itos( variables.size() ); }
		else if ( name=="Anchor" ) { oResult = itos( anchors.size() ); }
		else if ( name=="Talk" ) { oResult = itos( talks.size_of_element() ); }
		else if ( name=="Word" ) { oResult = itos( words.size_of_element() ); }
		else if ( name=="NoNameTalk" )
		{
			Family<Talk>* f = talks.get_family("");
			oResult = itos( ( f==0 ) ? 0 : f->size_of_element() );
		}
		else if ( name=="EventTalk" )
		{
			int	n=0;
			for ( map< string, Family<Talk> >::const_iterator it = talks.compatible().begin() ; it != talks.compatible().end() ; ++it )
				if ( compare_head(it->first, "On") )
					n += it->second.size_of_element();
			oResult = itos(n);
		}
		else if ( name=="OtherTalk" )
		{
			int	n=0;
			for ( map< string, Family<Talk> >::const_iterator it = talks.compatible().begin() ; it != talks.compatible().end() ; ++it )
				if ( !compare_head(it->first, "On") && !it->first.empty() )
					n += it->second.size_of_element();
			oResult = itos(n);
		}
		else if ( name=="Line" )
		{
			int	n=0;
			for ( map< string, Family<Talk> >::const_iterator it = talks.compatible().begin() ; it != talks.compatible().end() ; ++it )
			{
				vector<const Talk*> v;
				it->second.get_elements_pointers(v);
				for ( vector<const Talk*>::const_iterator el_it = v.begin() ; el_it != v.end() ; ++el_it )
				{
					n += (*el_it)->size();
				}
			}
			for ( map< string, Family<Word> >::const_iterator it = words.compatible().begin() ; it != words.compatible().end() ; ++it )
			{
				n += it->second.size_of_element();
			}
			oResult = itos(n);
		}
		else if ( name=="Parenthesis" )
		{
			int	n=0;
			for ( map< string, Family<Talk> >::const_iterator it = talks.compatible().begin() ; it != talks.compatible().end() ; ++it )
			{
				vector<const Talk*> v;
				it->second.get_elements_pointers(v);
				for ( vector<const Talk*>::const_iterator el_it = v.begin() ; el_it != v.end() ; ++el_it )
				{
					for ( Talk::const_iterator tk_it = (*el_it)->begin() ; tk_it != (*el_it)->end() ; ++tk_it )
					{
						n += count(*tk_it, "\x81\x69");
					}
				}
			}
			for ( map< string, Family<Word> >::const_iterator it = words.compatible().begin() ; it != words.compatible().end() ; ++it )
			{
				vector<const Word*> v;
				it->second.get_elements_pointers(v);
				for ( vector<const Word*>::const_iterator el_it = v.begin() ; el_it != v.end() ; ++el_it )
				{
					n += count(**el_it, "\x81\x69");
				}
			}
			oResult = itos(n);
		}
	}
	else if ( iName=="\x8E\x9F\x82\xCC\x83\x67\x81\x5B\x83\x4E" ) {
		map<int,string>::iterator it = reserved_talk.find(1);
		if ( it != reserved_talk.end() ) 
			oResult = it->second;
	}
	else if ( compare_head(iName,"\x8E\x9F\x82\xA9\x82\xE7") && compare_tail(iName,"\x89\xF1\x96\xDA\x82\xCC\x83\x67\x81\x5B\x83\x4E") ) {
		int	count = stoi( zen2han( string(iName.c_str()+6, iName.length()-6-12) ) );
		map<int,string>::iterator it = reserved_talk.find(count);
		if ( it != reserved_talk.end() ) 
			oResult = it->second;
	}
	else if ( compare_head(iName, "\x83\x67\x81\x5B\x83\x4E\x81\x75") && compare_tail(iName, "\x81\x76\x82\xCC\x97\x5C\x96\xf1\x97\x4C\x96\xB3") ) { // 「約」には\が含まれる。
		string	str(iName, 8, iName.length()-8-12);
		oResult = "0";
		for (map<int, string>::iterator it=reserved_talk.begin(); it!=reserved_talk.end() ; ++it) {
			if ( str == it->second ) {
				oResult = "1";
				break;
			}
		}
	}
	else if ( iName == "\x97\x5C\x96\xf1\x83\x67\x81\x5B\x83\x4E\x90\x94" ) { // 「約」には\が含まれる。
		oResult = itos( reserved_talk.size() );
	}
	else if ( iName == "\x83\x43\x83\x78\x83\x93\x83\x67\x96\xBC" ) { oResult=mRequestID; }
	else if ( iName == "\x92\xBC\x91\x4F\x82\xCC\x91\x49\x91\xF0\x8E\x88\x96\xBC" ) { oResult=last_choice_name; }
	else if ( mRequestMap.find(iName) != mRequestMap.end() ) {
		oResult = mRequestMap[iName];
	}
	else {
		// 見つからなかった。通常喋り？
		speaked_speaker.insert(speaker);
		characters += oResult.size();
		sender << "\x81\x69" << iName << "\x81\x6A not found." << endl;
		return	false;
	}

	if ( p_kakko_replace_history!=NULL )
		p_kakko_replace_history->push_back(oResult);
	sender << "\x81\x69" << iName << "\x81\x6A\x81\xA8" << oResult << "" << endl;
	return	true;
}
