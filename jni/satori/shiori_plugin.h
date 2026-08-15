/* -*- c++ -*- */
#ifndef POSIX
#  include <windows.h>	// HMODULE,BOOL,HGLOBALとか
#endif

#include "SaoriClient.h"

// プラグインの総合管理
class	ShioriPlugins {

	struct CallData {	// 呼び出し名ごとの情報
		string	mDllPath;
		strvec	mPreDefinedArguments;
		bool	mIsBasic;
	};
	struct DllData {	// DLLごとの情報
		SaoriClient	mSaoriClient;
		int	mRefCount;
	};
	map<string, CallData>	mCallData;	// 呼び出し名；呼び出し名ごとの情報
	map<string, DllData>	mDllData;	// DLLのフルパス；DLLごとの情報

	string	mBaseFolder;
#ifdef POSIX
	string mPosixFallbackPath;
	bool mPosixFallbackAlways;
#endif


public:
	ShioriPlugins()
#ifdef POSIX
		: mPosixFallbackAlways(false)
#endif
	{}
#ifdef POSIX
	void configure_posix_fallback(const string& path, bool always) {
		mPosixFallbackPath = path;
		mPosixFallbackAlways = always;
	}
#endif
	bool	load(const string& iBaseFolder);
	bool	load_a_plugin(const string& iPluginLine);

	string	request(const string& iCallName, const strvec& iArguments, strvec& oResults, const string& iSecurityLevel);
	void	unload();

	bool	find(string iCallName) {
		return (mCallData.find(iCallName) != mCallData.end() );
	}
};

