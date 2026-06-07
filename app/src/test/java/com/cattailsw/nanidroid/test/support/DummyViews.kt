package com.cattailsw.nanidroid.test.support

import android.content.Context
import android.util.Log
import com.cattailsw.nanidroid.SakuraView
import com.cattailsw.nanidroid.KeroView
import com.cattailsw.nanidroid.Balloon

open class DummySakuraView(ctx: Context) : SakuraView(ctx) {
    var sid: String? = null
    var stext: String? = null
    var talkCalledTime = 0
    var aid: String? = null
    var aidz: String? = null

    override fun changeSurface(id: String) {
        Log.d("DummySakuraView", " sid => $id")
        if (this.sid == null) {
            this.sid = id
            this.stext = id
        } else if (!sid.equals(id, ignoreCase = true)) {
            this.sid = id
            this.stext = "${this.stext},$id"
        }
    }

    override fun startTalkingAnimation() {
        talkCalledTime++
        Log.d("DummySakuraView", "startTalkingAnimation called $talkCalledTime times")
    }

    override fun loadAnimation(id: String) {
        aid = id
        if (aidz == null) {
            aidz = id
        } else {
            aidz += ",$id"
        }
    }

    override fun startAnimation() {}
}

open class DummyKeroView(ctx: Context) : KeroView(ctx) {
    var sid: String? = null
    var aid: String? = null
    var aidz: String? = null

    override fun changeSurface(id: String) {
        if (this.sid == null) {
            this.sid = id
        } else {
            this.sid = "${this.sid},$id"
        }
    }

    override fun loadAnimation(id: String) {
        aid = id
        if (aidz == null) {
            aidz = id
        } else {
            aidz += ",$id"
        }
    }

    override fun startTalkingAnimation() {}
    override fun startAnimation() {}
}

open class DummyBalloon(ctx: Context) : Balloon(ctx) {
    var dispText: String? = null
    var textVal: String? = null

    override fun setText(str: String) {
        Log.d("DummyBalloon", "got text:$str")
        if (this.textVal == null) {
            this.textVal = str
            dispText = str
        } else {
            dispText = str
            this.textVal = this.textVal + str
        }
    }
}
