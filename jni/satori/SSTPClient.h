
#include <windows.h>
#include <string>
using std::string;


bool direct_sstp(
	const string& i_script = "\\0\\s[0]\x82\xC9\x82\xB1\x82\xC9\x82\xB1\x81\x42\\1\\s[10]\x82\xC9\x82\xB1\x82\xC9\x82\xB1\x81\x42\\e",
	const string& i_client_name = "\x91\x97\x90\x4D\x8E\xD2\x82\xB3\x82\xF1",
	HWND i_client_window = NULL);

#include "SakuraClient.h"
/*

class SSTPClient : public SakuraClient
{
public:
	SSTPClient() {}
	virtual ~SSTPClient() {}
	
	virtual string request(const string& i_request_string);

	// ん～

};
*/
