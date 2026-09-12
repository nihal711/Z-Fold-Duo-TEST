package com.foldduo.hinge

import android.app.Application
import com.foldduo.hinge.effect.EffectPreferences
import org.lsposed.hiddenapibypass.HiddenApiBypass

class ZFoldDuoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        HiddenApiBypass.setHiddenApiExemptions(
            "Landroid/graphics/HardwareRenderer;",
            "Landroid/graphics/Shader;",
            "Landroid/view/SurfaceControl",
            "Landroid/view/View;",
            "Landroid/view/ViewRootImpl;",
        )
        EffectPreferences.initialize(this)
        AngleRuntime.init(this)
    }
}
