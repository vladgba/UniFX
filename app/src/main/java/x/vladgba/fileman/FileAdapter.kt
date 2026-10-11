package x.vladgba.fileman

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileAdapter(private val ctx: Context) : BaseAdapter() {
    private var all: List<Entry> = emptyList()
    private var shown: List<Entry> = emptyList()
    private var query = ""
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    val total get() = all.size

    /** Grid (icon tiles) vs list (rows). Both layouts are recycled separately via view types. */
    var grid = false
        set(v) { field = v; notifyDataSetChanged() }

    fun setData(d: List<Entry>) { all = d; refilter() }
    fun filter(q: String) { query = q.trim(); refilter() }

    private fun refilter() {
        shown = if (query.isEmpty()) all else all.filter { it.name.contains(query, ignoreCase = true) }
        notifyDataSetChanged()
    }

    override fun getCount() = shown.size
    override fun getItem(position: Int) = shown[position]
    override fun getItemId(position: Int) = position.toLong()

    /** Whether a position is selected. Recycled views keep their old highlight otherwise. */
    var isChecked: (Int) -> Boolean = { false }

    override fun getViewTypeCount() = 2
    override fun getItemViewType(position: Int) = if (grid) 1 else 0

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val e = shown[position]
        if (grid) {
            val cell = convertView as? Cell ?: Cell(ctx)
            cell.badge.set(e)
            cell.name.text = e.name
            cell.meta.text = if (e.isDir) "" else Formatter.formatShortFileSize(ctx, e.size)
            cell.meta.visibility = if (e.isDir) View.GONE else View.VISIBLE
            cell.isActivated = isChecked(position)
            return cell
        }
        val row = convertView as? Row ?: Row(ctx)
        row.badge.set(e)
        row.name.text = e.name
        val date = dateFmt.format(Date(e.modified))
        row.meta.text = if (e.isDir) date else Formatter.formatShortFileSize(ctx, e.size) + "  ·  " + date
        row.isActivated = isChecked(position)
        return row
    }

    private class Cell(ctx: Context) : LinearLayout(ctx) {
        val badge = Badge(ctx)
        val name = TextView(ctx)
        val meta = TextView(ctx)

        init {
            val d = resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumHeight = dp(112)
            setPadding(dp(6), dp(10), dp(6), dp(8))
            val tv = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.activatedBackgroundIndicator, tv, true)
            setBackgroundResource(tv.resourceId)

            val a = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary, android.R.attr.textColorSecondary))
            name.setTextColor(a.getColorStateList(0))
            meta.setTextColor(a.getColorStateList(1))
            a.recycle()

            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            name.gravity = Gravity.CENTER_HORIZONTAL
            name.maxLines = 2
            name.ellipsize = TextUtils.TruncateAt.END
            meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            meta.gravity = Gravity.CENTER_HORIZONTAL
            meta.setSingleLine()

            addView(badge, LayoutParams(dp(52), dp(52)).apply { bottomMargin = dp(6) })
            addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(meta, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private class Row(ctx: Context) : LinearLayout(ctx) {
        val badge = Badge(ctx)
        val name = TextView(ctx)
        val meta = TextView(ctx)

        init {
            val d = resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            val tv = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.activatedBackgroundIndicator, tv, true)
            setBackgroundResource(tv.resourceId)

            val a = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary, android.R.attr.textColorSecondary))
            name.setTextColor(a.getColorStateList(0))
            meta.setTextColor(a.getColorStateList(1))
            a.recycle()

            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            name.setSingleLine()
            name.ellipsize = TextUtils.TruncateAt.MIDDLE
            meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            meta.setSingleLine()

            addView(badge, LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(16) })
            val col = LinearLayout(ctx).apply {
                orientation = VERTICAL
                addView(name)
                addView(meta)
            }
            addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** Folder silhouette or a colored page with the extension on it. Drawn, not loaded: zero image assets. */
    private class Badge(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val r = RectF()
        private var dir = false
        private var label = ""

        fun set(e: Entry) {
            dir = e.isDir
            label = e.ext.take(4).uppercase()
            fill.color = Kinds.color(e)
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val rad = w * 0.1f
            if (dir) {
                r.set(w * 0.04f, h * 0.14f, w * 0.48f, h * 0.36f); c.drawRoundRect(r, rad, rad, fill)
                r.set(w * 0.04f, h * 0.24f, w * 0.96f, h * 0.86f); c.drawRoundRect(r, rad, rad, fill)
            } else {
                r.set(w * 0.12f, h * 0.04f, w * 0.88f, h * 0.96f); c.drawRoundRect(r, rad, rad, fill)
                if (label.isNotEmpty()) {
                    text.textSize = w * if (label.length > 3) 0.2f else 0.25f
                    c.drawText(label, w / 2, h / 2 - (text.descent() + text.ascent()) / 2, text)
                }
            }
        }
    }
}
