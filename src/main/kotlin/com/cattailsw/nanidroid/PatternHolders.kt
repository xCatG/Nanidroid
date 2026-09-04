package com.cattailsw.nanidroid

import java.util.regex.Pattern

/** Shared, Java-callable regular expressions for ghost descriptors and Sakura Script. */
object PatternHolders {
    @JvmField val element = Pattern.compile("element(\\d+),(\\w*),(\\S*),(-?\\d+),(-?\\d+)$")
    @JvmField val collision = Pattern.compile("collision([+-]?\\d+),\\s*([+-]?\\d+),\\s*([+-]?\\d+),\\s*([+-]?\\d+),\\s*([+-]?\\d+),(.*)$", Pattern.CASE_INSENSITIVE)
    @JvmField val interval = Pattern.compile("(\\d+)interval\\d*,(\\S*)$")
    @JvmField val animation_interval = Pattern.compile("animation(\\d+).interval.(\\S*)$")
    @JvmField val pattern = Pattern.compile("(\\d+)pattern(\\d+),(\\d*|-1),(\\d*),(\\w*),?(-?\\d*),(-?\\d*)$")
    @JvmField val pattern_base = Pattern.compile("(\\d+)pattern(\\d+),(\\d*|-1),(\\d*),(\\w*)$")
    @JvmField val pattern_alt = Pattern.compile("(\\d+)pattern(\\d+),0,0,alternativestart,\\[(\\S*)\\]$")
    @JvmField val animation = Pattern.compile("animation(\\d+).pattern(\\d+),(alternativestart,\\((\\S*)\\)$|(\\w*),(-?\\d*|-1),(-?\\d*),(-?\\d*),(-?\\d*)$)")
    @JvmField val animation_base = Pattern.compile("animation(\\d+).pattern(\\d+),(\\w*),((-1)|(-1),(\\d*))$")
    @JvmField val option = Pattern.compile("(\\d+)[oO]ption,(\\S*)$")
    @JvmField val sqbracket_half_number = Pattern.compile("^\\[(half|\\d+%?)\\]")
    @JvmField val surface_ptrn = Pattern.compile("^\\[(-?\\d+)\\]|^(\\d{1})")
    @JvmField val ani_ptrn = Pattern.compile("^\\[(\\d+)(|,\\w+)\\]")
    @JvmField val balloon_ptrn = Pattern.compile("^\\[(-?\\d+)\\]|^(\\d{1})")
    @JvmField val shiori_res_header_ptrn = Pattern.compile("^(\\w*)/(\\d*).(\\d*) (\\d{3})\\s*([[\\w][\\s]]*)$")
    @JvmField val comment_ptrn = Pattern.compile("(//|;).*$")
    @JvmField val q_choice_ptrn = Pattern.compile("\\\\q\\[([^,]*),([[^,]&&[^\\]]]*),?([^\\]]*?)\\]{1}")
}
