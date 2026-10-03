#include "SakuraCS.h"

string cut_token(string& io_target, const string& i_delimiter)
{
	string word;
	cut_token(io_target, i_delimiter, word);
	return word;
}

bool cut_token(string& io_target, const string& i_delimiter, string& o_token)
{
	string::size_type pos = io_target.find(i_delimiter);
	if ( pos == string::npos )
	{
		o_token = io_target;
		io_target = "";
		return false;
	}
	else
	{
		o_token = io_target.substr(0, pos);
		pos += i_delimiter.size();
		io_target = io_target.substr(pos);
		return true;
	}
}

