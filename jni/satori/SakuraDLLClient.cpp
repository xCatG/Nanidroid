#include "SakuraDLLClient.h"
#include "../_/Sender.h"
#include <assert.h>
#ifdef POSIX
#  include <dlfcn.h>
#  include <string.h>
#  include <stdlib.h>
#  define FALSE 0
#endif

extern void	PluginError(const string& str);

SakuraDLLClient::SakuraDLLClient()
{
	mModule = NULL;
	mLoad = NULL;
	mRequest = NULL;
	mUnload = NULL;
}

SakuraDLLClient::~SakuraDLLClient()
{
	unload();
}

string	SakuraDLLClient::request(const string& iRequestString)
{
	if ( mRequest==NULL )
	{
		PluginError("SakuraDLLClient::request: ロードしていないライブラリにrequestしようとしました。");
		return	"";
	}

#ifdef POSIX
	long len = iRequestString.length();
	char* h = static_cast<char*>(malloc(len));
	memcpy(h, iRequestString.c_str(), len);
	h = mRequest(h, &len);
	string theResponse(static_cast<char*>(h), len);
	free(h);
	return theResponse;
#else
	long	len = iRequestString.length();
	HGLOBAL h = ::GlobalAlloc(GMEM_FIXED, len);
	memcpy(h, iRequestString.c_str(), len);
	h = mRequest(h, &len);
	string	theResponse((char*)h, len);
	::GlobalFree(h);
	return theResponse;
#endif
}

// バージョン取得。GET Versionして"SAORI/1.0" みたいのを返す。
string SakuraDLLClient::get_version(const string& i_security_level)
{
	strpairvec data;
	data.push_back( strpair("Sender", m_sender) );
	data.push_back( strpair("Charset", m_charset) );
	data.push_back( strpair("SecurityLevel", i_security_level) );

	string r_protocol, r_protocol_version;
	strpairvec r_data;
	this->SakuraClient::request(
		m_protocol,
		m_protocol_version,
		"GET Version",
		data,
		r_protocol,
		r_protocol_version,
		r_data);

	return r_protocol + "/" + r_protocol_version;
}

// リクエストを送り、レスポンスを受け取る。戻り値はリターンコード。
int SakuraDLLClient::request(
	const string& i_command,
	const strpairvec& i_data,
	strpairvec& o_data)
{
	string r_protocol, r_protocol_version;
	int return_code = this->SakuraClient::request(
		m_protocol,
		m_protocol_version,
		i_command,
		i_data,
		r_protocol,
		r_protocol_version,
		o_data);
	return return_code;
}

void	SakuraDLLClient::unload()
{
#ifdef POSIX
    if (mModule != NULL) {
	    dlclose(mModule);
	}
#else
	if ( mUnload != NULL )
		mUnload();
	if ( mModule != NULL )
		::FreeLibrary(mModule);
#endif
	mModule = NULL;
	mLoad = NULL;
	mRequest = NULL;
	mUnload = NULL;
}



bool	SakuraDLLClient::load(
	const string& i_sender,
	const string& i_charset,
	const string& i_protocol,
	const string& i_protocol_version,
	const string& i_work_folder,
	const string& i_dll_fullpath)
{
	unload();

	m_sender = i_sender;
	m_charset = i_charset;
	m_protocol = i_protocol;
	m_protocol_version = i_protocol_version;
	string work_folder = unify_dir_char(i_work_folder);
	string dll_fullpath = unify_dir_char(i_dll_fullpath);

	sender << "SakuraDLLClient::load '" << dll_fullpath << "' ...";
#ifdef POSIX
	mModule = dlopen(dll_fullpath.c_str(), RTLD_LAZY);
	if (mModule == NULL) {
	    sender << "failed." << endl;
	    PluginError(dlerror());
	    return false;
	}
#else
	mModule = ::LoadLibrary(dll_fullpath.c_str());
	if ( mModule==NULL ) {
		sender << "failed." << endl;
		PluginError(dll_fullpath + ": LoadLibraryで失敗。");
		return	false;
	}
#endif

#ifdef POSIX
	mLoad = (bool (*)(char*, long))dlsym(mModule, "load");
	mRequest = (char* (*)(char*, long*))dlsym(mModule, "request");
	mUnload = (bool (*)())dlsym(mModule, "unload");
#else
	mLoad = (BOOL (*)(HGLOBAL, long))::GetProcAddress(mModule, "load");
	mRequest = (HGLOBAL (*)(HGLOBAL, long*))::GetProcAddress(mModule, "request");
	mUnload = (BOOL (*)())::GetProcAddress(mModule, "unload");
#endif
	if ( mRequest==NULL )
	{
		sender << "failed." << endl;
		unload();
		PluginError(dll_fullpath + ": requestがエクスポートされていません。");
		return	false;
	}
	if ( mLoad!=NULL )
	{
		long len = work_folder.length();
#ifdef POSIX
		char* h = static_cast<char*>(malloc(len));
#else
		HGLOBAL h = ::GlobalAlloc(GMEM_FIXED, len);
#endif
		memcpy(h, work_folder.c_str(), len);
		if ( mLoad(h, len) == FALSE )
		{
			sender << "failed." << endl;
			unload();
			PluginError(dll_fullpath + ": load()がFALSEを返しました。");
			return	false;
		}
	}

	sender << "succeed." << endl;
	return	true;
}

