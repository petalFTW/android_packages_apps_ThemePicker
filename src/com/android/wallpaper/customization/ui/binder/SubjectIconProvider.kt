/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.wallpaper.customization.ui.binder

import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.android.themepicker.R
import com.android.wallpaper.model.Screen
import com.android.wallpaper.util.PreviewUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sends the one-shot clear command to the Launcher subject-icon provider. */
object SubjectIconProvider {
    suspend fun clear(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val utils =
                PreviewUtils(
                    context,
                    context.getString(R.string.themed_icon_metadata_key),
                    Screen.HOME_SCREEN,
                )
            if (utils.supportsPreview()) {
                context.contentResolver.update(
                    utils.getUri("subject_icons"),
                    ContentValues().apply { put("action", "clear") },
                    null,
                    null,
                ) == 1
            } else false
        } catch (error: RuntimeException) {
            Log.w("CutoutIcons", "Could not request cache clear", error)
            false
        }
    }
}
