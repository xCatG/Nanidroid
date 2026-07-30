
#include <windows.h>
#include <string>
using std::string;


bool direct_sstp(
	const string& i_script = "\\0\\s[0]‚É‚±‚É‚±B\\1\\s[10]‚É‚±‚É‚±B\\e",
	const string& i_client_name = "‘—MÒ‚³‚ñ",
	HWND i_client_window = NULL);

#include "SakuraClient.h"
/*

class SSTPClient : public SakuraClient
{
public:
	SSTPClient() {}
	virtual ~SSTPClient() {}
	
	virtual string request(const string& i_request_string);

	// ‚ñ`

};
*/
