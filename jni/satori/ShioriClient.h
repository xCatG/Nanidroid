#include "SakuraDLLClient.h"

// SHIORI/3.0クライアント
class ShioriClient : public SakuraDLLClient
{
public:
	ShioriClient() {}
	virtual ~ShioriClient() {}

	bool load(
		const string& i_sender,
		const string& i_charset,
		const string& i_base_folder,
		const string& i_fullpath);

	int ShioriClient::request(
		const string& i_id, // OnBootとか
		const vector<string>& i_references, // Reference?
		bool i_is_secure, // SecurityLevel
		string& o_value, // 栞が返したさくらスクリプトや文字列リソース
		vector<string>& o_references // 複数戻り値。通常はOnCommunicateでしか使わない
		);
};