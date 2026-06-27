package com.vcam.model

import android.graphics.drawable.Drawable

data class AppInfo(
    val appName: String,
    val packageName: String,
    val icon: Drawable?,
    val useCamera: Boolean = false,
    val isSystem: Boolean = false
)
