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

package com.android.customization.picker.icon.domain.interactor

import com.android.customization.model.grid.ShapeOptionModel
import com.android.customization.picker.grid.data.repository.ShapeRepository
import com.android.customization.picker.icon.data.repository.IconStyleRepository
import com.android.customization.picker.icon.shared.model.IconStyle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class AppIconInteractor
@Inject
constructor(
    private val shapeRepository: ShapeRepository,
    private val iconStyleRepository: IconStyleRepository,
) {

    val shapeOptions = shapeRepository.shapeOptions

    private val defaultShape =
        ShapeOptionModel("default", "", shapeRepository.defaultShapePath, true)

    val selectedShapeOption =
        shapeRepository.selectedShapeOption.map { shapeOption -> shapeOption ?: defaultShape }

    val isShapeOptionsAvailable: Flow<Boolean> = shapeRepository.isShapeOptionsAvailable

    val isThemedIconAvailable: Flow<Boolean> = iconStyleRepository.isCustomizationAvailable

    val isThemedIconEnabled: Flow<Boolean> = iconStyleRepository.isThemedIconActivated

    val iconStyleModels = iconStyleRepository.iconStyleModels

    val selectedIconStyle = iconStyleRepository.selectedIconStyle

    val subjectIconStyle = iconStyleRepository.subjectIconStyle

    val subjectIconMonochrome = iconStyleRepository.subjectIconMonochrome

    suspend fun applyThemedIconEnabled(enabled: Boolean) =
        iconStyleRepository.setThemedIconEnabled(enabled)

    suspend fun applyShape(shapeKey: String) = shapeRepository.applyShape(shapeKey)

    suspend fun applyIconStyle(iconStyle: IconStyle): Boolean =
        iconStyleRepository.setIconStyle(iconStyle)

    suspend fun applySubjectIconStyle(style: String) =
        iconStyleRepository.setSubjectIconStyle(style)

    suspend fun applySubjectIconMonochrome(enabled: Boolean) =
        iconStyleRepository.setSubjectIconMonochrome(enabled)
}
