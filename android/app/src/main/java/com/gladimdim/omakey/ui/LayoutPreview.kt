package com.gladimdim.omakey.ui

import android.app.Activity
import android.app.AlertDialog
import android.widget.FrameLayout
import com.gladimdim.omakey.keyboard.KeyboardModel
import com.gladimdim.omakey.keyboard.KeyboardView
import com.gladimdim.omakey.layout.Layout

/** Shows a layout before it's imported, and what it would replace. */
object LayoutPreview {
    fun show(activity: Activity, layout: Layout, replaces: Layout?, onImport: () -> Unit) {
        val view = KeyboardView(activity).apply {
            interactive = false
            setLayout(layout, object : KeyboardModel.Sink {
                override fun keyDown(code: Int) {}
                override fun keyUp(code: Int) {}
            })
        }
        val frame = FrameLayout(activity).apply {
            val pad = activity.dp(12f)
            setPadding(pad, pad, pad, 0)
            // Keyboards are wide: give it a landscape-ish box.
            addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, activity.dp(180f)))
        }
        val details = buildString {
            append("${layout.keys.size} keys")
            layout.author?.let { append(" · by $it") }
            layout.description?.let { append("\n$it") }
            if (replaces != null) append("\n\nReplaces your imported layout \"${replaces.name}\" (same id \"${layout.id}\").")
        }
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(layout.name)
            .setMessage(details)
            .setView(frame)
            .setPositiveButton(if (replaces != null) "Replace" else "Import") { _, _ -> onImport() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
