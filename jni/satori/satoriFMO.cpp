#include	"satori.h"
#include	"../_/FMO.h"

bool readSakuraFMO(map<string, strmap>& oSakuraFMOMap)
{
	vector<char> v;
	{
		FMO	fmo;
		if ( !fmo.open(FILE_MAP_READ, FALSE, "Sakura") ) {
			sender << "FMO can't open." << endl;
			return	false;
		}

		LPVOID	p = fmo.map();
		if ( p==NULL ) {
			sender << "FMO can't mapping." << endl;
			return	false;
		}

		int size = *((long*)p);
		v.resize(size+1);
		v[size] = '\0';
		memcpy(v.begin(), p, size);
		fmo.unmap(p);
		fmo.close();
	}


	strvec	lines;
	split(v.begin()+4, ret_dlmt, lines);
	for( strvec::iterator i=lines.begin() ; i!=lines.end() ; ++i ) {
		//sender << "■" << *i << endl;
		strvec	MD5andDATA;
		if ( split(*i, ".", MD5andDATA, 2) != 2 )
			continue;

		strvec	ENTRYandVALUE;
		if ( split(MD5andDATA[1], byte1_dlmt, ENTRYandVALUE) != 2 )
			continue;

		(oSakuraFMOMap[ MD5andDATA[0] ])[ ENTRYandVALUE[0] ] = ENTRYandVALUE[1];
	}

	
	for( map<string, strmap>::iterator i=oSakuraFMOMap.begin() ; i!=oSakuraFMOMap.end() ; ++i ) {
		strmap&	m = i->second;
		sender << i->first << endl;
		for( strmap::iterator j=m.begin() ; j!=m.end() ; ++j ) {
			sender << "　" << j->first << ":" << j->second << endl;
		}
	}/**/
	
	return	true;
}


bool	Satori::updateGhostsInfo() {

	if ( mExeFolder=="" )
		return	false;

	map<string, strmap>	theSakuraFMO;
	if ( !readSakuraFMO(theSakuraFMO) )
		return	false;

	ghosts_info.clear();
	ghosts_info.push_back( strmap() );
	for( map<string, strmap>::iterator i=theSakuraFMO.begin() ; i!=theSakuraFMO.end() ; ++i ) {
		strmap&	m = i->second;
		bool	isSelfData = false;// 自分自身のデータか？

		if ( m.find("ghostpath") != m.end() ) {
			if ( stricmp((m["ghostpath"]+"ghost\\master\\").c_str(), mBaseFolder.c_str())==0 )
				isSelfData = true;
		}
		else {
			if ( stricmp(m["path"].c_str(), mExeFolder.c_str())==0 )
				isSelfData = true;
		}

		if ( isSelfData )
			ghosts_info[0]=m;	
		else
			ghosts_info.push_back(m);	// 他は順次末尾に
	}

	return	true;
}
