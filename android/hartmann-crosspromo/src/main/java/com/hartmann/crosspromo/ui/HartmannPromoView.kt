package com.hartmann.crosspromo.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.hartmann.crosspromo.R

/**
 * Android View implementation enabling direct integration in traditional XML layouts:
 *
 * <com.hartmann.crosspromo.ui.HartmannPromoView
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content"
 *     app:promoPlacement="settings"
 *     app:promoLimit="3" />
 */
class HartmannPromoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var placement: String = "home"
    private var limit: Int = 3
    private var title: String = "More from Hartmann Studios"

    private val composeView: ComposeView

    init {
        if (attrs != null) {
            val typedArray = context.obtainStyledAttributes(attrs, R.styleable.HartmannPromoView)
            try {
                typedArray.getString(R.styleable.HartmannPromoView_promoPlacement)?.let {
                    placement = it
                }
                limit = typedArray.getInt(R.styleable.HartmannPromoView_promoLimit, 3)
                typedArray.getString(R.styleable.HartmannPromoView_promoTitle)?.let {
                    title = it
                }
            } finally {
                typedArray.recycle()
            }
        }

        composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                HartmannPromoCarousel(
                    placement = placement,
                    limit = limit,
                    title = title
                )
            }
        }

        addView(composeView)
    }

    /**
     * Updates placement programmatically.
     */
    fun setPlacement(newPlacement: String, newLimit: Int = 3) {
        this.placement = newPlacement
        this.limit = newLimit
        composeView.setContent {
            HartmannPromoCarousel(
                placement = placement,
                limit = limit,
                title = title
            )
        }
    }
}
