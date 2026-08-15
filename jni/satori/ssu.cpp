//------------------------------------------------
//
//	里々同梱ユーティリティライブラリ　ssu.dll
//
#include	"SaoriHost.h"
#include	<map>

class ssu : public SaoriHost {
public:
	virtual SRV		request(deque<string>& iArguments, deque<string>& oValues);
};
SakuraDLLHost* SakuraDLLHost::m_dll = new ssu;


static SRV	call_ssu(string iCommand, deque<string>& iArguments, deque<string>& oValues)
{
	// 名前と命令を関連付けたmap
	typedef SRV (*Command)(deque<string>&, deque<string>&);
	static map<string, Command>	theMap;
	if ( theMap.empty() )
	{ 
		// 初回準備
		#define	d(iName)	\
			SRV	_##iName(deque<string>&, deque<string>&); \
			theMap[ #iName ] = _##iName
		// 命令一覧の宣言と関連付け。
		d(calc);			d(calc_float);		d(if);				d(unless);
		d(nswitch);			d(switch);			d(iflist);			d(substr);
		d(split);			d(join);			d(replace);			d(replace_first);	d(erase);
		d(erase_first);		d(count);			d(compare);			d(compare_head);
		d(compare_tail);	d(length);			d(is_empty);		d(is_digit);
		d(is_alpha);		d(zen2han);			d(han2zen);			d(hira2kata);
		d(kata2hira);		d(sprintf);			d(reverse);			d(at);
		d(choice);
		#undef	d
	}

	// 命令の存在を確認
	map<string, Command>::iterator i = theMap.find(iCommand);
	if ( i==theMap.end() )
		return SRV(400, string()+"Error: '"+iCommand+"'\x82\xC6\x82\xA2\x82\xA4\x96\xBC\x91\x4F\x82\xCC\x96\xBD\x97\xDF\x82\xCD\x92\xE8\x8B\x60\x82\xB3\x82\xEA\x82\xC4\x82\xA2\x82\xDC\x82\xB9\x82\xF1\x81\x42");

	// 実際に呼ぶ
	return	i->second(iArguments, oValues);
}

SRV	ssu::request(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()<1 )
		return	SRV(400, "\x96\xBD\x97\xDF\x82\xAA\x8E\x77\x92\xE8\x82\xB3\x82\xEA\x82\xC4\x82\xA2\x82\xDC\x82\xB9\x82\xF1");

	// 最初の引数は命令名として扱う
	string	theCommand = iArguments.front();
	iArguments.pop_front();
	return	call_ssu(theCommand, iArguments, oValues);
}


// ここから実装

#ifdef POSIX
#  include      "../_/Utilities.h"
#else
#  include	<windows.h>
#  include	<mbctype.h>
#endif
#include	"../_/stltool.h"

/* 「ソ」の2バイト目は「\」であるので、エスケープする必要がある。 */
static const char	zen[] = 
	"\x81\x40\x82\x60\x82\x61\x82\x62\x82\x63\x82\x64\x82\x65\x82\x66\x82\x67\x82\x68\x82\x69\x82\x6A\x82\x6B\x82\x6C\x82\x6D\x82\x6E\x82\x6F\x82\x70\x82\x71\x82\x72\x82\x73\x82\x74\x82\x75\x82\x76\x82\x77\x82\x78\x82\x79\x82\x81\x82\x82\x82\x83\x82\x84\x82\x85\x82\x86\x82\x87\x82\x88\x82\x89\x82\x8A\x82\x8B\x82\x8C\x82\x8D\x82\x8E\x82\x8F\x82\x90\x82\x91\x82\x92\x82\x93\x82\x94\x82\x95\x82\x96\x82\x97\x82\x98\x82\x99\x82\x9A"
	"\x82\x4F\x82\x50\x82\x51\x82\x52\x82\x53\x82\x54\x82\x55\x82\x56\x82\x57\x82\x58\x81\x49\x81\x68\x81\x94\x81\x90\x81\x93\x81\x95\x81\x66\x81\x69\x81\x6A\x81\x81\x81\x60\x81\x62\x81\x65\x81\x6F\x81\x7B\x81\x96\x81\x70\x81\x83\x81\x84\x81\x48\x81\x51\x81\x5B\x81\x4F\x81\x8F\x81\x97\x81\x75\x81\x47\x81\x46\x81\x76\x81\x41\x81\x42\x81\x45\x81\x80\x81\x7E\x81\x7C\x81\x43\x81\x44\x81\x6D\x81\x6E"
	"\x83\x41\x83\x43\x83\x45\x83\x47\x83\x49\x83\x4A\x83\x4C\x83\x4E\x83\x50\x83\x52\x83\x54\x83\x56\x83\x58\x83\x5A\x83\x5c\x83\x5E\x83\x60\x83\x63\x83\x65\x83\x67\x83\x69\x83\x6A\x83\x6B\x83\x6C\x83\x6D\x83\x6E\x83\x71\x83\x74\x83\x77\x83\x7A\x83\x7D\x83\x7E\x83\x80\x83\x81\x83\x82\x83\x84\x83\x86\x83\x88\x83\x89\x83\x8A\x83\x8B\x83\x8C\x83\x8D\x83\x8F\x83\x92\x83\x93\x83\x40\x83\x42\x83\x44\x83\x46\x83\x48\x83\x83\x83\x85\x83\x87\x81\x4A\x81\x4B\x81\x41\x81\x42";
static const char	han[] = 
	" ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
	"0123456789!\"#$%&'()=~|`{+*}<>?_-^\\@[;:],.\xA5/*-,.[]"
	"\xB1\xB2\xB3\xB4\xB5\xB6\xB7\xB8\xB9\xBA\xBB\xBC\xBD\xBE\xBF\xC0\xC1\xC2\xC3\xC4\xC5\xC6\xC7\xC8\xC9\xCA\xCB\xCC\xCD\xCE\xCF\xD0\xD1\xD2\xD3\xD4\xD5\xD6\xD7\xD8\xD9\xDA\xDB\xDC\xA6\xDD\xA7\xA8\xA9\xAA\xAB\xAC\xAD\xAE\xDE\xDF\xA4\xA1";
static const char	kata[] = "\x83\x41\x83\x43\x83\x45\x83\x47\x83\x49\x83\x4A\x83\x4C\x83\x4E\x83\x50\x83\x52\x83\x54\x83\x56\x83\x58\x83\x5A\x83\x5c\x83\x5E\x83\x60\x83\x63\x83\x65\x83\x67\x83\x69\x83\x6A\x83\x6B\x83\x6C\x83\x6D\x83\x6E\x83\x71\x83\x74\x83\x77\x83\x7A\x83\x7D\x83\x7E\x83\x80\x83\x81\x83\x82\x83\x84\x83\x86\x83\x88\x83\x89\x83\x8A\x83\x8B\x83\x8C\x83\x8D\x83\x8F\x83\x90\x83\x91\x83\x92\x83\x93\x83\x40\x83\x42\x83\x44\x83\x46\x83\x48\x83\x83\x83\x85\x83\x87\x83\x62\x83\x4B\x83\x4D\x83\x4F\x83\x51\x83\x53\x83\x55\x83\x57\x83\x59\x83\x5B\x83\x5D\x83\x5F\x83\x61\x83\x64\x83\x66\x83\x68\x83\x6F\x83\x72\x83\x75\x83\x78\x83\x7B\x83\x70\x83\x73\x83\x76\x83\x79\x83\x7C";
static const char	hira[] = "\x82\xA0\x82\xA2\x82\xA4\x82\xA6\x82\xA8\x82\xA9\x82\xAB\x82\xAD\x82\xAF\x82\xB1\x82\xB3\x82\xB5\x82\xB7\x82\xB9\x82\xBB\x82\xBD\x82\xBF\x82\xC2\x82\xC4\x82\xC6\x82\xC8\x82\xC9\x82\xCA\x82\xCB\x82\xCC\x82\xCD\x82\xD0\x82\xD3\x82\xD6\x82\xD9\x82\xDC\x82\xDD\x82\xDE\x82\xDF\x82\xE0\x82\xE2\x82\xE4\x82\xE6\x82\xE7\x82\xE8\x82\xE9\x82\xEA\x82\xEB\x82\xED\x82\xEE\x82\xEF\x82\xF0\x82\xF1\x82\x9F\x82\xA1\x82\xA3\x82\xA5\x82\xA7\x82\xE1\x82\xE3\x82\xE5\x82\xC1\x83\x4B\x83\x4D\x83\x4F\x83\x51\x83\x53\x82\xB4\x82\xB6\x82\xB8\x82\xBA\x82\xBC\x82\xBE\x82\xC0\x82\xC3\x82\xC5\x82\xC7\x82\xCE\x82\xD1\x82\xD4\x82\xD7\x82\xDA\x82\xCF\x82\xD2\x82\xD5\x82\xD8\x82\xDB";
static const char	zen_alpha[] = "\x82\x60\x82\x61\x82\x62\x82\x63\x82\x64\x82\x65\x82\x66\x82\x67\x82\x68\x82\x69\x82\x6A\x82\x6B\x82\x6C\x82\x6D\x82\x6E\x82\x6F\x82\x70\x82\x71\x82\x72\x82\x73\x82\x74\x82\x75\x82\x76\x82\x77\x82\x78\x82\x79\x82\x81\x82\x82\x82\x83\x82\x84\x82\x85\x82\x86\x82\x87\x82\x88\x82\x89\x82\x8A\x82\x8B\x82\x8C\x82\x8D\x82\x8E\x82\x8F\x82\x90\x82\x91\x82\x92\x82\x93\x82\x94\x82\x95\x82\x96\x82\x97\x82\x98\x82\x99\x82\x9A";
static const char	han_alpha[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
static const char	zen_digit[] = "\x82\x4F\x82\x50\x82\x51\x82\x52\x82\x53\x82\x54\x82\x55\x82\x56\x82\x57\x82\x58";
static const char	han_digit[] = "0123456789";

extern	bool calc(string& ioString);
extern	bool calc_float(string& ioString);

#include	<sstream>

// 半角/全角を同等に扱った上で文字長を返す
int	sjis_strlen(const char* p) {
	int	n=0;
	for (int i=0 ; p[i] != '\0' ; i += _ismbblead(p[i]) ? 2 : 1 )
		++n;
	return	n;
}

// 半角/全角を同等に扱った上でn文字移動、超過時はNULL
const char*	sjis_at(const char* p, int n) {
	for (int i=0 ; i<n ; ++i) {
		if ( *p == '\0' )
			return	NULL;
		p += _ismbblead(*p) ? 2 : 1;
	}
	return	p;
}

bool	printf_format(const char*& p, deque<string>& iArguments, stringstream& os) {
	assert(*p=='%');
	if ( iArguments.empty() )
		return	false;	// 置き換え対象が無い

	++p;
	string	str = iArguments.front();
	iArguments.pop_front();

	// フラグ指定読み込み
	bool	isLeft=false, isZeroFill=false, isSharp=false;
	enum { MINUS_ONLY, MINUS_AND_PLUS, IF_PLUS_THEN_PUT_SPACE } SignMode = MINUS_ONLY;
	while (true) {
		if ( *p == '-' ) { isLeft=true; ++p; }
		else if ( *p == '+' ) { SignMode=MINUS_AND_PLUS; ++p; }
		else if ( *p == '0' ) { os.fill('0'); os<<internal; isZeroFill=true; ++p; }
		else if ( *p == ' ' ) { os.fill(' '); os<<internal; SignMode=IF_PLUS_THEN_PUT_SPACE; ++p; }
		else if ( *p == '#' ) { isLeft=true; ++p; }
		else break;
	}

	// 幅指定読み込み
	int	width=0;
	bool	isReadWidth = false;
	if ( *p=='*' ) {
		isReadWidth = true;
		++p;
	} else {
		while ( *p>='0' && *p<='9' ) {
			width = width*10 + (*p - '0');
			++p;
			os.width(width);
		}
	}

	// 精度指定読み込み
	/*int	precision=0;
	if ( *p == '.' ) {
		++p;
		while ( *p>='0' && *p<='9' ) {
			precision = precision*10 + (*p - '0');
			++p;
		}
		os.precision(precision);
	}*/

	// サイズ指定子は未対応

	// 変換文字に応じて挿入
	int	n = atoi(str.c_str());
	switch (*p) {
	case 's': os << str; break;
	case 'c': os << (char)n; break;
	case 'C': os << (unsigned short)n; break;
	case 'd': os << n; break;
	case 'i': os << oct << n; break; 
	case 'o': os << oct << n; break;
	case 'u': os << (unsigned int)n; break;
	case 'x': case 'X': break;
	case 'f': break;
	case 'e': case 'E': break;
	case 'p': break;
	default: return false;
	}
	++p;
	return	true;
}

string	sprintf(deque<string>& iArguments) {
	stringstream s;
	string	str = iArguments.front();
	iArguments.pop_front();
	const char* p = str.c_str();
	while ( *p!='\0' ) {
		if ( *p=='%' && printf_format(p, iArguments, s) )
			continue;
		if ( _ismbblead(*p) ) {
			s << *p++; s << *p++;
		} else {
			s << *p++;
		}
	}
	return	s.str();
}


SRV _calc(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()!=1 )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xCC\x8C\xC2\x90\x94\x82\xAA\x90\xB3\x82\xB5\x82\xAD\x82\xA0\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");
	string	exp = iArguments[0];
	if ( !calc(exp) )
		return	SRV(400, string()+"'"+iArguments[0]+"' \x8E\xAE\x82\xAA\x8C\x76\x8E\x5A\x95\x73\x94\x5c\x82\xC5\x82\xB7\x81\x42"); // 「能」の2バイト目は「\」
	return	exp;
}

SRV _calc_float(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()!=1 )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xCC\x8C\xC2\x90\x94\x82\xAA\x90\xB3\x82\xB5\x82\xAD\x82\xA0\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");
	string	exp = iArguments[0];
	if ( !calc_float(exp) )
	    return	SRV(400, string()+"'"+iArguments[0]+"' \x8E\xAE\x82\xAA\x8C\x76\x8E\x5A\x95\x73\x94\x5c\x82\xC5\x82\xB7\x81\x42");
	return	exp;
}

SRV _if(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()<2 || iArguments.size()>3 )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xCC\x8C\xC2\x90\x94\x82\xAA\x90\xB3\x82\xB5\x82\xAD\x82\xA0\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");
	string	exp = iArguments[0];
	if ( !calc(exp) )
		return	SRV(400, string()+"'"+iArguments[0]+"' \x8E\xAE\x82\xAA\x8C\x76\x8E\x5A\x95\x73\x94\x5c\x82\xC5\x82\xB7\x81\x42");
	if ( exp!="0" )
		return	iArguments[1];	// 真
	else
		if ( iArguments.size()==3 )
			return	iArguments[2];	// 偽
		else
			return	SRV(204);	// 偽でelseなし
}

SRV _unless(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()<2 || iArguments.size()>3 )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xCC\x8C\xC2\x90\x94\x82\xAA\x90\xB3\x82\xB5\x82\xAD\x82\xA0\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");
	string	exp = iArguments[0];
	if ( !calc(exp) )
		return	SRV(400, string()+"'"+iArguments[0]+"' \x8E\xAE\x82\xAA\x8C\x76\x8E\x5A\x95\x73\x94\x5c\x82\xC5\x82\xB7\x81\x42");
	if ( exp=="0" )
		return	iArguments[1];	// 偽
	else
		if ( iArguments.size()==3 )
			return	iArguments[2];	// 真
		else
			return	SRV(204);	// 真でelseなし
}

SRV _nswitch(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()<2 )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xAA\x91\xAB\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");
	if ( !calc(iArguments[0]) )
		return	SRV(400, string()+"'"+iArguments[0]+"' \x8E\xAE\x82\xAA\x8C\x76\x8E\x5A\x95\x73\x94\x5c\x82\xC5\x82\xB7\x81\x42");

	int	n = stoi(iArguments[0]);
	//iArguments.pop_front();
	//if ( iArguments.size()>n )
	if ( n>0 && iArguments.size()>n )
		return	SRV(200, iArguments[n]);
	else
		return	SRV(204);
}

SRV _switch(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()<2 )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xAA\x91\xAB\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");

	const string	lhs = iArguments[0];
	const int max = iArguments.size();
	for (int i=1 ; i<max ; i+=2) {
		if ( i==max-1 ) // 引数が奇数個の場合、最後の１つはelse式
			return	SRV(200, iArguments[i]);
		string	exp = string("(") + lhs + ")==(" + iArguments[i] + ")";
		if ( !calc(exp) )
			return	SRV(400, string()+"switch\x82\xCC"+itos((i-1)/2+1)+"\x8C\xC2\x96\xDA\x81\x41\x8E\xAE '"+exp+"' \x82\xCD\x8C\x76\x8E\x5A\x95\x73\x94\x5c\x82\xC5\x82\xB5\x82\xBD\x81\x42");
		if ( exp!="0" )
			return	SRV(200, iArguments[i+1]);
	}
	return	SRV(204);
}

SRV _iflist(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()<2 )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xAA\x91\xAB\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");

	const string	lhs = iArguments[0];
	const int max = iArguments.size();
	for (int i=1 ; i<max ; i+=2) {
		if ( i==max-1 ) // 引数が奇数個の場合、最後の１つはelse扱い。ここまできたら無条件でそれを返す。
			return	SRV(200, iArguments[i]);
		string	exp = lhs + iArguments[i];
		if ( !calc(exp) )
			return	SRV(400, string()+"iflist\x82\xCC"+itos((i-1)/2+1)+"\x8C\xC2\x96\xDA\x81\x41\x8E\xAE '"+exp+"' \x82\xCD\x8C\x76\x8E\x5A\x95\x73\x94\x5c\x82\xC5\x82\xB5\x82\xBD\x81\x42");
		if ( exp!="0" )
			return	SRV(200, iArguments[i+1]);
	}
	return	SRV(204);
}


SRV _substr(deque<string>& iArguments, deque<string>& oValues) {

	if ( iArguments.size()<1 )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xAA\x91\xAB\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");

	// 対象文字列
	const char* p = iArguments[0].c_str();
	if ( iArguments.size()==1 )
		return	SRV(200, p); // 引数１個なら全体を返す

	const int	len = sjis_strlen(p);

	// 始点
	int	start = atoi(iArguments[1].c_str());
	if ( start < 0 )
		start = len + start;

	// 始点からのオフセット値
	int offset = (iArguments.size()<=2) ? len : atoi(iArguments[2].c_str());
	if ( offset==0 || offset==INT_MIN ) // INT_MINの時は符号反転が効かないので0扱い。
		return	SRV(204);
	if ( offset<0 ) {
		start += offset;
		offset = -offset;
	}
	assert(offset >= 0 );

	if ( start < 0 )
		start = 0;
	if ( start >= len )
		return	SRV(204);
	if ( start + offset >= len )
		offset = len - start;

	const char* const start_p = sjis_at(p, start);
	const char* const end_p = sjis_at(start_p, offset);
	return	SRV(200, string(start_p, end_p));
}

SRV _split(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()<1 || iArguments.size()>3 )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xCC\x8C\xC2\x90\x94\x82\xAA\x90\xB3\x82\xB5\x82\xAD\x82\xA0\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");

	strvec	vec;
	if ( iArguments.size()==1 ) {
		split(iArguments[0],vec);
	}
	else if ( iArguments.size()==2 ) {
		split(iArguments[0],iArguments[1],vec);
	}
	else {
		if ( !calc(iArguments[2]) )
			return	SRV(400, "split\x82\xCC\x91\xE6\x82\x52\x88\xF8\x90\x94\x82\xCD\x8E\xAE\x82\xDC\x82\xBD\x82\xCD\x90\x94\x92\x6C\x82\xC5\x82\xA0\x82\xE9\x95\x4B\x97\x76\x82\xAA\x82\xA0\x82\xE8\x82\xDC\x82\xB7\x81\x42");
		split(iArguments[0],iArguments[1],vec,stoi(iArguments[2]));
	}

	for ( strvec::iterator i=vec.begin() ; i!=vec.end() ; ++i )
		oValues.push_back(*i);
	return	SRV(200, itos(vec.size()));
}

SRV _join(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()<1 )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xCC\x8C\xC2\x90\x94\x82\xAA\x90\xB3\x82\xB5\x82\xAD\x82\xA0\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");

	string	r = iArguments[1];
	for (int n=2 ; n<iArguments.size() ; ++n)
		r += iArguments[0] + iArguments[n];
	return	r;
}

SRV _replace(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()!=3 )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xCC\x8C\xC2\x90\x94\x82\xAA\x90\xB3\x82\xB5\x82\xAD\x82\xA0\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");
	replace(iArguments[0], iArguments[1], iArguments[2]);
	return	SRV(200, iArguments[0]);
}

SRV _replace_first(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()!=3 )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xCC\x8C\xC2\x90\x94\x82\xAA\x90\xB3\x82\xB5\x82\xAD\x82\xA0\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");
	replace_first(iArguments[0], iArguments[1], iArguments[2]);
	return	iArguments[0];
}

SRV _erase(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()!=2 )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xCC\x8C\xC2\x90\x94\x82\xAA\x90\xB3\x82\xB5\x82\xAD\x82\xA0\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");
	erase(iArguments[0], iArguments[1]);
	return	iArguments[0];
}

SRV _erase_first(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()!=2 )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xCC\x8C\xC2\x90\x94\x82\xAA\x90\xB3\x82\xB5\x82\xAD\x82\xA0\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");
	erase_first(iArguments[0], iArguments[1]);
	return	iArguments[0];
}

SRV _count(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()!=2 )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xCC\x8C\xC2\x90\x94\x82\xAA\x90\xB3\x82\xB5\x82\xAD\x82\xA0\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");
	return	itos( count(iArguments[0], iArguments[1]) );
}

SRV _compare(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()!=2 )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xCC\x8C\xC2\x90\x94\x82\xAA\x90\xB3\x82\xB5\x82\xAD\x82\xA0\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");
	return	(strcmp(iArguments[0].c_str(), iArguments[1].c_str())==0) ? "1" : "0";
}

SRV _compare_head(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()!=2 )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xCC\x8C\xC2\x90\x94\x82\xAA\x90\xB3\x82\xB5\x82\xAD\x82\xA0\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");
	return	compare_head(iArguments[0], iArguments[1]) ? "1" : "0";
}

SRV _compare_tail(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()!=2 )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xCC\x8C\xC2\x90\x94\x82\xAA\x90\xB3\x82\xB5\x82\xAD\x82\xA0\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");
	return	compare_tail(iArguments[0], iArguments[1]) ? "1" : "0";
}

SRV _length(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()<1 )
		return	"0";
	return	itos( sjis_strlen(iArguments[0].c_str()) );
}

SRV _is_empty(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()<1 )
		return	"1";
	if ( iArguments[0].empty() )
		return	"1";
	else
		return	"0";
}

SRV _is_digit(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()<1 || iArguments[0].empty() )
		return	"0";
	int	i;
	for ( const char* p = iArguments[0].c_str() ; *p ; p += (_ismbblead(*p)?2:1) ) {
		for ( i=0 ; i<20 ; i+=2)
			if ( p[0]==zen_digit[i] && p[1]==zen_digit[i+1] )
				break;
		if ( i<20 )
			continue;
		for ( i=0 ; i<10 ; ++i)
			if ( p[0]==han_digit[i] )
				break;
		if ( i<10 )
			continue;
		return	"0";
	}
	return	"1";
}

SRV _is_alpha(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()<1 || iArguments[0].empty() )
		return	"0";
	return	arealphabets(iArguments[0]) ? "1" : "0";
}

SRV _zen2han(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()!=1 )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xCC\x8C\xC2\x90\x94\x82\xAA\x90\xB3\x82\xB5\x82\xAD\x82\xA0\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");

	char	before[3]="\x81\x40", after[2]=" ";
	string&	str=iArguments[0];
	for (int n=0 ; n<sizeof(han) ; ++n) {
		before[0]=zen[n*2];
		before[1]=zen[n*2+1];
		after[0]=han[n];
		replace(str, before, after);
	}
	return	str;
}

SRV _han2zen(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()!=1 )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xCC\x8C\xC2\x90\x94\x82\xAA\x90\xB3\x82\xB5\x82\xAD\x82\xA0\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");

	char	before[2]=" ", after[3]="  ";
	string&	str=iArguments[0];
	for (int n=0 ; n<sizeof(han) ; ++n) {
		before[0]=han[n];
		after[0]=zen[n*2];
		after[1]=zen[n*2+1];
		replace(str, before, after);
	}
	return	str;
}

SRV _hira2kata(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()!=1 )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xCC\x8C\xC2\x90\x94\x82\xAA\x90\xB3\x82\xB5\x82\xAD\x82\xA0\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");

	string&	str=iArguments[0];
	for (int i=0 ; str[i]!='\0' ; i+=_ismbblead(str[i])?2:1) {
		for (int j=0 ; j<sizeof(hira) ; j+=2) {
			if ( str[i]==hira[j] && str[i+1]==hira[j+1] ) {
				str[i]=kata[j];
				str[i+1]=kata[j+1];
			}
		}
	}
	return	iArguments[0];
}

SRV _kata2hira(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.size()!=1 )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xCC\x8C\xC2\x90\x94\x82\xAA\x90\xB3\x82\xB5\x82\xAD\x82\xA0\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");

	string&	str=iArguments[0];
	for (int i=0 ; str[i]!='\0' ; i+=_ismbblead(str[i])?2:1) {
		for (int j=0 ; j<sizeof(hira) ; j+=2) {
			if ( str[i]==kata[j] && str[i+1]==kata[j+1] ) {
				str[i]=hira[j];
				str[i+1]=hira[j+1];
			}
		}
	}
	return	iArguments[0];
}

SRV _sprintf(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.empty() )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xAA\x91\xAB\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");
	return	sprintf(iArguments);
}

SRV _reverse(deque<string>& iArguments, deque<string>& oValues) {
	if ( iArguments.empty() )
		return	SRV(400, "\x88\xF8\x90\x94\x82\xAA\x91\xAB\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");

	string	r;
	const char* p = iArguments[0].c_str();
	while (*p != '\0') {
		const int len = _ismbblead(*p)?2:1;
		r = string(p, len) + r;
		p += len;
	}

	return	r;
}

SRV _at(deque<string>& iArguments, deque<string>& oValues) {

	if ( iArguments.size()==2 ) {
		const char* p = sjis_at(iArguments.at(0).c_str(), stoi(iArguments.at(1)));
		return	(p==NULL || *p=='\0') ? "" : string(p, _ismbblead(*p)?2:1);
	}
	//else if ( iArguments.size()==3 ) {
	//}
	else
		return	SRV(400, "\x88\xF8\x90\x94\x82\xAA\x90\xB3\x82\xB5\x82\xAD\x82\xA0\x82\xE8\x82\xDC\x82\xB9\x82\xF1\x81\x42");
}



/*
if ( compare_head(theCommand, "tm") ) {
	string	TimeCommands(const string& iCommand, const deque<string>& iArguments);
	return	TimeCommands(theCommand, iArguments);
}
*/
SRV _choice(deque<string>& iArguments, deque<string>& oValues)
{
	if ( iArguments.size()==0 )
	{
		return	"";
	}
	return iArguments[ rand() % iArguments.size() ];
}

#ifdef POSIX
// YAYA's POSIX SAORI bridge uses per-module symbol names.  SSU has one
// process-local host, so each successful load uses a nonzero compatibility id.
extern "C" int load(char* i_data, long i_data_len);
extern "C" int unload(void);
extern "C" char* request(char* i_data, long* io_data_len);

extern "C" long ssu_saori_load(char* i_data, long i_data_len) {
    return ::load(i_data, i_data_len) ? 1 : 0;
}

extern "C" int ssu_saori_unload(long) {
    return ::unload();
}

extern "C" char* ssu_saori_request(long, char* i_data, long* io_data_len) {
    return ::request(i_data, io_data_len);
}
#endif