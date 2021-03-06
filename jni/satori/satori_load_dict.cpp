#include	"satori.h"

#include	<fstream>
#include	<cassert>

#include	"../_/Utilities.h"
#include	"satori_load_dict.h"

#ifdef POSIX
#  include <iostream>
#  include "stltool.h"
#endif


struct satori_unit
{
	string typemark;
	string name;
	string condition;
	strvec body;
};
#ifdef _DEBUG
ostream& operator<<(ostream& o, const satori_unit& su)
{
	o << su.typemark << "/" << su.name << "/" << su.condition << endl;
	o << su.body;
	return o;
}
#endif

static void lines_to_units(
	const vector<string>& i_lines,
	const vector<string>& i_typemarks,
	const string& i_name_cond_delimiter,
	vector<satori_unit>& o_units)
{
	vector<string>::const_iterator line_it = i_lines.begin();
	for ( ; line_it != i_lines.end() ; ++line_it)
	{
		//cout << *line_it << endl;

		// 行頭にtypemarksのいずれかが出現しているか探す
		vector<string>::const_iterator mark_it = i_typemarks.begin();
		for ( ; mark_it != i_typemarks.end() ; ++mark_it) 
		{
			if ( line_it->compare(0, mark_it->size(), mark_it->c_str()) ==0 )
			{
				break;
			}
		}
		
		// 出現してたらヘッダ、さもなくばボディ
		if ( mark_it != i_typemarks.end() )
		{
			satori_unit unit;
			unit.typemark = *mark_it;
			
			const char* name = line_it->c_str() + mark_it->size();
			const char* delimiter = strstr_hz(name, i_name_cond_delimiter.c_str());
			if ( delimiter==NULL )
			{
				unit.name.assign(name);
			}
			else
			{
				unit.name.assign(name, delimiter);
				unit.condition.assign(delimiter + i_name_cond_delimiter.size());
			}

			o_units.push_back(unit);
		}
		else
		{
			if ( o_units.empty() ) 
			{
				// o_unitsが空のとき（最初のtypemarks出現前）はコメントと見做し、何もしない。
			}
			else
			{
				// 最後のo_unitsにミを追加
				o_units.back().body.push_back(*line_it);
			}
		}
	}

	// 各unit末尾の空行を削る
	for ( vector<satori_unit>::iterator i = o_units.begin() ; i != o_units.end() ; ++i )
	{
		while (true)
		{
			strvec::iterator j = i->body.end();
			if ( j == i->body.begin() || (--j)->length()>0 )
			{
				break;
			}
			i->body.pop_back();
		}

		//sender << *i << endl;
	}
}


// プリプロセス的な処理。
// 改行キャンセル適用、コメント削除、before_replaceの適用
static bool pre_process(
	const strvec& in,
	strvec& out,

	escaper& io_escaper,
	strmap& io_replace_dic
	
	)
{
	int	kakko_nest_count=0;	// "（" のネスト数。1以上の場合は改行を無効化する。
	string	accumulater="";	// 行あきゅむれーた
	int	line_number=1;
	for ( strvec::const_iterator fi=in.begin() ; fi!=in.end() ; ++fi, ++line_number )
	{
		const char* p=fi->c_str();
		bool	escape = false;

		// カッコ内の場合、行頭のタブは無視する。
		if ( kakko_nest_count>0 )
			while ( *p=='\t' )
				++p;

		// 一行（物理行）に対する処理
		while ( *p!='\0' )
		{
			string	c=get_a_chr(p);	// 全角半角問わず一文字取得。

			if ( escape ) 
			{
				accumulater += (c=="φ") ? c : io_escaper.insert(c);
				escape = false;
			}
			else 
			{
				if ( c=="φ" ) 
				{
					escape = true;
					continue;
				}
				if ( c=="＃" )
					break;	// 行終了

				if ( c=="（" )
					++kakko_nest_count;
				else if (  c=="）" && kakko_nest_count>0 )
					--kakko_nest_count;

				accumulater += c;
			}
		}

		if ( escape )
		{
			continue;
		}

		if ( kakko_nest_count==0 )
		{
			// 置き換え辞書適用
			for ( strmap::iterator di=io_replace_dic.begin() ; di!=io_replace_dic.end() ; ++di )
			{
				replace(accumulater, di->first, di->second);
			}

			// 一行追加
			out.push_back(accumulater);
			//sender << line_number << " [" << accumulater << "]" << endl;
			accumulater="";
		}
		else if ( line_number == in.size() ) 
		{
			// エラー
			return false;
		}
	}
	return true;
}

string	Satori::SentenceToSakuraScript_with_PreProcess(const strvec& i_vec)
{
	strvec vec;
	pre_process(i_vec, vec, m_escaper, replace_before_dic);
	return SentenceToSakuraScript(vec);
}

// .txtと.satの両方がくるので、新しい方だけを読み込む。
static bool select_dict_and_load_to_vector(const string& iFileName, strvec& oFileBody)
{
	string txtfile = set_extention(iFileName, "txt");
	string satfile = set_extention(iFileName, "sat");


	// ２ファイルの日時を比較する。file1が新しければ正の値、file2が新しければ負の値、等しければ0を返す。
	// ファイルが存在しない場合、存在しない方が古いと判定する。
#ifdef POSIX
	int CompareTime(const string& file1, const string& file2);
#else
	int	CompareTime(LPCSTR szL, LPCSTR szR);
#endif

	if ( CompareTime(txtfile.c_str(), satfile.c_str())>=0 )
	{
		// .txtの方が新しかった
		if ( get_extention(iFileName) == "sat" )
		{
			sender << "  " << satfile << "is older file.";
			return false;
		}
	
		sender << "  loading " << get_file_name(txtfile);
		if ( !strvec_from_file(oFileBody, txtfile) )
		{
			sender << "... failed." << endl;
			return	false;
		}
	}
	else
	{
		// .satの方が新しかった
		if ( get_extention(iFileName) == "txt" )
		{
			sender << "  " << txtfile << "is older file.";
			return false;
		}

		sender << "  loading " << get_file_name(satfile);
		if ( !strvec_from_file(oFileBody, satfile) )
		{
			sender << "... failed." << endl;
			return	false;
		}

		// 暗号化を解除
		for ( strvec::iterator it=oFileBody.begin() ; it!=oFileBody.end() ; ++it )
		{
			*it = decode( decode(*it) );
		}
	}
	return true;
}



// 辞書を読み込む。
bool	Satori::LoadDictionary(const string& iFileName) 
{
	// ファイルからvectorへ読み込む。
	// その際、同ファイル名で拡張子が.txtと.satのファイルの日付を比較し、新しい方だけを採用する。
	strvec	file_vec;
	if ( !select_dict_and_load_to_vector(iFileName, file_vec) )
	{
		return false;
	}

	bool	is_for_anchor = compare_head(get_file_name(iFileName), "dicAnchor");

	strvec preprocessed_vec;
	if ( false == pre_process(file_vec, preprocessed_vec, m_escaper, replace_before_dic) )
	{
#ifdef POSIX
	        // MessageBoxなんて無い！
	        std::cerr <<
		    "syntax error - SATORI : " << iFileName << std::endl <<
		    std::endl <<
		    "There are some mismatched parenthesis." << std::endl <<
		    "The dictionary is not loaded correctly." << std::endl <<
		    std::endl <<
		    "If you want to display parenthesis independently," << std::endl <<
		    "use \"phi\" symbol to escape it." << std::endl;
#else
		::MessageBox(NULL, 
			(string() + iFileName + "\n\n"
			"\n"
			"カッコの対応関係が正しくない部分があります。" "\n"
			"辞書は正しく読み込まれていません。" "\n"
			"\n"
			"カッコを単独で表示する場合は　φ（　と記述してください。").c_str(),
			"syntax error - SATORI", MB_OK|MB_SYSTEMMODAL);
#endif
	}

	static vector<string> typemarks;
	if ( typemarks.empty() )
	{
		typemarks.push_back("＊");
		typemarks.push_back("＠");
	}

	vector<satori_unit> units;
	lines_to_units(preprocessed_vec, typemarks, "\t", units); // 単語群名/トーク名と採用条件式の区切り


	for ( vector<satori_unit>::iterator i=units.begin() ; i!=units.end() ; ++i)
	{
		// 末尾の空行を削除
		//while ( i->body.size()>0 && i->body.size()==0 )
		//{
		//	i->body.pop_back();
		//}
	        while (i->body.size() > 0 && i->body[i->body.size()-1].length() == 0) {
		        i->body.pop_back();
		}
		
		if ( i->typemark == "＊" )
		{
			// トークの場合
			if ( is_for_anchor ) { anchors.insert(i->name); }
			talks.add_element(i->name, i->body, i->condition);
		}
		else
		{
			// 単語群の場合
			const strvec& v = i->body;
			for ( strvec::const_iterator j=v.begin() ; j!=v.end() ; ++j)
			{
				words.add_element(i->name, *j, i->condition);
			}
		}

	}

	//sender << "　　　talk:" << talks.count_all() << ", word:" << words.count_all() << endl;
	sender << "... ok." << endl;
	return	true;
}

#ifdef POSIX
#  include <sys/types.h>
#  include <dirent.h>
#endif

void list_files(string i_path, list<string>& o_files)
{
	unify_dir_char(i_path); // \\と/を環境に応じて適切な方に統一
#ifdef POSIX

	DIR* dh = opendir(i_path.c_str());
	if (dh == NULL)
	{
	    sender << "file not found." << endl;
	    return;
	}
	while (1) {
	    struct dirent* ent = readdir(dh);
	    if (ent == NULL) {
		break;
	    }
#if defined(__WINDOWS__) || defined(__CYGWIN__) || defined ANDROID
	    string fname(ent->d_name);
#else
	    string fname(ent->d_name, ent->d_namlen);
#endif
		o_files.push_back(fname);
	}
	closedir(dh);
#else /* POSIX */
	HANDLE			hFIND;	// 検索ハンドル
	WIN32_FIND_DATA	fdFOUND;// 見つかったファイルの情報
	hFIND = ::FindFirstFile((i_path+"*.*").c_str(), &fdFOUND);
	if ( hFIND == INVALID_HANDLE_VALUE )
	{
		sender << "file not found." << endl;
	}

	do
	{
		o_files.push_back(fdFOUND.cFileName);
	} while ( ::FindNextFile(hFIND,&fdFOUND) );
	::FindClose(hFIND);
#endif /* POSIX */

}




bool Satori::LoadDicFolder(const string& i_base_folder)
{
	sender << "LoadDicFolder(" << i_base_folder << ")" << endl;
	list<string> files;
	list_files(i_base_folder, files);
	
	for (list<string>::const_iterator it=files.begin() ; it!=files.end() ; ++it)
	{
		const int len = it->size();
		if ( len < 7 ) { continue; } // dic.txtが最短ファイル名
		if ( it->substr(0,3) != "dic" ) { continue; }
		if ( it->substr(len-4) != ".txt" && it->substr(len-4) != ".sat" ) { continue; }

		LoadDictionary(i_base_folder + *it);
	}

	sender << "ok." << endl;
	return	true;
}
