package com.cattailsw.nanidroid

import android.content.Context
import android.text.util.Linkify
import android.text.method.ScrollingMovementMethod
import android.util.AttributeSet
import android.widget.TextView

open class Balloon @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    private var vPaddingSize = 0
    private val s = ScrollingMovementMethod()

    open fun setText(str: String) {
        scrollTo(0, 0)
        super.setText(str)
        Linkify.addLinks(this, Linkify.ALL)
        vPaddingSize = compoundPaddingBottom + compoundPaddingTop
        val l = layout
        if (l != null) {
            val scrollDelta = l.getLineBottom(l.lineCount - 1) - scrollY - (height - vPaddingSize)
            if (scrollDelta > 0) {
                movementMethod = s
                scrollBy(0, scrollDelta)
            } else {
                movementMethod = null
            }
        }
    }
}
