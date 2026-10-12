package x.vladgba.fileman

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * "Rename by pattern": find / replace fields, wildcard-or-regex and match-case switches, and a live preview
 * of every selected name (old → new, problems in red). OK is enabled only while something would be renamed;
 * files with a problem are skipped. The last rule is remembered.
 */
object BatchRenameDialog {
    private const val MAX_PREVIEW = 300
    private const val RED = 0xFFE53935.toInt()

    fun show(a: Activity, files: List<File>, onApply: (RenameOps.Plan) -> Unit) {
        val prefs = a.getSharedPreferences("unifx", Context.MODE_PRIVATE)
        fun dp(v: Int) = Ui.dp(a, v)

        val find = field(a, R.string.rename_find, prefs.getString("rn_find", "*") ?: "*")
        val replace = field(a, R.string.rename_replace, prefs.getString("rn_replace", "*") ?: "*")
        val regex = CheckBox(a).apply { setText(R.string.rename_regex); isChecked = prefs.getBoolean("rn_regex", false) }
        val matchCase = CheckBox(a).apply { setText(R.string.match_case); isChecked = prefs.getBoolean("rn_case", false) }
        val help = TextView(a).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.7f
        }
        val summary = TextView(a).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, dp(4))
        }
        val preview = TextView(a).apply {
            typeface = Fonts.mono()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextIsSelectable(false)
        }
        val previewScroll = ScrollView(a).apply { addView(preview) }
        val content = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(find, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(replace, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            // Stacked, not side by side: "Regular expression" doesn't fit half a phone-width dialog.
            addView(regex, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(matchCase, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(help)
            addView(summary)
            addView(previewScroll, LinearLayout.LayoutParams(MATCH_PARENT, dp(180)))
        }

        val dialog = AlertDialog.Builder(a)
            .setTitle(a.resources.getQuantityString(R.plurals.rename_title, files.size, files.size))
            .setView(ScrollView(a).apply { addView(content) })
            .setPositiveButton(R.string.rename, null) // set below, so a click can be refused
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        var plan: RenameOps.Plan? = null

        fun rule() = RenameOps.Rule(find.text.toString(), replace.text.toString(), regex.isChecked, matchCase.isChecked)

        fun refresh() {
            help.setText(if (regex.isChecked) R.string.rename_help_regex else R.string.rename_help_wildcard)
            val p = RenameOps.plan(files, rule()) { FileOps.existsNoFollow(it) }
            plan = p
            val sb = SpannableStringBuilder()
            if (p.error != null) {
                summary.text = a.getString(R.string.rename_bad_pattern, p.error)
                summary.setTextColor(RED)
            } else {
                val n = p.renames.size
                summary.text = buildString {
                    append(a.resources.getQuantityString(R.plurals.rename_count, n, n))
                    if (p.problems > 0) append(" · ").append(a.resources.getQuantityString(R.plurals.rename_problems, p.problems, p.problems))
                }
                summary.setTextColor(if (p.problems > 0) RED else preview.currentTextColor)
            }
            for (item in p.items.take(MAX_PREVIEW)) {
                val start = sb.length
                sb.append(item.file.name)
                when {
                    item.newName == null -> {
                        sb.setSpan(ForegroundColorSpan(0x80808080.toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    item.problem != null -> {
                        sb.append("  →  ").append(item.newName).append("  (").append(a.getString(reason(item.problem))).append(')')
                        sb.setSpan(ForegroundColorSpan(RED), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    else -> sb.append("  →  ").append(item.newName)
                }
                sb.append('\n')
            }
            if (p.items.size > MAX_PREVIEW) sb.append("…\n")
            preview.text = sb
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = p.error == null && p.renames.isNotEmpty()
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable) = refresh()
        }
        find.addTextChangedListener(watcher)
        replace.addTextChangedListener(watcher)
        regex.setOnCheckedChangeListener { _, _ -> refresh() }
        matchCase.setOnCheckedChangeListener { _, _ -> refresh() }

        dialog.setOnShowListener {
            refresh()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p = plan ?: return@setOnClickListener
                if (p.error != null || p.renames.isEmpty()) return@setOnClickListener
                prefs.edit()
                    .putString("rn_find", find.text.toString()).putString("rn_replace", replace.text.toString())
                    .putBoolean("rn_regex", regex.isChecked).putBoolean("rn_case", matchCase.isChecked)
                    .apply()
                dialog.dismiss()
                onApply(p)
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
    }

    private fun reason(p: RenameOps.Problem) = when (p) {
        RenameOps.Problem.INVALID_NAME -> R.string.rename_invalid
        RenameOps.Problem.DUPLICATE -> R.string.rename_duplicate
        RenameOps.Problem.EXISTS -> R.string.exists
    }

    private fun field(a: Activity, hint: Int, value: String) = EditText(a).apply {
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setHint(hint)
        setText(value)
    }
}
