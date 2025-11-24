package cl.shoppersa.ui

import android.app.Activity
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import cl.shoppersa.R
import com.google.android.material.snackbar.Snackbar

/** Snackbar preferido (sin deprecaciones). Fallback a Toast si no hay root view. */
fun Context.brandToast(msg: String, long: Boolean = false) {
    val root: View? = (this as? Activity)?.findViewById(android.R.id.content)

    if (root != null) {
        Snackbar.make(root, msg, if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT).apply {
            setBackgroundTint(ContextCompat.getColor(this@brandToast, R.color.brand_primary))
            setTextColor(ContextCompat.getColor(this@brandToast, R.color.brand_on_primary))
            // si existe, anclar al bottom nav para que no lo tape
            (root.findViewById<View?>(R.id.bottomNav))?.let { setAnchorView(it) }
        }.show()
        return
    }

    // ---------- Fallback: Toast custom (solo si no hay root view) ----------
    @Suppress("DEPRECATION")
    run {
        val cont = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(ContextCompat.getColor(this@brandToast, R.color.brand_primary))
            }
        }

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_nav_products)
            setColorFilter(
                ContextCompat.getColor(this@brandToast, R.color.brand_on_primary),
                PorterDuff.Mode.SRC_IN
            )
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val tv = TextView(this).apply {
            text = msg
            setTextColor(ContextCompat.getColor(this@brandToast, R.color.brand_on_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

        cont.addView(icon)
        cont.addView(tv)

        Toast(this).apply {
            duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(72)) // despega del bottom bar
            view = cont
        }.show()
    }
}

private fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()
