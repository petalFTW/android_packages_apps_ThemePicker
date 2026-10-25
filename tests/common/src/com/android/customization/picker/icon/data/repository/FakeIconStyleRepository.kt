/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.customization.picker.icon.data.repository

import android.stats.style.StyleEnums.APP_ICON_STYLE_THEMED
import android.stats.style.StyleEnums.APP_ICON_STYLE_UNSPECIFIED
import com.android.customization.picker.icon.shared.model.IconStyle
import com.android.customization.picker.icon.shared.model.IconStyleModel
import com.android.customization.picker.icon.shared.model.ThemePickerIconStyle
import com.android.wallpaper.config.BaseFlags
import com.android.wallpaper.testing.FakePreviewUtils
import com.android.wallpaper.util.BasePreviewUtils
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@Singleton
class FakeIconStyleRepository @Inject constructor() : IconStyleRepository {
    override val previewUtilsFlow: Flow<BasePreviewUtils?> = flowOf(FakePreviewUtils())

    private val _isCustomizationAvailable = MutableStateFlow(true)
    override val isCustomizationAvailable = _isCustomizationAvailable.asStateFlow()

    private val _isThemedIconActivated = MutableStateFlow(false)
    override val isThemedIconActivated = _isThemedIconActivated.asStateFlow()

    private val _selectedIconStyle = MutableStateFlow<IconStyle>(ThemePickerIconStyle.DEFAULT)
    override val selectedIconStyle = _selectedIconStyle.asStateFlow()
    override val subjectIconStyle = MutableStateFlow("frosted")
    override val subjectIconMonochrome = MutableStateFlow(false)
    val appliedIconStyles = mutableListOf<IconStyle>()

    override suspend fun setSubjectIconStyle(style: String) { subjectIconStyle.value = style }
    override suspend fun setSubjectIconMonochrome(enabled: Boolean) {
        subjectIconMonochrome.value = enabled
    }

    var shouldApplySuccessfully = true
    var shouldUpdateSuccessfully = true

    override val iconStyleModels: Flow<List<IconStyleModel>> =
        isCustomizationAvailable.map { isThemedIconAvailable ->
            ThemePickerIconStyle.entries
                .toList()
                // Filter entries if themed icon is not available
                .filter { isThemedIconAvailable || !it.getIsThemedIcon() }
                .map { it.toIconStyleModel() }
        }

    private fun IconStyle.toIconStyleModel(): IconStyleModel {
        return IconStyleModel(
            iconStyle = this,
            nameResId = this.nameResId,
            icon = null,
            isThemedIcon = this == ThemePickerIconStyle.MONOCHROME,
            isExternalLink = false,
        )
    }

    override suspend fun setIconStyle(iconStyle: IconStyle): Boolean {
        appliedIconStyles.add(iconStyle)
        if (shouldApplySuccessfully && shouldUpdateSuccessfully) {
            _selectedIconStyle.value = iconStyle
            _isThemedIconActivated.value = iconStyle == ThemePickerIconStyle.MONOCHROME
        }
        return shouldApplySuccessfully
    }

    override suspend fun getIconStyleForLogging(): Int {
        if (BaseFlags.get().isExtendibleThemeManager()) {
            val iconStyle = selectedIconStyle.value
            return iconStyle.loggingId ?: APP_ICON_STYLE_UNSPECIFIED
        } else {
            val isThemedIconActivated = isThemedIconActivated.value
            return if (isThemedIconActivated) APP_ICON_STYLE_THEMED else APP_ICON_STYLE_UNSPECIFIED
        }
    }

    override suspend fun setThemedIconEnabled(enabled: Boolean) {
        setIconStyle(if (enabled) ThemePickerIconStyle.MONOCHROME else ThemePickerIconStyle.DEFAULT)
    }

    fun setIsCustomizationAvailable(isAvailable: Boolean) {
        _isCustomizationAvailable.value = isAvailable
    }
}
