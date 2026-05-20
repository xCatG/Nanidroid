#!/bin/env ruby

# ソースファイルの依存関係を解析する


def sh_find(path,names)
	finded=Array::new

	Dir::foreach(path) {|f|
		if FileTest::directory?(f)
			if f!="." && f!=".."
				finded.concat(sh_find(path+"/"+f,names))
			end
		else
			names.each {|name|
				if (f=~name)!=nil
					finded.push(path+"/"+f)
				end
			}
		end
	}

	return finded
end


def WildToRegexp(wildcardstr)
	regexpstr=wildcardstr.gsub(/\./,"\\.")
	regexpstr.gsub!(/\*/,".*")
	regexpstr.gsub!(/\?/,".")
	return regexpstr
end


def sh_grep(file,patterns)
	finded=Array::new

	IO::foreach(file) {|line|
		line.chomp!("\n")
		line.chomp!("\r")
		flag=false
		patterns.each {|pattern|
			if (line=~pattern)!=nil
				flag=true
			end
		}
		if flag
			finded.push(line)
		end
	}

	return finded
end


def makedepend(path,withn2a=false)
	sh_find(path,[Regexp::new(WildToRegexp("*.cpp")),Regexp::new(WildToRegexp("*.h"))]).each{|f|
		if !withn2a
			print f
		else
			print (f.sub(/\.([^\.])[^\.]*$/) do "."+$1+"xx" end)
		end
		print" :"

		sh_grep(f,[/^[ \t]*#[ \t]*include[ \t]"/]).each{|inc|
			inc.sub!(/^[^"]*"/,"")
			inc.sub!(/".*/,"")
			if withn2a
				inc.sub!(/\.([^\.])[^\.]*$/) do "."+$1+"xx" end
			end
			print " "+path+"/"+inc
		}
		print"\n\n"
	}
end

n2aflag=false
path="."
ARGV.each do |arg|
	case arg
	when "--with-sjis2ascii"
		n2aflag=true
	when /^[^-]/
		path=arg
	else
		$stderr.puts "invalid option -- "+arg
		exit 1
	end
end

makedepend(path,n2aflag)

