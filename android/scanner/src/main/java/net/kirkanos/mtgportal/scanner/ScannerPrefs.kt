package net.kirkanos.mtgportal.scanner

import android.content.Context
import androidx.core.content.edit

/**
 * Scanner choices that outlive a session: the set filter (sorting one box
 * takes many sessions) and the camera. A forgotten filter would hide cards,
 * so the scanner always shows when one is active.
 */
class ScannerPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("scanner", Context.MODE_PRIVATE)

    var sets: Set<String>
        get() = prefs.getStringSet(SETS, null).orEmpty().toSet()
        set(value) = prefs.edit { putStringSet(SETS, value) }

    /** [CameraChoice.key], null = the phone's default camera. */
    var camera: String?
        get() = prefs.getString(CAMERA, null)
        set(value) = prefs.edit { putString(CAMERA, value) }

    private companion object {
        const val SETS = "sets"
        const val CAMERA = "camera"
    }
}
