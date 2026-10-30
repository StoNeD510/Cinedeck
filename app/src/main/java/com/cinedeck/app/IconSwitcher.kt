package com.cinedeck.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Swaps the launcher icon and TV banner by enabling one launcher alias per theme. */
object IconSwitcher {
    val ids = listOf("neon", "gold", "coral", "ocean", "emerald", "violet")

    private fun alias(id: String) = "com.cinedeck.app.Launcher" + id.replaceFirstChar { it.uppercase() }

    fun apply(ctx: Context, id: String) {
        val pm = ctx.packageManager
        val want = if (id in ids) id else "neon"
        // Enable the new one first so there's never a moment with no launcher entry.
        set(pm, ctx, want, true)
        ids.filter { it != want }.forEach { set(pm, ctx, it, false) }
    }

    private fun set(pm: PackageManager, ctx: Context, id: String, on: Boolean) {
        val cn = ComponentName(ctx, alias(id))
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (pm.getComponentEnabledSetting(cn) != state) runCatching { pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP) }
    }
}
