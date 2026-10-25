/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.customization.picker.settings.ui.section

import android.app.ProgressDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.android.themepicker.R
import com.android.wallpaper.model.CustomizationSectionController
import com.android.wallpaper.picker.SectionView
import java.util.concurrent.Executors

/** Picker UI state owner; all provider I/O is off main, polling stops on release. */
class SubjectIconsSectionController : CustomizationSectionController<SubjectIconsSectionView> {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var view: SubjectIconsSectionView? = null
    private var dialog: ProgressDialog? = null
    private var released = false
    private var updating = false
    private var progressHidden = false
    private var uri: Uri? = null
    private var cached = false
    private val poll = Runnable { refresh() }

    private fun resolve(context: Context): Uri? {
        val home = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_META_DATA)
        val authority = home?.activityInfo?.metaData?.getString(
            context.getString(R.string.themed_icon_metadata_key)) ?: return null
        return Uri.Builder().scheme("content").authority(authority).appendPath("subject_icons").build()
    }

    override fun isAvailable(context: Context) = resolve(context) != null

    override fun createView(context: Context): SubjectIconsSectionView {
        uri = resolve(context)
        return SubjectIconsSectionView(context).also { section ->
            view = section
            section.toggle.setOnCheckedChangeListener { _, enabled ->
                if (!updating) {
                    progressHidden = false
                    if (!enabled) {
                        dialog?.dismiss()
                        dialog = null
                    }
                    command(if (enabled) { if (cached) "enable" else "generate" } else "disable")
                }
            }
            section.clear.setOnClickListener {
                android.app.AlertDialog.Builder(context)
                    .setMessage(R.string.subject_icons_clear_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.subject_icons_clear) { _, _ -> command("clear") }.show()
            }
            section.refresh.setOnClickListener { progressHidden = false; command("generate") }
            section.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { refresh() }
                override fun onViewDetachedFromWindow(v: View) {
                    main.removeCallbacks(poll)
                    dialog?.dismiss()
                    dialog = null
                }
            })
        }
    }

    private fun command(action: String) {
        val section = view ?: return
        val target = uri ?: return
        section.toggle.isEnabled = false
        worker.execute {
            var failure: String? = null
            try {
                check(section.context.contentResolver.update(target,
                    ContentValues().apply { put("action", action) }, null, null) == 1)
            } catch (error: Exception) {
                failure = error.message ?: section.context.getString(R.string.subject_icons_failed)
            }
            main.post {
                if (!released && view === section) {
                    if (failure != null) section.status.text = failure else refresh()
                    section.toggle.isEnabled = true
                }
            }
        }
    }

    private fun refresh() {
        val section = view ?: return
        val target = uri ?: return
        if (released || !section.isAttachedToWindow) return
        main.removeCallbacks(poll)
        worker.execute {
            var state: State? = null
            try {
                section.context.contentResolver.query(target, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        fun int(name: String) = cursor.getInt(cursor.getColumnIndexOrThrow(name))
                        fun text(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name)) ?: ""
                        state = State(int("enabled") != 0, text("state"), int("done"), int("total"),
                            int("fallbacks"), int("cached") != 0, text("error"))
                    }
                }
            } catch (error: Exception) {
                state = State(false, "error", 0, 0, 0, false,
                    error.message ?: section.context.getString(R.string.subject_icons_failed))
            }
            main.post {
                if (!released && view === section && section.isAttachedToWindow) {
                    state?.let { render(section, it) }
                    main.postDelayed(poll, 1000)
                }
            }
        }
    }

    private fun render(section: SubjectIconsSectionView, state: State) {
        cached = state.cached
        val processing = state.state == "processing"
        updating = true
        section.toggle.isChecked = state.enabled || processing
        section.toggle.isEnabled = !processing
        updating = false
        section.clear.visibility = if (cached) View.VISIBLE else View.GONE
        section.clear.isEnabled = !processing
        section.refresh.visibility = if (cached) View.VISIBLE else View.GONE
        section.refresh.isEnabled = !processing
        section.status.text = when {
            processing -> section.context.getString(R.string.subject_icons_progress, state.done, state.total)
            state.state == "error" -> state.error
            state.state == "interrupted" -> section.context.getString(R.string.subject_icons_interrupted)
            cached -> section.context.getString(R.string.subject_icons_ready, state.done, state.fallbacks)
            else -> section.context.getString(R.string.subject_icons_summary)
        }
        if (processing && !progressHidden) {
            if (dialog == null) {
                dialog = ProgressDialog(section.context).apply {
                    setTitle(R.string.subject_icons_title)
                    setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
                    setCancelable(false)
                    setButton(ProgressDialog.BUTTON_NEGATIVE, section.context.getString(android.R.string.cancel)) { _, _ ->
                        progressHidden = true
                        dialog?.dismiss()
                        dialog = null
                        command("disable")
                    }
                    setButton(ProgressDialog.BUTTON_NEUTRAL, section.context.getString(R.string.subject_icons_background)) { _, _ ->
                        progressHidden = true
                        dialog?.dismiss()
                        dialog = null
                    }
                    show()
                }
            }
            dialog?.isIndeterminate = state.total == 0
            dialog?.max = state.total
            dialog?.progress = state.done
        } else if (!processing) {
            dialog?.dismiss()
            dialog = null
        }
    }

    override fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        dialog?.dismiss()
        dialog = null
        view = null
    }

    private data class State(val enabled: Boolean, val state: String, val done: Int, val total: Int,
        val fallbacks: Int, val cached: Boolean, val error: String)
}

class SubjectIconsSectionView(context: Context) : SectionView(context, null) {
    val toggle = Switch(context)
    val status = TextView(context)
    val clear = Button(context)
    val refresh = Button(context)
    init {
        orientation = VERTICAL
        val padding = (16 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        setTitle(context.getString(R.string.subject_icons_title))
        toggle.setText(R.string.subject_icons_title)
        status.setText(R.string.subject_icons_summary)
        clear.setText(R.string.subject_icons_clear)
        refresh.setText(R.string.subject_icons_refresh)
        clear.visibility = View.GONE
        refresh.visibility = View.GONE
        addView(toggle, LayoutParams(-1, -2))
        addView(status, LayoutParams(-1, -2))
        val actions = LinearLayout(context).apply {
            addView(refresh, LayoutParams(0, -2, 1f))
            addView(clear, LayoutParams(0, -2, 1f))
        }
        addView(actions, LayoutParams(-1, -2))
    }
}
