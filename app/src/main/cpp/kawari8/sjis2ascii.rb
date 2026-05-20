#!/bin/env ruby

# ソースコード中のShiftJIS文字列をASCIIへエンコードする

require "kconv"

while ($<.gets)
	line=Kconv::tosjis($_)

	kanji1=0
	line.each_byte do |c|
		if kanji1==0
			if (((c^0x20)-0xa1)&0xff)<=0x3b
				# SJIS
				# 0x00-0x7f ASCII
				# 0x80-0x9f,0xe0-0xfc いわゆる全角1バイト目
				# 0xa0-0xdf いわゆる半角カナ
				# ちなみに2バイト目は0x40-0xfc
				kanji1=c
			else
				putc c
			end
		else
#			printf "\\x%x\\x%x",kanji1,c
			printf "\\%03o\\%03o",kanji1,c
			kanji1=0
		end
	end

end

