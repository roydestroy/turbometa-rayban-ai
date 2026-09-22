package com.smartview.glassai.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** Compose may provide a themed wrapper around the foreground activity. */
fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val base = current.baseContext
        if (base === current) return null
        current = base
    }
    return current as? Activity
}
