//---------------------------------------------------------------------------
//
// TMMap - mapのようにアクセスできるmultimap -
//
//      Programed by NAKAUE.T (Thanks: しの、酔狂)
//      2005.06.21  gcc3.xのwarning対応 (suikyo)
//
//---------------------------------------------------------------------------
#ifndef TMMAP_H
#define TMMAP_H
//---------------------------------------------------------------------------
#include <vector>
#include <map>
#include <cstring>
//---------------------------------------------------------------------------
#ifndef FORCE_TYPENAME
#	undef typename
#endif
//---------------------------------------------------------------------------
template<class KeyType,class DataType> class TMMap
 : public std::multimap<KeyType,DataType> {
public:

	DataType& operator[](const KeyType& key)
	{
//		iterator it=lower_bound(key);
//		std::multimap<KeyType,DataType>::iterator it;
	  typename std::multimap<KeyType, DataType>::iterator it=std::multimap<KeyType, DataType>::lower_bound(key);
		// 2001/12/16 suikyo@yk.rim.or.jp : imortal entry bug
//		if(it==end()) it=insert(pair<KeyType,DataType>(key,DataType()));
//		if(it==upper_bound(key)) it=insert(pair<const KeyType,DataType>(key,DataType()));
	  if(it==std::multimap<KeyType, DataType>::upper_bound(key)) it=insert(typename TMMap<KeyType,DataType>::value_type(key,DataType()));
		return((*it).second);
	}
/*
	const DataType& operator[](const KeyType& key) const
	{
		static DataType dummy;
		iterator it=lower_bound(key);
		if(it==end()) return(dummy);
		return((*it).second);
	}
*/
	void Add(const KeyType& key,const DataType& data)
	{
//		insert(pair<const KeyType,DataType>(key,data));
		insert(typename TMMap::value_type(key,data));
	}

};
//---------------------------------------------------------------------------
#endif
