package com.cattailsw.nanidroid.di

import android.os.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

fun interface MonotonicClock {
    fun nowMillis(): Long
}

@Module
@InstallIn(SingletonComponent::class)
object PlatformClockModule {
    @Provides
    fun monotonicClock(): MonotonicClock =
        MonotonicClock { SystemClock.elapsedRealtime() }
}
