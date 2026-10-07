package com.gladimdim.omakey.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import com.gladimdim.omakey.layout.Layout
import com.gladimdim.omakey.layout.LayoutLink
import com.gladimdim.omakey.store.LayoutStore

/** Pick the active layout; optionally import, share or remove one. */
object LayoutPicker {
    fun show(activity: Activity, store: LayoutStore, onImport: (() -> Unit)?, onPicked: () -> Unit) {
        val entries = store.all()
        val selected = store.selectedId
        val names = entries.map { e ->
            (if (e.layout.id == selected) "● " else "   ") + e.layout.name + if (e.builtIn) "" else "  (imported)"
        }.toTypedArray()

        val b = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Keyboard layout")
            .setItems(names) { _, i ->
                store.selectedId = entries[i].layout.id
                onPicked()
            }
        if (onImport != null) b.setNeutralButton("Import file…") { _, _ -> onImport() }
        b.setPositiveButton("Share…") { _, _ ->
            AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Share a layout")
                .setItems(entries.map { it.layout.name }.toTypedArray()) { _, i -> share(activity, entries[i].layout) }
                .setNegativeButton("Cancel", null)
                .show()
        }
        val imported = entries.filter { !it.builtIn }
        if (imported.isNotEmpty()) {
            b.setNegativeButton("Remove…") { _, _ ->
                AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Remove an imported layout")
                    .setItems(imported.map { it.layout.name }.toTypedArray()) { _, i ->
                        store.delete(imported[i].layout.id)
                        onPicked()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        b.show()
    }

    /**
     * Out through the share sheet: an `omakey://layout` link when it is
     * short enough to paste anywhere, otherwise the JSON itself. Either
     * imports on another phone or in the studio.
     */
    fun share(activity: Activity, layout: Layout) {
        val link = LayoutLink.encode(layout.source)
        val text = if (link.length <= MAX_LINK) link else layout.source
        val send = Intent(Intent.ACTION_SEND)
            .setType(if (text === link) "text/plain" else "application/json")
            .putExtra(Intent.EXTRA_SUBJECT, "${layout.name} — Omakey layout")
            .putExtra(Intent.EXTRA_TEXT, text)
        activity.startActivity(Intent.createChooser(send, "Share ${layout.name}"))
    }

    private const val MAX_LINK = 8000
}
