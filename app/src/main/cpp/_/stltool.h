/*
	stltool.h
*/
#ifndef	STLTOOL_H
#define	STLTOOL_H

#ifdef _MSC_VER 
// �e���v���[�g�����������̌x����}��
#pragma warning( disable : 4786 ) //�u�f�o�b�O����ł̎��ʎq�؎̂āv
#pragma warning( disable : 4503 ) //�u�������ꂽ���O�̒��������E��z���܂����B���O�͐؂�̂Ă��܂��B�v
// for�X�R�[�v��ANSI����������
#ifndef for
#define for if(0);else for
#endif	// for
#endif	// _MSC_VER

#include	<iostream>
#include	<string>
#include	<map>
#include	<set>
#include	<vector>
#include	<list>
#include	<fstream>
#include	<deque>
#include	<cassert>
#include 	<cstdlib>
#include	<cstring>
#include <cctype>
using namespace std;

class strvec : public vector<string>
{
public:
	strvec() : vector<string>() {}
	strvec(const_iterator first, const_iterator last) : vector<string>(first, last) {}

	string join(const string& i_delimiter="")
	{
		string r;
		for (iterator i=begin() ; i!=end() ; ++i)
		{
			if ( i!=begin() )
				r += i_delimiter;
			r += *i;
		}
		return r;
	}
};

typedef	map<string, string>	strmap;
typedef	set<string>	stringset;
typedef	list<string>	strlist;
typedef	map<string, int>	strintmap;
typedef	unsigned char	byte;
typedef	pair<string, string>	strpair;
typedef vector<strpair> strpairvec;
/*
{
public:
	strpairvec() : vector<strpair>() {}
	void push_back(const strpair& element)
	{
		this->vector<strpair>::push_back( element );
	}

	void push_back(const string& first, const string& second)
	{
		this->vector<strpair>::push_back( strpair(first, second) );
	}
};
*/

class inimap : public map<string, strmap>	{
public:
	bool	load(const string& iFileName);
	bool	save(const string& iFileName) const;
};
class inivec : public vector< pair<string, strpairvec> > {
public:
	bool	load(const string& iFileName);
};

// �f���~�^�܂œǂݍ���
bool	getline(istream& i, string& o, int delimtier='\n');
bool	getline(istream& i, int& o, int delimtier='\n');

// int�Ƃ̑��ݕϊ�
//inline int	stoi(const string& s) { return atoi(s.c_str()); }
inline string	itos(int i, const char* iFormat="%d") { char buf[32]; sprintf(buf,iFormat,i); return buf; }

// ����ĂȂɁB
bool	aredigits(const string& s);
bool	arealphabets(const string& s);

// target���̍ŏ���find�����񂪏o������ʒu��Ԃ��B���p�S�p���Ή�
const char*	strstr_hz(const char* target, const char* find);

// ������u��
bool	replace_first(string& str, const string& before, const string& after);
int	replace(string& str, const string& before, const string& after);
template<class T>
int	multi_replace(string& str, T* array1, T* array2, int array_size) {
	int	count=0;
	for (int i=0 ; i<array_size ; ++i)
		count += replace(str, array1[i], array2[i]);
	return	count;
}

// ���������
bool	erase_first(string& str, const string& before);
int	erase(string& str, const string& before);
// �Ώی��̐��𐔂���
int	count(const string& str, const string& target);
// �Ώی��̑��݊m�F
inline bool find(const string& str, const string& target) { return	strstr_hz(str.c_str(), target.c_str())!=NULL; }


// deque�̌�납�� n �ڂ�Q�Ƃ���
template<class T>
T&	from_back(deque<T>& iDeque, int n) {
	assert(n>=0 && n<iDeque.size());
	return	iDeque[iDeque.size()-1-n];
}

// �t�@�C������string
bool	string_from_file(string& o, const string& iFileName);
bool	string_to_file(const string& i, const string& iFileName);
// �t�@�C������strvec
bool	strvec_from_file(strvec& o, const string& iFileName);
bool	strvec_to_file(const strvec& i, const string& iFileName);
// �t�@�C������strmap
bool	strmap_from_file(strmap& o, const string& iFileName, const string& dlmt);
bool	strmap_to_file(const strmap& i, const string& iFileName, const string& dlmt);
// �S�p���p��킸�ꕶ���擾
string	get_a_chr(const char*& p);

// �����P�ʂɕ���
template<class T>
int	split(const string& i, T& o) {
	const char* p=i.c_str();
	while ( *p != '\0' )
		o.push_back(get_a_chr(p));
	return	o.size();
}

// �P��P�ʂɕ��� max_words�͍ő�؂�o���P�ꐔ�B0�Ȃ琧�����Ȃ��B
template<class T>
int	split(const string& i, const string& dlmt, T& o, int max_words=0) {
	set<string>	dlmt_set;
	const char* p=dlmt.c_str();
	while ( *p != '\0' )
		dlmt_set.insert(get_a_chr(p));

	if ( dlmt_set.empty() )
		return	split(i, o);

	if ( max_words==1 ) {
		o.push_back(i);
		return	1;
	}

	string	word;
	p=i.c_str();
	while ( *p != '\0' ) {
		string	c = get_a_chr(p);
		if ( dlmt_set.find(c) != dlmt_set.end() ) {
			if ( word.size() > 0 ) {
				o.push_back(word);

				if ( max_words>0 && o.size()+1 >= max_words ) {	// �P�ꐔ����
					word = p;
					break;
				} else
					word="";
			}
		} else
			word += c;
	}
	if ( word.size() > 0 )
		o.push_back(word);

	return	o.size();
}

inline int	splitToSet(const string& iString, set<string>& oSet, int iDelimiter) {
	oSet.clear();
	const char* start=iString.c_str();
	const char* p=start;

	for ( ; *p!='\0' ; ++p ) {
		if ( *p==iDelimiter ) {
			if ( start<p )
				oSet.insert( string(start, p-start) );
			start = p+1;
		}
	}
	if ( start<p )
		oSet.insert(start);
	return	oSet.size();
}



// map����key�̈ꗗ��擾
template<typename C, typename K, typename V>
int	keys(const map<K,V>& iMap, C& oContainer) {
	oContainer.clear();
	for ( typename map<K,V>::const_iterator i=iMap.begin() ; i!=iMap.end() ; ++i)
		oContainer.push_back(i->first);
	return	oContainer.size();
}
// map����key�̈ꗗ��擾
template<typename C, typename K, typename V>
C	keys(const map<K,V>& iMap) {
	C	theContainer;
	keys<C,K,V>(iMap, theContainer);
	return	theContainer;
}

// map����value�̈ꗗ��擾
template<typename C, typename K, typename V>
int	values(const map<K,V>& iMap, C& oContainer) {
	oContainer.clear();
	for ( typename map<K,V>::const_iterator i=iMap.begin() ; i!=iMap.end() ; ++i)
		oContainer.push_back(i->second);
	return	oContainer.size();
}
// map����value�̈ꗗ��擾
template<typename C, typename K, typename V>
C	values(const map<K,V>& iMap) {
	C	theContainer;
	keys<C,K,V>(iMap, theContainer);
	return	theContainer;
}

// �R���e�i�v�f��P���string�Ɍ����Bdlmt��Ԃɋ��ށB�Ԓl��string�̑傫��
template<class T>
int	combine(string& out, const T& in, const string& dlmt="", bool add_dlmt_on_final=false) {
	typename T::const_iterator i=in.begin();
	if ( add_dlmt_on_final ) {
		for (; i!=in.end() ;++i) {
			out += *i;
			out += dlmt;
		}
	}
	else {
		int	size=in.size();
		for (int n=0 ; n<size ; ++n,++i) {
			out += *i;
			if (n<size-1)
				out += dlmt;
		}
	}
	return	out.size();
}
template<class T>
string	combine(const T& in, const string& dlmt="", bool add_dlmt_on_final=false) {
	string	s;
	combine(s, in, dlmt, add_dlmt_on_final);
	return	s;
}



// �t�@�C���̑��݂�m�F
bool	is_exist_file(const string& iFileName);

// str�̐擪��head�ł����true
inline bool	compare_head(const string& str, const string& head) { return str.compare(0, head.size(), head)==0; }
inline bool	compare_head(const char* str, const char* head) { return strncmp(str, head, strlen(head))==0; }
// str�̖�����tail�ł����true
bool	compare_tail(const string& str, const string& tail);	

// target���̍ŏ���find�����񂪏o������ʒu��Ԃ��B���p�S�p���Ή�
const char*	strstr_hz(const char* target, const char* find);

// ����̈ꕶ�����Ō�ɏo������ʒu��Ԃ�
const char*	find_final_char(const char* str, char c);
inline char* find_final_char(char* str, char c) { return const_cast<char*>(find_final_char(static_cast<const char*>(str), c)); }
/*#include	<mbctype.h>	// for _ismbblead,_ismbbtrail
template<class T>
T*	find_final_char(T* str, const T& c) {
	T* last=NULL;
	for (T* p=str ; *p!='\0' ; p+=_ismbblead(*p)?2:1)
		if ( *p==c )
			last=p;
	return	last;
}*/

// �ȈՈÍ���
string	encode(const string& s);
string	decode(const string& s);

// �o�C�i���f�[�^��16�i���\���𑊌ݕϊ�
string	binary_to_string(const byte* iArray, int iLength);
void	string_to_binary(const string& iString, byte* oArray);
								// oArray �ɂ� iString.size()/2 �o�C�g�̃�������m�ۂ��Ă��邱�ƁB


// �R���e�i�������A���ݗL����bool�ŕԂ�
template<typename C, typename E>
bool exists(const C& iC, const E& iE) {
	for ( typename C::const_iterator i=iC.begin() ; i!=iC.end() ; ++i )
		if ( *i == iE )
			return	true;
	return	false;
}
/*
template<typename K, typename V, typename E>
inline bool exists< map<K, V> >(const map<K,V>& iC, const E& iE) {
	return	(iC.find(iE) != iC.end());
}

template<typename T, typename E>
inline bool exists< set<T> >(const set<T>& iC, const E& iE) {
	return	(iC.find(iE) != iC.end());
}
*/

// xor �t�B���^
void	xor_filter(byte* iArray, int iLength, byte _xor);

// �t���p�X�̕���
string	get_file_name(const string& str);
string	get_folder_name(const string& str);
string	get_extention(const string& str);
// �g���q��ύX������̂�Ԃ�
string	set_extention(const string& str, const char* new_ext);
inline string	set_extention(const string& str, const string& new_ext) { return set_extention(str, new_ext.c_str()); }
// �g���q��Ԃ�
inline string	get_extension(const string& str) {
	const char* p = find_final_char(str.c_str(), '.');
	return p ? p+1 : "";
}
// �t�@�C����������ύX������̂�Ԃ�
string	set_filename(const string& str, const char* new_filename);
inline string	set_filename(const string& str, const string& new_filename) { return set_filename(str, new_filename.c_str()); }

// �o��
ostream& operator<<(ostream& o, const strvec& i);
ostream& operator<<(ostream& o, const strmap& i);
ostream& operator<<(ostream& o, const strintmap& i);
inline ostream& operator<<(ostream& o, const strpairvec& i) {
	for ( strpairvec::const_iterator p=i.begin() ; p!=i.end() ; ++p )
		o << p->first << ": " << p->second << endl;
	return	o;
}


// ����
istream& operator>>(istream& i, strvec& o);
istream& operator>>(istream& i, strmap& o);
istream& operator>>(istream& i, strintmap& o);

/*
// �Ȃ񂩁A����܂�g���Ă��Ȃ���́B�B

// �t�@�C���A�N�Z�T
template<class T>
bool	put(const char* iFileName, T& i) {
	ofstream	o(iFileName);
	if ( !o.is_open() )
		return	false;
	o<<i;
	return	true;
}
template<class T>
bool	get(const char* iFileName, T& o) {
	ifstream	i(iFileName);
	if ( !i.is_open() )
		return	false;
	i>>o;
	return	true;
}


template<class T>
bool	read_text_file(
	T& oT,	// require "push_back" method. vector, list, deque, ...
	const string& iFileName) 
{
	ifstream	in(iFileName.c_str());
	if ( !in.is_open() )
		return	false;
	while ( in.peek() != EOF ) {
		// �P�s�ǂݍ���
		strstream	line;
		int	c;
		while ( (c=in.get()) != '\n' && c!=EOF)
			line.put(c);
		line.put('\0');

		// �s�X�g���[����Œ�A�e�s�ɑ΂�����
		char* p=line.str();
		oT.push_back(p);
		// �s�X�g���[���̌Œ����
		line.rdbuf()->freeze(0);
	}
	in.close();
	return	true;
}

template<class T>
bool	write_text_file(
	const T& iT,	// �R���e�i��begin()��end()��ForwardAccessIterator���v��B�ו��ɂ� operator<< ���v��B������string���ȁB
	const string& iFileName)
{
	ofstream	out(iFileName.c_str());
	if ( !out.is_open() )
		return	false;
	T::const_iterator	it;
	for (it=iT.begin() ; it!=iT.end() ; ++it)
		out << *it << endl;
	out.close();
	return	true;
}
*/


// 32bit����
extern void	randomize(int);
#ifndef POSIX
extern int	random();
#endif

// printf�݊��ŕ�����𐶐����Astring�^�ŕԂ��B
extern string stringf(const char* iFormat, ...);

// �C�ӂ̌���l�̌ܓ�����Bfigure�ȗ����͏������ʂ�l�̌ܓ��B
template<typename T>
T	round(T num, const int figure=0) {
	if ( figure>=0 ) {
		for (int i=0 ; i<figure ; ++i) { num *= 10; }
		num = int(num + static_cast<T>(0.5));
		for (int i=0 ; i<figure ; ++i) { num /= 10; }
	}
	else {
		for (int i=0 ; i>figure ; --i) { num /= 10; }
		num = int(num + static_cast<T>(0.5));
		for (int i=0 ; i>figure ; --i) { num *= 10; }
	}
	return	num;
}






// �f�B���N�g����؂�
#ifdef POSIX
  #define DIR_CHAR '/'
  inline string unify_dir_char(const string& i_str) { string r=i_str; replace(r, "\\", "/"); return r; }
#else
  #define DIR_CHAR '\\'
  inline string unify_dir_char(const string& i_str) { string r=i_str; replace(r, "/", "\\"); return r; }
#endif


// DOS/Windows��HTTP�Ȃǂɂ�����s�f���~�^
static const string CRLF = "\x0d\x0a";


#endif	//	STLTOOL_H
