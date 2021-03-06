#include "Satori.h"

const char* gSatoriName = "Satori";
const char* gSatoriNameW = "里々";
const char* gSatoriCraftman = "Yagi Kushigahama";
const char* gSatoriCraftmanW = "櫛ヶ浜やぎ";
const char* gSatoriVersion = "phase 123";
const char* gShioriVersion = "3.0";
const char* gSaoriVersion = "1.0";



#ifdef SATORI_DLL
	// Satoriの唯一の実体
	Satori gSatori;
	SakuraDLLHost* SakuraDLLHost::m_dll = &gSatori;
#else
	SakuraDLLHost* SakuraDLLHost::m_dll = NULL;
#endif // SATORI_DLL


// エスケープ文字列
const char escaper::sm_escape_sjis_code[3]={(char)0x9e,(char)0xff,0x00};

// 引数文字列を受け取り、メンバに格納し、「エスケープされた文字列」を返す。
string escaper::insert(const string& i_str)
{
	m_id2str.push_back(i_str);
	//m_str2id[i_str] = m_id2str.size()-1;
	return string() + sm_escape_sjis_code + itos(m_id2str.size()-1) + " ";
}

// 対象文字列中に含まれる「エスケープされた文字列」を元に戻す。
void escaper::unescape(string& io_str)
{
	const int	max = m_id2str.size();
	for (int i=0 ; i<max ; ++i)
		replace(io_str, string(sm_escape_sjis_code)+itos(i)+" ", m_id2str[i]);
}

// メンバをクリア
void escaper::clear()
{
	//m_str2id.clear();
	m_id2str.clear();
}


// 式を評価し、結果を真偽値として解釈する
bool Satori::evalcate_to_bool(const Condition& i_cond)
{
	string r;
	if ( !calculate(i_cond.c_str(), r) )
	{
		// 計算失敗
		return false;
	}
	return  ( r!="0" && r!="０" );
}
