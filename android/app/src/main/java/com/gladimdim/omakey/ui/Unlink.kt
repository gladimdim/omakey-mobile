package com.gladimdim.omakey.ui

import android.app.Activity
import android.app.AlertDialog
import com.gladimdim.omakey.protocol.HostRecord
import com.gladimdim.omakey.store.BtHost
import com.gladimdim.omakey.store.BtHostStore
import com.gladimdim.omakey.store.HostStore

/** Unlink a computer paired with omakeyd, after asking; [done] once it's gone. */
internal fun Activity.confirmUnlink(h: HostRecord, hosts: HostStore, done: () -> Unit) {
    AlertDialog.Builder(this, Palette.dialogTheme)
        .setTitle("Unlink ${h.name}?")
        .setMessage("This phone will need a new pairing code to connect again. Also remove it on the computer with `omakeyd forget`.")
        .setPositiveButton("Unlink") { _, _ -> hosts.remove(h.hostId); done() }
        .setNegativeButton("Cancel", null)
        .show()
}

/** Unlink a computer the phone was a Bluetooth keyboard for, after asking. */
internal fun Activity.confirmUnlink(b: BtHost, btHosts: BtHostStore, done: () -> Unit) {
    AlertDialog.Builder(this, Palette.dialogTheme)
        .setTitle("Unlink ${b.name}?")
        .setMessage("It's removed from this list. To unpair completely, also remove it in the phone's Bluetooth settings.")
        .setPositiveButton("Unlink") { _, _ -> btHosts.remove(b.address); done() }
        .setNegativeButton("Cancel", null)
        .show()
}
