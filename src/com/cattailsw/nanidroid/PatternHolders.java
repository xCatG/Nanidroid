package com.cattailsw.nanidroid;

import java.util.regex.Pattern;

public class PatternHolders {

    // Patterns for parsing Surfaces.txt
    public static final Pattern element = Pattern.compile("element(\\d+),(\\w*),(\\S*),(\\d+),(\\d+)$");
    public static final Pattern collision = Pattern.compile("collision(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\w*)$");
    public static final	Pattern point = Pattern.compile("point.center([xXyY]{1}),(\\d+$)");

    public static final	Pattern interval = Pattern.compile("(\\d+)interval,(\\S*)$");
    public static final	Pattern pattern = Pattern.compile("(\\d+)pattern(\\d?),(\\d*|-1),(\\d*),(\\w*),(\\d*),(\\d*)$");
    public static final	Pattern animation = Pattern.compile("animation(\\d+),pattern(\\d?),(\\w*),(\\d*),(\\d*),(\\d*),(\\d*)");
    public static final	Pattern option = Pattern.compile("(\\d+)[oO]ption,(\\S*)$");

}
