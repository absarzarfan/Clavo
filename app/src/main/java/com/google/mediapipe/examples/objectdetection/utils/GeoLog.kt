package com.google.mediapipe.examples.objectdetection.utils // TEMP-ClavoGeo

import android.graphics.RectF // TEMP-ClavoGeo
import android.util.Log // TEMP-ClavoGeo
import android.view.View // TEMP-ClavoGeo
import androidx.camera.view.PreviewView // TEMP-ClavoGeo
import com.google.mediapipe.examples.objectdetection.BuildConfig // TEMP-ClavoGeo

/**
 * TEMP-ClavoGeo: geometry diagnosis for the Camera-mode "Putar" feature. Debug builds only
 * (BuildConfig.DEBUG), logcat only (tag ClavoGeo), no file, no network, no effect on geometry.
 * To remove: delete this file and every line containing "TEMP-ClavoGeo".
 */
object GeoLog { // TEMP-ClavoGeo
    const val TAG = "ClavoGeo" // TEMP-ClavoGeo
    val enabled: Boolean get() = BuildConfig.DEBUG // TEMP-ClavoGeo

    @Volatile var rawW = 0 // TEMP-ClavoGeo
    @Volatile var rawH = 0 // TEMP-ClavoGeo
    @Volatile var inW = 0 // TEMP-ClavoGeo
    @Volatile var inH = 0 // TEMP-ClavoGeo
    @Volatile var imgRot = 0 // TEMP-ClavoGeo
    @Volatile var manual = 0 // TEMP-ClavoGeo

    /** Set by [snapshot]; consumed by the overlay on the next draw that has a detection. */
    @Volatile var boxRequested = false // TEMP-ClavoGeo

    fun setFrame(rawW: Int, rawH: Int, inW: Int, inH: Int, imgRot: Int, manual: Int) { // TEMP-ClavoGeo
        if (!enabled) return
        this.rawW = rawW; this.rawH = rawH; this.inW = inW; this.inH = inH
        this.imgRot = imgRot; this.manual = manual
    }

    private fun f(v: Float) = String.format(java.util.Locale.US, "%.1f", v) // TEMP-ClavoGeo

    fun rect(r: RectF) = "L=${f(r.left)} T=${f(r.top)} R=${f(r.right)} B=${f(r.bottom)} (w=${f(r.width())} h=${f(r.height())})" // TEMP-ClavoGeo

    fun box(stage: String, r: RectF) { // TEMP-ClavoGeo
        if (enabled) Log.i(TAG, "box $stage: ${rect(r)}")
    }

    /** Maps a rect given in overlay coordinates to screen coordinates through the container's transform. */
    fun toScreen(overlay: View, r: RectF): RectF { // TEMP-ClavoGeo
        val pts = floatArrayOf(r.left, r.top, r.right, r.top, r.right, r.bottom, r.left, r.bottom)
        overlay.matrix.mapPoints(pts)
        for (i in pts.indices step 2) { pts[i] += overlay.left; pts[i + 1] += overlay.top }
        val c = overlay.parent as? View
        var base = overlay
        if (c != null) {
            c.matrix.mapPoints(pts)
            for (i in pts.indices step 2) { pts[i] += c.left; pts[i + 1] += c.top }
            base = c
        }
        val loc = IntArray(2)
        (base.parent as? View)?.getLocationOnScreen(loc)
        for (i in pts.indices step 2) { pts[i] += loc[0]; pts[i + 1] += loc[1] }
        val xs = floatArrayOf(pts[0], pts[2], pts[4], pts[6])
        val ys = floatArrayOf(pts[1], pts[3], pts[5], pts[7])
        return RectF(xs.min(), ys.min(), xs.max(), ys.max())
    }

    private fun view(name: String, v: View?): String { // TEMP-ClavoGeo
        if (v == null) return "$name=null"
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return "$name size=${v.width}x${v.height} screenXY(origin after transform)=(${loc[0]},${loc[1]})"
    }

    /** One burst of lines: layout, transform, frame and overlay state. Also asks for one box trace. */
    fun snapshot( // TEMP-ClavoGeo
        reason: String, angle: Int, container: View, viewFinder: PreviewView, overlay: View,
        overlayState: String, sheet: View?, sheetState: String
    ) {
        if (!enabled) return
        boxRequested = true
        val dm = container.resources.displayMetrics
        Log.i(TAG, "==== snapshot [$reason] angle=$angle density=${dm.density} screen=${dm.widthPixels}x${dm.heightPixels}")
        Log.i(TAG, view("container", container))
        Log.i(TAG, view("previewView", viewFinder))
        Log.i(TAG, view("overlay", overlay))
        val child = viewFinder.getChildAt(0)
        Log.i(TAG, "previewView implementationMode=${viewFinder.implementationMode} scaleType=${viewFinder.scaleType} " +
            "child=${child?.javaClass?.simpleName} ${view("child", child)}")
        Log.i(TAG, "container rotation=${container.rotation} scaleX=${container.scaleX} scaleY=${container.scaleY} " +
            "pivotX=${container.pivotX} pivotY=${container.pivotY} translationX=${container.translationX} translationY=${container.translationY}")
        Log.i(TAG, "frame raw=${rawW}x${rawH} detectorInput=${inW}x${inH} R(imageRotation)=$imgRot M(manual)=$manual")
        Log.i(TAG, overlayState)
        if (sheet != null) {
            val loc = IntArray(2)
            sheet.getLocationOnScreen(loc)
            Log.i(TAG, "bottomSheet height=${sheet.height} topY=${loc[1]} $sheetState")
        }
    }
}
