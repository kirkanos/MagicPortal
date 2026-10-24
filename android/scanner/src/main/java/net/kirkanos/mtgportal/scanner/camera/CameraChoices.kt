package net.kirkanos.mtgportal.scanner.camera

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * One back camera to scan with. Phones hide their lenses behind one logical
 * camera that switches by itself; picking a lens directly helps when it
 * picks badly - the ultra-wide of a Pixel 8 Pro focuses from 2 cm, the main
 * camera only from 11 cm, so a card held close is sharp on the first only.
 */
data class CameraChoice(
    /** "logicalId" or "logicalId/physicalId"; stored in [net.kirkanos.mtgportal.scanner.ScannerPrefs]. */
    val key: String,
    /** Field of view relative to the default camera: 0.5 = twice as wide. */
    val zoom: Float,
    /** Closest sharp distance, null when the camera does not say. */
    val minFocusCm: Int?,
)

object CameraChoices {
    /**
     * The back cameras and their lenses, widest first. A lens appears once:
     * phones also list sensor crops of a lens as cameras of their own (the
     * Pixel 8 Pro's "2x" and "10x"), which neither focus closer nor see
     * finer than the full sensor.
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun list(infos: List<CameraInfo>): List<CameraChoice> {
        val back = infos.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
        val main = back.firstOrNull()?.let(::equivalentFocal) ?: return emptyList()
        val out = ArrayList<Pair<Float, CameraChoice>>()
        for (logical in back) {
            val id = Camera2CameraInfo.from(logical).cameraId
            val lenses = runCatching { logical.physicalCameraInfos }.getOrDefault(emptySet())
            if (lenses.size > 1) {
                for (lens in lenses) {
                    val f = equivalentFocal(lens) ?: continue
                    out += focal(lens) to CameraChoice("$id/${Camera2CameraInfo.from(lens).cameraId}", f / main, minFocusCm(lens))
                }
            } else {
                val f = equivalentFocal(logical) ?: continue
                out += focal(logical) to CameraChoice(id, f / main, minFocusCm(logical))
            }
        }
        // Widest first, so of one lens the full sensor stays.
        return out.sortedBy { it.second.zoom }.distinctBy { it.first }.map { it.second }
    }

    /** The selector for [key]; the default back camera for null or an unknown key. */
    @SuppressLint("UnsafeOptInUsageError")
    fun selector(key: String?): CameraSelector {
        val logical = key?.substringBefore('/') ?: return CameraSelector.DEFAULT_BACK_CAMERA
        val physical = key.substringAfter('/', "").ifEmpty { null }
        return CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == logical }.ifEmpty { infos } }
            .apply { if (physical != null) setPhysicalCameraId(physical) }
            .build()
    }

    /** The lens's real focal length in mm: the same for a sensor and its crops. */
    @SuppressLint("UnsafeOptInUsageError")
    private fun focal(info: CameraInfo): Float =
        Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 0f

    /** 35 mm equivalent focal length, so lenses with different sensors compare. */
    @SuppressLint("UnsafeOptInUsageError")
    private fun equivalentFocal(info: CameraInfo): Float? {
        val c = Camera2CameraInfo.from(info)
        val focal = focal(info).takeIf { it > 0f } ?: return null
        val size = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        return focal * 43.27f / hypot(size.width, size.height)
    }

    /** The minimum focus distance comes in diopters (1/m); 0 means fixed focus. */
    @SuppressLint("UnsafeOptInUsageError")
    private fun minFocusCm(info: CameraInfo): Int? {
        val d = Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: return null
        return if (d > 0f) (100f / d).roundToInt() else null
    }
}
