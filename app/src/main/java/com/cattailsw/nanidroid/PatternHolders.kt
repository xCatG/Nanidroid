package com.cattailsw.nanidroid

import java.util.regex.Pattern

object PatternHolders {
    @JvmField val element: Pattern = Pattern.compile("element(\\d+),(\\w*),(\\S*),(-?\\d+),(-?\\d+)$")
    @JvmField val collision: Pattern = Pattern.compile("collision(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),([\\w|-]*)$")
    @JvmField val point: Pattern = Pattern.compile("point.(\\w*\\.*)center([xXyY]{1}),(\\d+$)")

    @JvmField val interval: Pattern = Pattern.compile("(\\d+)interval\\d*,(\\S*)$")
    @JvmField val animation_interval: Pattern = Pattern.compile("animation(\\d+).interval.(\\S*)$")
    // XpatternX,number|-1,wait,overlay,x,y
    @JvmField val pattern: Pattern = Pattern.compile("(\\d+)pattern(\\d+),(\\d*|-1),(\\d*),(\\w*),?(-?\\d*),(-?\\d*)$")
    @JvmField val pattern_base: Pattern = Pattern.compile("(\\d+)pattern(\\d+),(\\d*|-1),(\\d*),(\\w*)$")
    @JvmField val pattern_alt: Pattern = Pattern.compile("(\\d+)pattern(\\d+),0,0,alternativestart,\\[(\\S*)\\]$")
    // animationX.patternX,overlay,number|-1,wait,x,y
    @JvmField val animation: Pattern = Pattern.compile("animation(\\d+).pattern(\\d+),(alternativestart,\\((\\S*)\\)$|(\\w*),(-?\\d*|-1),(-?\\d*),(-?\\d*),(-?\\d*)$)")
    @JvmField val animation_alt: Pattern = Pattern.compile("animation(\\d+).pattern(\\d+),alternativestart,\\((\\S*)\\)$")
    @JvmField val animation_base: Pattern = Pattern.compile("animation(\\d+).pattern(\\d+),(\\w*),((-1)|(-1),(\\d*))$")
    
    @JvmField val option: Pattern = Pattern.compile("(\\d+)[oO]ption,(\\S*)$")

    @JvmField val surface_file_scan: Pattern = Pattern.compile("surface(\\d+).png")
    @JvmField val surface_desc_ptrn: Pattern = Pattern.compile("^surface(\\d+)")

    @JvmField val sqbracket_half_number: Pattern = Pattern.compile("^\\[(half|\\d+%?)\\]")
    @JvmField val surface_ptrn: Pattern = Pattern.compile("^\\[(-?\\d+)\\]|^(\\d{1})")

    @JvmField val ani_ptrn: Pattern = Pattern.compile("^\\[(\\d+)(|,\\w+)\\]")
    @JvmField val balloon_ptrn: Pattern = Pattern.compile("^\\[(-?\\d+)\\]|^(\\d{1})")

    @JvmField val shiori_res_header_ptrn: Pattern = Pattern.compile("^(\\w*)/(\\d*).(\\d*) (\\d{3})\\s*([[\\w][\\s]]*)$")

    @JvmField val url_ptrn: Pattern = Pattern.compile("(([\\w]+:)?//)?(([\\d\\w]|%[a-fA-f\\d]{2,2})+(:([\\d\\w]|%[a-fA-f\\d]{2,2})+)?@)?([\\d\\w][-\\d\\w]{0,253}[\\d\\w]\\.)+[\\w]{2,4}(:[\\d]+)?(/([-+_~.\\d\\w]|%[a-fA-f\\d]{2,2})*)*(\\?(&?([-+_~.\\d\\w]|%[a-fA-f\\d]{2,2})=?)*)?(#([-+_~.\\d\\w]|%[a-fA-f\\d]{2,2})*)?.*(.nar|.zip)$")

    @JvmField val comment_ptrn: Pattern = Pattern.compile("(//|;).*$")

    // Patterns for Sakura Script
    @JvmField val sqbracket_q_title: Pattern = Pattern.compile("^\\[([^,]*),([[^,]&&[^\\]]]*),?([^\\]]*?)\\]{1}")
    @JvmField val sqbracket_q_withOn: Pattern = Pattern.compile("^\\[([^,]*),(On[^,]*),*(.*)\\]")
    @JvmField val q_choice_ptrn: Pattern = Pattern.compile("\\\\q\\[([^,]*),([[^,]&&[^\\]]]*),?([^\\]]*?)\\]{1}")
    @JvmField val open_input: Pattern = Pattern.compile("^\\[open,inputbox,(.*)\\]")
}
