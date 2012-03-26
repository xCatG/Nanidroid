package com.cattailsw.nanidroid;

import java.util.regex.Pattern;

public class PatternHolders {

    // Patterns for parsing Surfaces.txt
    public static final Pattern element = Pattern.compile("element(\\d+),(\\w*),(\\S*),(\\d+),(\\d+)$");
    public static final Pattern collision = Pattern.compile("collision(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\w*)$");
    public static final	Pattern point = Pattern.compile("point.(\\w*\\.*)center([xXyY]{1}),(\\d+$)");

    public static final	Pattern interval = Pattern.compile("(\\d+)interval,(\\S*)$");
    public static final	Pattern animation_interval = Pattern.compile("animation(\\d+).interval.(\\S*)$");
    // XpatternX,number|-1,wait,overlay,x,y
    public static final	Pattern pattern = Pattern.compile("(\\d+)pattern(\\d?),(\\d*|-1),(\\d*),(\\w*),?(\\d*),(\\d*)$");
    public static final Pattern pattern_base = Pattern.compile("(\\d+)pattern(\\d?),(\\d*|-1),(\\d*),(\\w*)$");
    // animationX.patternX,overlay,number|-1,wait,x,y
    //public static final	Pattern animation = Pattern.compile("animation(\\d+).pattern(\\d?),(\\w*),(\\d*|-1),(\\d*),(\\d*),(\\d*)$");
    public static final	Pattern animation = Pattern.compile("^animation(\\d+).pattern(\\d?),(alternativestart,\\((\\S*)\\)$|(\\w*),(\\d*|-1),(\\d*),(\\d*),(\\d*)$)");
    public static final Pattern animation_alt = Pattern.compile("^animation(\\d+).pattern(\\d?),alternativestart,\\((\\S*)\\)$");
    public static final	Pattern animation_base = Pattern.compile("animation(\\d+).pattern(\\d?),(\\w*),((-1)|(-1),(\\d*))$");
    
    public static final	Pattern option = Pattern.compile("(\\d+)[oO]ption,(\\S*)$");

    public static final Pattern surface_file_scan = Pattern.compile("surface(\\d+).png");

    public static final Pattern sqbracket_half_number = Pattern.compile("\\[(half|\\d+%?)\\]");

}
