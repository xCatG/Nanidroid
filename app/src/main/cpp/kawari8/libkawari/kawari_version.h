//---------------------------------------------------------------------------
//
// "華和梨" for あれ以外の何か以外の何か
// バージョン表記
//
//      Programed by Suikyo.
//
//  2002.04.13  Phase 8.0.0   移植
//
//---------------------------------------------------------------------------
#ifndef KAWARI_VERSION_H
#define KAWARI_VERSION_H
//---------------------------------------------------------------------------
// SHIORIラッパー

#define KAWARI_NAME		"KAWARI.kdt"
#define KAWARI_AUTHOR	"KawariDeveloperTeam"	// 著作者(英数字だけ)
#define KAWARI_MAJOR	"8"		// メジャー番号(ピリオド無し、数字だけ)
#define KAWARI_MINOR	"2"		// マイナー番号(ピリオド無し、数字だけ)
#define KAWARI_SUBMINOR	"8"		// バグフィックス(ピリオド無し、数字だけ)

//---------------------------------------------------------------------------
// コア

// 華和梨コア・コードネーム
#define KAWARI_CORE_CODENAME	"K.I.U."

// 華和梨コア・バージョン (過去に遡って振り直し)
//   1.0.0 = Phase 0.4
//   1.1.0 = Phase 0.42
//   2.0.0 = Phase 5.1
//   2.1.0 = Phase 5.4
//   2.2.0 = Phase 6.1
//   2.3.0 = Phase 7.0
//   3.0.0 = Phase 8.0.0
//   3.1.0 = Phase 8.1.0
//   3.2.0 = Phase 8.2.0
#define KAWARI_CORE_VERSION	"3.2.8"

// 華和梨コア・クレジット(適当に改行で区切って連名)
#define KAWARI_CORE_CREDITS \
	" Original KAWARI Developer Team :\n" \
"    Meister(original works)/Nise-Meister/Sato/Shino/Suikyo\n" \
" Contributers :\n" \
"    ebisawa, MDR, sanori, whoami, ABE, phonohawk, Shiba-yan, PaulLiu\n" \
" Libraries :\n" \
"    Mersenne Twister Library\n" \
"       <http://www.math.keio.ac.jp/~matumoto/mt.html>\n" \

// 華和梨ライセンス (modified BSD(FreeBSD) style license)
#define KAWARI_CORE_LICENSE \
"Copyright (C) 2001-2008 KAWARI Development Team\n" \
"(Meister(original works)/Nise-Meister/Sato/Shino/Suikyo)\n" \
"Contributers (Ebisawa/MDR/Sanori/Whoami/ABE/phonohawk/Shiba-yan/PaulLiu)\n" \
"All rights reserved.\n" \
"\n" \
"Redistribution and use in source and binary forms, with or without \n" \
"modification, are permitted provided that the following conditions are \n" \
"met: \n" \
"\n" \
"1. Redistributions of source code must retain the above copyright \n" \
"   notice, this list of conditions and the following disclaimer \n" \
"   as the first lines of this file unmodified." \
"\n" \
"2. Redistributions in  binary form must reproduce the above copyright \n" \
"   notice, this list of conditions and the following disclaimer in the \n" \
"   documentation and/or other materials provided with the distribution. \n" \
"\n" \
"THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR \n" \
"IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED \n" \
"WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE \n" \
"DISCLAIMED. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, \n" \
"INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES \n" \
"(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR \n" \
"SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) \n" \
"HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, \n" \
"STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN \n" \
"ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE \n" \
"POSSIBILITY OF SUCH DAMAGE.\n" \
"\n" \
"License of MT19937 library is following.\n" \
"Redistribution and use in source and binary forms, with or without\n" \
"modification, are permitted provided that the following conditions\n" \
"are met:\n" \
"\n" \
"  1. Redistributions of source code must retain the above copyright\n" \
"     notice, this list of conditions and the following disclaimer.\n" \
"\n" \
"  2. Redistributions in binary form must reproduce the above copyright\n" \
"     notice, this list of conditions and the following disclaimer in the\n" \
"     documentation and/or other materials provided with the distribution.\n" \
"\n" \
"  3. The names of its contributors may not be used to endorse or promote \n" \
"     products derived from this software without specific prior written \n" \
"     permission.\n" \
"\n" \
"THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS\n" \
"\"AS IS\" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT\n" \
"LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR\n" \
"A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR\n" \
"CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,\n" \
"EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,\n" \
"PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR\n" \
"PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF\n" \
"LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING\n" \
"NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS\n" \
"SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.\n"

#endif // KAWARI_VERSION_H
