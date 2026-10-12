package x.vladgba.fileman

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout

/** Small shared UI helpers so every screen stays framework-only and code-built. */
object Ui {
    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun attrRes(ctx: Context, attr: Int): Int =
        TypedValue().also { ctx.theme.resolveAttribute(attr, it, true) }.resourceId

    fun isNight(ctx: Context) =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /**
     * Edge-to-edge (targetSdk 35): keep content clear of the navigation bar and the keyboard. Screens with the
     * system action bar pass [underActionBar]: on API 35+ their content starts at the very top of the window,
     * behind the status bar and the action bar, and the top inset it receives covers both.
     */
    fun applyInsets(root: View, underActionBar: Boolean = false) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                val top = if (underActionBar && Build.VERSION.SDK_INT >= 35) i.top else 0
                v.setPadding(i.left, top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, 0, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    /** Status-bar height when drawing edge-to-edge (0 when the system already keeps content below it). */
    fun topInset(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).top
        else @Suppress("DEPRECATION") insets.systemWindowInsetTop

    fun button(ctx: Context, label: CharSequence, onClick: () -> Unit): Button =
        Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            isAllCaps = false
            minWidth = 0
            minimumWidth = dp(ctx, 44)
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }

    /** One-field input dialog. [neutral] is an optional second action (e.g. "Hex" vs "Text"). */
    fun input(
        a: Activity,
        title: CharSequence,
        initial: String,
        inputType: Int,
        positive: Int,
        onPositive: (String) -> Unit,
        neutral: Int = 0,
        onNeutral: ((String) -> Unit)? = null,
    ) {
        val field = EditText(a).apply {
            setSingleLine()
            this.inputType = inputType
            setText(initial)
            selectAll()
        }
        val box = FrameLayout(a).apply {
            setPadding(dp(a, 20), dp(a, 8), dp(a, 20), 0)
            addView(field)
        }
        val b = AlertDialog.Builder(a)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(positive) { _, _ -> onPositive(field.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
        if (onNeutral != null) b.setNeutralButton(neutral) { _, _ -> onNeutral(field.text.toString()) }
        b.create().apply { window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE) }.show()
    }

    /** "Save changes?" with Save / Discard / Cancel. */
    fun confirmUnsaved(a: Activity, name: String, onSave: () -> Unit, onDiscard: () -> Unit) {
        AlertDialog.Builder(a)
            .setTitle(R.string.unsaved_title)
            .setMessage(a.getString(R.string.unsaved_msg, name))
            .setPositiveButton(R.string.save) { _, _ -> onSave() }
            .setNeutralButton(R.string.discard) { _, _ -> onDiscard() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
