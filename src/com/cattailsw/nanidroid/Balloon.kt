package com.cattailsw.nanidroid

import android.content.Context
import android.text.Layout
import android.text.method.ScrollingMovementMethod
import android.text.util.Linkify
import android.util.AttributeSet
import android.widget.TextView

/**
 * Text balloon used by the retained compatibility renderer.
 *
 * This preserves the legacy String overload: URL detection is refreshed after
 * every script update and overflowing content is positioned at its final line.
 */
open class Balloon @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : TextView(context, attrs, defStyle) {
    private var verticalPaddingSize = 0
    private val scrollingMovementMethod = ScrollingMovementMethod()

    open fun setText(text: String) {
        scrollTo(0, 0)
        super.setText(text)
        Linkify.addLinks(this, Linkify.ALL)
        verticalPaddingSize = compoundPaddingBottom + compoundPaddingTop

        val currentLayout: Layout = layout ?: return
        val scrollDelta = currentLayout.getLineBottom(currentLayout.lineCount - 1) - scrollY -
            (height - verticalPaddingSize)
        if (scrollDelta > 0) {
            movementMethod = scrollingMovementMethod
            scrollBy(0, scrollDelta)
        } else {
            movementMethod = null
        }
    }
}
