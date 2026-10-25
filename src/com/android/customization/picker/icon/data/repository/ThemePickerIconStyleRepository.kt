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

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.graphics.drawable.AdaptiveIconDrawable
import android.net.Uri
import android.os.SystemClock
import android.stats.style.StyleEnums.APP_ICON_STYLE_THEMED
import android.stats.style.StyleEnums.APP_ICON_STYLE_UNSPECIFIED
import com.android.customization.module.CustomizationPreferences
import com.android.customization.module.logging.ThemesUserEventLoggerImpl.Companion.TIMEOUT
import com.android.customization.picker.icon.shared.model.IconStyle
import com.android.customization.picker.icon.shared.model.IconStyleModel
import com.android.customization.picker.icon.shared.model.ThemePickerIconStyle
import com.android.themepicker.R
import com.android.wallpaper.config.BaseFlags
import com.android.wallpaper.customization.ui.binder.ShapeIconViewBinder
import com.android.wallpaper.customization.ui.view.ShapeTileDrawable
import com.android.wallpaper.model.Screen
import com.android.wallpaper.module.InjectorProvider
import com.android.wallpaper.picker.common.icon.ui.viewmodel.Icon
import com.android.wallpaper.picker.di.modules.BackgroundDispatcher
import com.android.wallpaper.util.PreviewUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ThemePickerIconStyleRepository
@Inject
constructor(
    @ApplicationContext private val appContext: Context,
    private val contentResolver: ContentResolver,
    @BackgroundDispatcher private val backgroundScope: CoroutineScope,
) : IconStyleRepository {
    private val metadataKey = appContext.getString(R.string.themed_icon_metadata_key)
    // TODO (b/424856247): test the retry logic for getting PreviewUtils
    private var previewUtils: PreviewUtils? = null
    override val previewUtilsFlow = flow {
        // If PreviewUtils is created too early on start up, the provider (e.g. Launcher) may not be
        // ready, so PreviewUtils#supportsPreview would return false. Only cache previewUtils if it
        // supports previewing. Otherwise, retry when new flow consumers appear.
        if (previewUtils == null) {
            PreviewUtils(appContext, metadataKey, Screen.HOME_SCREEN).let {
                if (it.supportsPreview()) {
                    previewUtils = it
                }
            }
        }
        emit(previewUtils)
    }

    override val isCustomizationAvailable: Flow<Boolean> = previewUtilsFlow.map { it != null }

    override val isThemedIconActivated: Flow<Boolean> =
        previewUtilsFlow
            .flatMapLatest {
                callbackFlow {
                    var disposableHandle: DisposableHandle? = null
                    if (it != null) {
                        val contentObserver =
                            object : ContentObserver(null) {
                                override fun onChange(selfChange: Boolean) {
                                    trySend(getThemedIconEnabled(it.getUri(ICON_THEMED)))
                                }
                            }
                        // Icons can be set with ICON_THEMED or SET_ICON_THEMED URI
                        contentResolver.registerContentObserver(
                            it.getUri(ICON_THEMED),
                            /* notifyForDescendants= */ true,
                            contentObserver,
                        )
                        contentResolver.registerContentObserver(
                            it.getUri(SET_ICON_THEMED),
                            /* notifyForDescendants= */ true,
                            contentObserver,
                        )

                        trySend(getThemedIconEnabled(it.getUri(ICON_THEMED)))

                        disposableHandle = DisposableHandle {
                            contentResolver.unregisterContentObserver(contentObserver)
                        }
                    }
                    awaitClose { disposableHandle?.dispose() }
                }
            }
            .stateIn(
                scope = backgroundScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    private data class SubjectState(
        val enabled: Boolean,
        val state: String,
        val cached: Boolean,
        val style: String,
        val monochrome: Boolean,
    )

    private val subjectState: Flow<SubjectState> =
        previewUtilsFlow
            .flatMapLatest { utils ->
                callbackFlow {
                    val initial = SubjectState(false, "", false, DEFAULT_SUBJECT_STYLE, false)
                    if (utils == null) {
                        trySend(initial)
                        awaitClose { }
                    } else {
                        val observer =
                            object : ContentObserver(null) {
                                override fun onChange(selfChange: Boolean) {
                                    trySend(querySubject(utils))
                                }
                            }
                        contentResolver.registerContentObserver(
                            utils.getUri(SUBJECT_ICONS),
                            /* notifyForDescendants= */ true,
                            observer,
                        )
                        trySend(querySubject(utils))
                        awaitClose { contentResolver.unregisterContentObserver(observer) }
                    }
                }
            }
            .stateIn(
                scope = backgroundScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = SubjectState(false, "", false, DEFAULT_SUBJECT_STYLE, false),
            )

    private val subjectIconEnabled: Flow<Boolean> = subjectState.map { it.enabled }
    override val subjectIconStyle: Flow<String> = subjectState.map { it.style }
    override val subjectIconMonochrome: Flow<Boolean> = subjectState.map { it.monochrome }

    private fun querySubject(utils: PreviewUtils): SubjectState =
        runCatching {
                contentResolver
                    .query(utils.getUri(SUBJECT_ICONS), null, null, null, null)
                    ?.use { cursor ->
                        if (!cursor.moveToFirst()) {
                            return@use SubjectState(false, "", false, DEFAULT_SUBJECT_STYLE, false)
                        }
                        SubjectState(
                            enabled =
                                cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) == ENABLED,
                            state = cursor.getString(cursor.getColumnIndexOrThrow("state")) ?: "",
                            cached =
                                cursor.getInt(cursor.getColumnIndexOrThrow("cached")) == ENABLED,
                            style =
                                cursor.getString(cursor.getColumnIndexOrThrow("style"))
                                    ?: DEFAULT_SUBJECT_STYLE,
                            monochrome =
                                cursor.getInt(cursor.getColumnIndexOrThrow("monochrome")) == ENABLED,
                        )
                    } ?: SubjectState(false, "", false, DEFAULT_SUBJECT_STYLE, false)
            }
            .getOrDefault(SubjectState(false, "", false, DEFAULT_SUBJECT_STYLE, false))

    private fun updateSubject(
        utils: PreviewUtils,
        action: String,
        style: String? = null,
        monochrome: Boolean? = null,
    ): Int =
        runCatching {
                contentResolver.update(
                    utils.getUri(SUBJECT_ICONS),
                    ContentValues().apply {
                        put("action", action)
                        style?.let { put("style", it) }
                        monochrome?.let { put("monochrome", if (it) ENABLED else 0) }
                    },
                    /* where= */ null,
                    /* selectionArgs= */ null,
                )
            }
            .getOrDefault(0)

    override suspend fun setSubjectIconStyle(style: String) {
        previewUtilsFlow.first()?.let { updateSubject(it, "style", style) }
    }

    override suspend fun setSubjectIconMonochrome(enabled: Boolean) {
        previewUtilsFlow.first()?.let { updateSubject(it, "monochrome", monochrome = enabled) }
    }

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
            icon = this.getIcon(),
            isThemedIcon = this == ThemePickerIconStyle.MONOCHROME,
            isExternalLink = false,
        )
    }

    private fun IconStyle.getIcon(): Icon {
        val previewIconPackageName = appContext.resources.getString(R.string.camera_package)
        val appIconDrawable = ShapeIconViewBinder.loadAppIcon(appContext, previewIconPackageName)
        return Icon.Loaded(
            drawable =
                ShapeTileDrawable(
                    context = appContext,
                    icon = appIconDrawable as? AdaptiveIconDrawable,
                    isThemed = this == ThemePickerIconStyle.MONOCHROME,
                ),
            contentDescription = null,
        )
    }

    override val selectedIconStyle: Flow<IconStyle> =
        combine(isThemedIconActivated, subjectIconEnabled) { mono, subject ->
            when {
                subject -> ThemePickerIconStyle.SUBJECT
                mono -> ThemePickerIconStyle.MONOCHROME
                else -> ThemePickerIconStyle.DEFAULT
            }
        }

    fun getThemedIconEnabled(uri: Uri): Boolean {
        val cursor =
            contentResolver.query(
                uri,
                /* projection= */ null,
                /* selection= */ null,
                /* selectionArgs= */ null,
                /* sortOrder= */ null,
            )
        var isEnabled = false
        if (cursor != null && cursor.moveToNext()) {
            isEnabled = (cursor.getInt(cursor.getColumnIndex(COL_ICON_THEMED_VALUE)) == ENABLED)
            val preferences =
                InjectorProvider.getInjector().getPreferences(appContext)
                    as CustomizationPreferences
            if (preferences.getThemedIconEnabled() != isEnabled) {
                preferences.setThemedIconEnabled(isEnabled)
            }
        }
        cursor?.close()
        return isEnabled
    }

    override suspend fun setThemedIconEnabled(enabled: Boolean) {
        setIconStyle(if (enabled) ThemePickerIconStyle.MONOCHROME else ThemePickerIconStyle.DEFAULT)
    }

    override suspend fun setIconStyle(iconStyle: IconStyle): Boolean = withContext(Dispatchers.IO) {
        val utils = previewUtilsFlow.first() ?: return@withContext false
        val cutout = iconStyle == ThemePickerIconStyle.SUBJECT
        val themed = iconStyle == ThemePickerIconStyle.MONOCHROME
        if (!cutout && !themed && iconStyle != ThemePickerIconStyle.DEFAULT) return@withContext false
        val action = if (cutout) {
            val state = querySubject(utils)
            if (state.cached) "enable" else "generate"
        } else {
            if (themed) "themed" else "default"
        }
        if (updateSubject(utils, action) == 0) return@withContext false
        val start = SystemClock.elapsedRealtime()
        val timeout = if (cutout) SUBJECT_TIMEOUT_MS else 5000L
        var sawProcessing = false
        while (SystemClock.elapsedRealtime() - start < timeout) {
            val now = querySubject(utils)
            if (cutout && now.enabled) return@withContext true
            if (!cutout && !now.enabled && getThemedIconEnabled(utils.getUri(ICON_THEMED)) == themed) {
                return@withContext true
            }
            if (now.state == "processing") sawProcessing = true
            // The service is started asynchronously. A previous error/cancelled value must
            // not reject a new request before onStartCommand has had a chance to replace it.
            if (cutout && (sawProcessing || SystemClock.elapsedRealtime() - start > 2000) &&
                now.state in setOf("error", "cancelled", "interrupted")) return@withContext false
            delay(200)
        }
        false
    }

    override suspend fun getIconStyleForLogging(): Int {
        if (BaseFlags.get().isExtendibleThemeManager()) {
            val iconStyle = withTimeoutOrNull(TIMEOUT) { selectedIconStyle.first() }
            return iconStyle?.loggingId ?: APP_ICON_STYLE_UNSPECIFIED
        } else {
            val isThemedIconActivated =
                withTimeoutOrNull(TIMEOUT) { isThemedIconActivated.first() } ?: false
            return if (isThemedIconActivated) APP_ICON_STYLE_THEMED else APP_ICON_STYLE_UNSPECIFIED
        }
    }

    companion object {
        const val ICON_THEMED = "icon_themed"
        const val SET_ICON_THEMED = "set_icon_themed"
        const val SUBJECT_ICONS = "subject_icons"
        const val COL_ICON_THEMED_VALUE = "boolean_value"
        private const val ENABLED = 1
        private const val SUBJECT_TIMEOUT_MS = 5 * 60 * 1000L
        private const val DEFAULT_SUBJECT_STYLE = "frosted"
    }
}
