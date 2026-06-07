package com.cattailsw.nanidroid.test

import com.cattailsw.nanidroid.TapClassifier
import org.junit.Assert.assertEquals
import org.junit.Test

class TapClassifierTest {

    @Test
    fun classifyTap_returnsDouble_whenWithinTimeout() {
        val classifier = TapClassifier(doubleTapTimeoutMs = 300L)
        val result = classifier.classifyTap(now = 1300L, lastTapTime = 1100L)
        assertEquals(TapClassifier.TapType.DOUBLE, result)
    }

    @Test
    fun classifyTap_returnsSingle_whenExceedsTimeout() {
        val classifier = TapClassifier(doubleTapTimeoutMs = 300L)
        val result = classifier.classifyTap(now = 1500L, lastTapTime = 1100L)
        assertEquals(TapClassifier.TapType.SINGLE, result)
    }
    @Test
    fun classifyTap_returnsDouble_whenExactlyOnTimeout() {
        val classifier = TapClassifier(doubleTapTimeoutMs = 300L)
        val result = classifier.classifyTap(now = 1400L, lastTapTime = 1100L)
        assertEquals(TapClassifier.TapType.DOUBLE, result)
    }

    @Test
    fun classifyTap_returnsSingle_whenExactlyOneMsOverTimeout() {
        val classifier = TapClassifier(doubleTapTimeoutMs = 300L)
        val result = classifier.classifyTap(now = 1401L, lastTapTime = 1100L)
        assertEquals(TapClassifier.TapType.SINGLE, result)
    }
}
