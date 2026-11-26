package dev.tilecam.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/** Fits itself inside the available space at a fixed aspect ratio, so the overlay lines up with the preview. */
class AspectFrameLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    private var ratio = 4f / 3f

    fun setAspectRatio(width: Int, height: Int) {
        ratio = width.toFloat() / height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = View.MeasureSpec.getSize(widthMeasureSpec)
        val availH = View.MeasureSpec.getSize(heightMeasureSpec)
        var w = availW
        var h = (w / ratio).toInt()
        if (h > availH) {
            h = availH
            w = (h * ratio).toInt()
        }
        super.onMeasure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
    }
}
