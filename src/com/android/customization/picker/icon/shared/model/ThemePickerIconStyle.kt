/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.customization.picker.icon.shared.model

import android.stats.style.StyleEnums.APP_ICON_STYLE_THEMED
import android.stats.style.StyleEnums.APP_ICON_STYLE_UNSPECIFIED
import com.android.customization.module.logging.ThemesUserEventLogger.AppIconStyle
import com.android.themepicker.R

enum class ThemePickerIconStyle(
    override val nameResId: Int,
    @AppIconStyle override val loggingId: Int = APP_ICON_STYLE_UNSPECIFIED,
) : IconStyle {
    DEFAULT(R.string.app_icons_style_default, APP_ICON_STYLE_UNSPECIFIED),
    MONOCHROME(R.string.app_icons_style_minimal, APP_ICON_STYLE_THEMED),
    SUBJECT(R.string.app_icons_style_subject, APP_ICON_STYLE_UNSPECIFIED);

    override fun getIsThemedIcon(): Boolean {
        return this == MONOCHROME
    }
}
