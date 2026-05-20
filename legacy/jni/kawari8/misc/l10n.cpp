//---------------------------------------------------------------------------
//
// Localization支援
//
//      Programed by Kouji.U (sky) / NAKAUE.T
//
//  2002.03.17  Phase 7.9.0   ctow()をexprから移動
//  2005.10.16  Phase 8.2.4   ctow()の式評価順序依存箇所を変更(芝やん)
//
//---------------------------------------------------------------------------
#include "config.h"
//---------------------------------------------------------------------------
#include "misc/l10n.h"
//---------------------------------------------------------------------------
#include "misc/misc.h"
//---------------------------------------------------------------------------
// SJIS Wide Char String -> SJIS Char String
std::string wtoc(const std::wstring& ws)
{
    unsigned int max = ws.length();
    std::string ret;
    for (unsigned int i=0; i<max; i++){
        if (ws[i] & (unsigned short)0xff00){
            ret += static_cast<unsigned char>(
                (ws[i] & (unsigned short)0xff00) >> 8);
            ret += static_cast<unsigned char>(
                (ws[i] & (unsigned short)0x00ff));
        }else{
            ret += static_cast<unsigned char>(
                (ws[i] & (unsigned short)0x00ff));
        }
    }
    return ret;
}
//---------------------------------------------------------------------------
// SJIS Char String -> SJIS Wide Char String
std::wstring ctow(const std::string& s)
{
    unsigned int max = s.length();
    std::wstring ret;
    for (unsigned int i=0; i<max; i++){
        if (iskanji1st(s[i]) && (i<max-1)){
            // 変更点 : i++ の位置を変更
            ret +=
                (static_cast<unsigned char>(s[i]) << 8) | static_cast<unsigned char>(s[i + 1]);
            i++;
        }else{
            ret += static_cast<unsigned char>(s[i]);
        }
    }
    return ret;
}
//---------------------------------------------------------------------------
