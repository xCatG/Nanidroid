//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// KawariInlineScript -- split --
//
//      Programed by さとー/NAKAUE.T
//
//  2002.01.07  Phase 7.3     findposサブコマンド追加 (さとー)
//                            splitコマンド追加(さとー)
//  2002.03.17  Phase 7.9.0   7.9の仕様に合わせた (NAKAUE.T)
//                            splitがなぜexprとまとめられているか理解に苦しむので移動
//                            split仕様変更
//  2002.11.20  Phase 8.1.0   splitが空要素を挿入しなくなっていたバグを修正
//                            join追加
//  2003.11.17  Phase 8.2.0   splitがヌル区切り文字でこけるバグ修正
//                            split,join:区切り文字を省略するとヌル文字扱いに
//  2005.06.21  Phase 8.2.3   ぬるぽbugfix + 趣味に沿って修正 (suikyo)
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "kis/kis_split.h"
//---------------------------------------------------------------------------
#include <string>
using namespace std;
//---------------------------------------------------------------------------
#include "libkawari/kawari_engine.h"
#include "misc/l10n.h"
//---------------------------------------------------------------------------
// splitの実体
class TSplitter {
public:
	// source: 切り出す文字列
	// delim:  区切り文字列
	TSplitter(const string &source, const string &delim);

	// 次のトークンを切り出す
	string Next(void);

	// まだトークンが存在するか
	inline bool HasNext(void) { return (pos<max); }

private:
	wstring str;		// 切り出す文字列
	wstring cs;		// 区切り文字列
	wstring::size_type pos;	// 解析開始位置
	wstring::size_type max;	// 切り出す文字列の長さ
};
//---------------------------------------------------------------------------
TSplitter::TSplitter(const string &s, const string &c)
{
	str=ctow(s);
	cs=ctow(c);
	pos=static_cast<wstring::size_type>(0);
	max=str.length();
}
//---------------------------------------------------------------------------
string TSplitter::Next(void)
{
	if (!HasNext())
		return "";

	unsigned int idx;	// 注目中のポインタ
	string ret;
	if(cs.length()==0){
		// 区切り文字無し
		ret=wtoc(str.substr(pos, 1));
		pos++;
	}else if((idx=str.find(cs, pos))!=string::npos) {
		// 発見
		ret=wtoc(str.substr(pos,idx-pos));	// 空文字列が返ることもある
		pos=idx+cs.length();			// 区切り文字列分だけポインタ前進
	} else {
		// ラスト
		ret=wtoc(str.substr(pos));		// 空文字列が返ることもある
		pos=max;				// ポインタを文字列最後尾に
	}

	return ret;
}
//---------------------------------------------------------------------------
string KIS_split::Function(const vector<string>& args){

	if(!AssertArgument(args, 3, 4)) return ("");

	TEntry entry=Engine->CreateEntry(args[1]);
	string delim=(args.size()==3)?"":args[3];

	TSplitter splitter(args[2],delim);
	while(splitter.HasNext())
		entry.Push(Engine->CreateStrWord(splitter.Next()));

	return("");
}
//---------------------------------------------------------------------------
string KIS_join::Function(const vector<string>& args)
{
	if(!AssertArgument(args, 2, 3)) return ("");

	TEntry entry=Engine->GetEntry(args[1]);
	unsigned int size=entry.Size();

	string retstr;
	string sep;
	if(args.size()==2) {
		sep="";
	} else {
		sep=args[2];
	}
	for(unsigned int i=0;i<size;i++) {
		retstr+=Engine->IndexParse(entry,i)+sep;
	}

	return(retstr.substr(0,retstr.length()-sep.length()));
}
//---------------------------------------------------------------------------
