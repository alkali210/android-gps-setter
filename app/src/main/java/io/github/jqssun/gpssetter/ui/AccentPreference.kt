package io.github.jqssun.gpssetter.ui

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.utils.Accent
import io.github.jqssun.gpssetter.utils.PrefManager

// Settings row with the accent colours shown inline as tappable swatches.
class AccentPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    Preference(context, attrs) {

    init { widgetLayoutResource = R.layout.widget_accent_swatches }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val row = holder.findViewById(R.id.accent_swatches) as? LinearLayout ?: return
        row.removeAllViews()
        val d = context.resources.displayMetrics.density
        val size = (24 * d).toInt()
        val gap = (6 * d).toInt()
        Accent.entries.forEachIndexed { i, accent ->
            val sw = View(context)
            sw.layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = gap }
            sw.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent.color)
                if (i == PrefManager.accentIndex) setStroke((2.5f * d).toInt(), context.getColor(R.color.glass_ink))
            }
            sw.setOnClickListener {
                if (i != PrefManager.accentIndex) {
                    PrefManager.accentIndex = i
                    notifyChanged()
                    (context as? Activity)?.recreate()
                }
            }
            row.addView(sw)
        }
    }
}
