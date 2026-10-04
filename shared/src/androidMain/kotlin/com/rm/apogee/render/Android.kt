package com.rm.apogee.render

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.rm.apogee.platform.Log
import com.rm.apogee.render.gl.GLES30
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** [renderer] on a GLSurfaceView's own thread. */
class GlSurfaceRenderer(val renderer: GlRenderer) : GLSurfaceView.Renderer {
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        com.rm.apogee.platform.HintedThreads.set("gl", android.os.Process.myTid())
        renderer.onSurfaceCreated()
    }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = renderer.onSurfaceChanged(width, height)
    override fun onDrawFrame(gl: GL10?) = renderer.onDrawFrame()
}

/** Part pictures kept as PNG files under [cacheRoot]. */
class PngPictures(private val cacheRoot: File) : PictureStore {
    override fun load(folder: String, key: String): ImageBitmap? {
        val file = File(File(cacheRoot, folder), "$key.png")
        return if (file.exists()) BitmapFactory.decodeFile(file.path)?.asImageBitmap() else null
    }

    override fun save(folder: String, key: String, argb: IntArray, size: Int) {
        val dir = File(cacheRoot, folder).also { it.mkdirs() }
        val bitmap = Bitmap.createBitmap(argb, size, size, Bitmap.Config.ARGB_8888)
        File(dir, "$key.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

/**
 * Chooses a tier from RAM, core count and the GL renderer string. Call it on the GL thread, since
 * [GLES30.glGetString] needs a current context.
 */
fun QualityTier.Companion.detect(context: Context): QualityTier {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)
    val totalRamGb = memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
    val cores = Runtime.getRuntime().availableProcessors()
    val renderer = runCatching { GLES30.glGetString(GLES30.GL_RENDERER) }.getOrNull() ?: ""

    val tier = when {
        totalRamGb >= 6.0 && cores >= 8 && Build.VERSION.SDK_INT >= 31 -> QualityTier.HIGH
        totalRamGb >= 4.0 && cores >= 6 -> QualityTier.MEDIUM
        else -> QualityTier.LOW
    }

    Log.i(
        "ApogeeQuality",
        "quality=$tier ram=${"%.1f".format(totalRamGb)}GB cores=$cores " +
            "sdk=${Build.VERSION.SDK_INT} renderer='$renderer'",
    )
    return tier
}
