#include "Family.h"


// 要素は単語またはトーク。

// 同じ名前を持つ要素の集合がFamily。

// Familiesは名前により特定されるFamilyの集合。
// ほぼ map< string, Family<T> > だがpublic継承はせず、インタフェースを限定する

template<typename T>
class Families : private map< string, Family<T> >
{
        typedef typename map< string, Family<T> >::iterator iterator;
        typedef typename map< string, Family<T> >::const_iterator const_iterator;
	set<string> m_clearOC_at_talk_end;

public:
	//Families() { cout << "Families()" << endl; }
	//~Families() { cout << "~Families()" << endl; }

	// 要素の登録
	const T* add_element(const string& i_name, const T& i_t, const Condition& i_condition=Condition())
	{
		Family<T>& f = (*this)[i_name];
		return f.add_element(i_t, i_condition);
	}

	// 過去互換の提供
	const map< string, Family<T> >& compatible() const
	{
		return *this;
	}

	// 名前からFamilyを取得
	Family<T>* get_family(string i_name)
	{
	        iterator i = this->find(i_name);
		return ( i == this->end() ) ? NULL : &(i->second);
	}

	// 名前の存在を確認
	bool is_exist(const string& i_name) const
	{
		return this->find(i_name) != this->end();
	}

	// Tを１つ選択し、そのポインタを返す
	const T* select(const string& i_name, Evalcator& i_evalcator)
	{
		iterator it = this->find(i_name);
		if ( it == this->end() )
		{
			return NULL;
		}
		return it->second.select(i_evalcator);
	}
	
	// トークの終了を通知。重複制御期間が「トーク中」であるFamilyの重複回避制御をクリアする
	void handle_talk_end()
	{
		for ( set<string>::iterator it = m_clearOC_at_talk_end.begin() ; it != m_clearOC_at_talk_end.end() ; ++it )
		{
			get_family(*it)->clear_OC();
		}
	}

	// family数
	int size_of_family() const
	{
		return this->size();
	}

	// 全Familyの全要素数を計算
	int size_of_element() const
	{
		int r = 0;
		for ( const_iterator it = this->begin() ; it != this->end() ; ++it )
		{
			r += it->second.size_of_element();
		}
		return r;
	}

	// 全クリア
	void clear()
	{
		map< string, Family<T> >::clear();
		m_clearOC_at_talk_end.clear();
	}

	// 重複回避制御を選択する。引数はタイプ、期間
	void setOC(string i_name, string i_value)
	{
		iterator st, ed;
		if ( i_name == "\x81\x96" )
		{
			st = this->begin();
			ed = this->end();
		}
		else
		{
			st = this->find(i_name);
			if ( st == this->end() )
			{
				sender << "'" << i_name << "' \x82\xCD\x91\xB6\x8D\xDD\x82\xB5\x82\xDC\x82\xB9\x82\xF1\x81\x42" << endl;
				return;
			}
			++(ed = st);
		}
		
		strvec argv;
		const int n = split(i_value, "\x81\x41,", argv);
		const string method = (n>=1) ? argv[0] : "\x96\xB3\x8C\xF8";
		const string span = (n>=2) ? argv[1] : "\x8B\x4E\x93\xAE\x92\x86";
		
		for ( iterator it = st; it != ed ; ++it )
		{
			Family<T>& family = it->second;
			if ( family.empty() )
			{
				continue;
			}

			if (0)
				NULL;
			else if ( method=="\x92\xBC\x91\x4F" )
				family.set_OC(new OC_NonDual<const T*>);
			else if ( method=="\x8D\x7E\x8F\x87" || method=="\x90\xB3\x8F\x87" )
				family.set_OC(new OC_Sequential<const T*>);
			else if ( method=="\x8F\xB8\x8F\x87" || method=="\x8B\x74\x8F\x87" )
				family.set_OC(new OC_SequentialDesc<const T*>);
			else if ( method=="\x97\x4C\x8C\xF8" || method=="\x8A\xAE\x91\x53" )
				family.set_OC(new OC_NonOverlap<const T*>);
			else if ( method=="\x96\xB3\x8C\xF8" )
				family.set_OC(new OC_Random<const T*>);
			else
				sender << "\x8F\x64\x95\xA1\x89\xF1\x94\xF0\x90\xA7\x8C\xE4\x82\xCC\x95\xFB\x96\x40'" << method << "' \x82\xCD\x92\xE8\x8B\x60\x82\xB3\x82\xEA\x82\xC4\x82\xA2\x82\xDC\x82\xB9\x82\xF1\x81\x42" << endl;
			
			if ( span == "\x83\x67\x81\x5B\x83\x4E\x92\x86" )
				m_clearOC_at_talk_end.insert(it->first);
			else if ( span == "\x8B\x4E\x93\xAE\x92\x86")
				NULL;
			else
				sender << "\x8F\x64\x95\xA1\x89\xF1\x94\xF0\x82\xCC\x8A\xFA\x8A\xD4'" << method << "' \x82\xCD\x92\xE8\x8B\x60\x82\xB3\x82\xEA\x82\xC4\x82\xA2\x82\xDC\x82\xB9\x82\xF1\x81\x42" << endl;
		
		}
	}

	const Talk* communicate_search(const string& iSentence, bool iAndMode)
	{
		sender << "\x95\xB6\x96\xBC\x82\xCC\x8C\x9F\x8D\xF5\x82\xF0\x8A\x4A\x8E\x6E" << endl;
		sender << "\x81\x40\x91\xCE\x8F\xDB\x95\xB6\x8E\x9A\x97\xF1: " << iSentence << endl;
		sender << "\x81\x40\x91\x53\x92\x50\x8C\xEA\x88\xEA\x92\x76\x83\x82\x81\x5B\x83\x68: " << (iAndMode?"true":"false") << endl;

		vector<const Talk*>	result;
		int	max_hit_point=0;
		for ( iterator it = this->begin() ; it != this->end() ; ++it )
		{
			// 語群を全角スペースで区切る
			strvec	words;
			if ( split(it->first, "\x81\x40", words)<2 )
			{
				continue; // 全角スペースが無い。該当外。
			}

			// いくつの単語がヒットしたか。単語１つで10てん、長さ１もじで1てん
			int	hit_point=0;
			strvec::iterator wds_it=words.begin();
			for ( ; wds_it!=words.end() ; ++wds_it )
			{
				if ( iSentence.find(*wds_it) != string::npos )
				{
					if ( compare_tail(*wds_it, "\x81\x75") )	// 末尾が 「 であるものだけの場合はヒットと見なさないように。
						hit_point += 4;
					else
						hit_point += 10+(wds_it->size()/4);	// 一致した単語。
				}
				else
				{
					hit_point -= (iAndMode ? 999 : 1);	// 一致しなかった、見つからなかった単語
				}
			}
			if ( hit_point<=4 )
			{
				continue;	// いっこも一致しない場合
			}

			sender << "'" << it->first << "' : " << hit_point << "pt ,";

			if ( hit_point<max_hit_point) {
				sender << "\x8B\x70\x89\xBA" << endl;
				continue;
			} else if ( hit_point == max_hit_point ) {
				sender << "\x8C\xF3\x95\xE2\x82\xC6\x82\xB5\x82\xC4\x92\xC7\x89\xC1" << endl;
			} else {
				max_hit_point = hit_point;
				sender << "\x92\x50\x93\xC6\x82\xC5\x8D\xCC\x97\x70" << endl;
				result.clear();
			}


			it->second.get_elements_pointers(result);
		}

		sender << "\x8C\x8B\x89\xCA: ";
		if ( result.size() <= 0 ) {
			sender << "\x8A\x59\x93\x96\x82\xC8\x82\xB5" << endl;
			return	NULL;
		}

		return result[ random()%(result.size()) ];
	}

};


