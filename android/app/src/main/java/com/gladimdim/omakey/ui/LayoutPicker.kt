package com.gladimdim.omakey.ui

import android.app.Activity
import android.app.AlertDialog
import com.gladimdim.omakey.store.LayoutStore

/** Pick the active layout; optionally import or remove one. */
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
}
