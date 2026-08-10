package com.cattailsw.nanidroid.compose

import org.junit.Assert.assertSame
import org.junit.Test

class NanidroidThemeTest {
    @Test fun reusesTheLightSchemeInstance() {
        assertSame(nanidroidColorScheme(false), nanidroidColorScheme(false))
    }

    @Test fun reusesTheDarkSchemeInstance() {
        assertSame(nanidroidColorScheme(true), nanidroidColorScheme(true))
    }
}
