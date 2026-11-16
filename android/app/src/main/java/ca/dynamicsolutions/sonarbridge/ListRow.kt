package ca.dynamicsolutions.sonarbridge

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.google.android.material.R as MR

/** The app's one list-row shape and the section heading above a group of rows. */
object ListRow {
    /** A full-width, tappable row with one line of text and the platform ripple. */
    fun create(context: Context, title: String, onClick: () -> Unit): View = TextView(context).apply {
        val density = context.resources.displayMetrics.density
        text = title
        setTextAppearance(MR.style.TextAppearance_Material3_BodyLarge)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = (56 * density).toInt()
        setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        isClickable = true
        isFocusable = true
        val ripple = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        setBackgroundResource(ripple.resourceId)
        setOnClickListener { onClick() }
    }

    /** A section heading, matching the Settings headings. */
    fun heading(context: Context, text: String, topPadDp: Int = 16): TextView = TextView(context).apply {
        val density = context.resources.displayMetrics.density
        this.text = text
        setTextAppearance(MR.style.TextAppearance_Material3_TitleMedium)
        setPadding(0, (topPadDp * density).toInt(), 0, (8 * density).toInt())
    }
}
