package com.android.customization.picker.settings.ui.section

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import com.android.customization.picker.settings.ui.view.MoreSettingsSectionView
import com.android.themepicker.R
import com.android.wallpaper.model.CustomizationSectionController

class DepthStudioSectionController : CustomizationSectionController<MoreSettingsSectionView> {
    private fun studioIntent() = Intent().setClassName(
        "org.petalos.depth",
        "org.petalos.depth.DepthStudioActivity",
    )

    override fun isAvailable(context: Context): Boolean =
        studioIntent().resolveActivity(context.packageManager) != null

    @SuppressLint("InflateParams")
    override fun createView(context: Context): MoreSettingsSectionView =
        (LayoutInflater.from(context).inflate(R.layout.depth_studio_section_view, null)
            as MoreSettingsSectionView).apply {
            setOnClickListener { context.startActivity(studioIntent()) }
        }
}
