package org.hello.reader.core.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build

object DeviceProfile {

    private val HISENSE_EINK_MODEL_REGEX = Regex(
        """(^|[^a-z0-9])(a5|a6|a7|a9|touch|hi\s*reader)($|[^a-z0-9])|hlte""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Detects if the host device is an e-reader / E-Ink device (such as Onyx Boox Go Color 7,
     * Boox Page, Boox Palma, Kobo, Supernote, etc.).
     */
    fun isEInkDevice(
        manufacturer: String? = null,
        brand: String? = null,
        model: String? = null,
        device: String? = null,
    ): Boolean {
        val m = (manufacturer ?: safeBuildManufacturer()).lowercase()
        val b = (brand ?: safeBuildBrand()).lowercase()
        val mod = (model ?: safeBuildModel()).lowercase()
        val d = (device ?: safeBuildDevice()).lowercase()

        val isOnyxBoox = m.contains("onyx") ||
            b.contains("onyx") ||
            mod.contains("boox") ||
            d.contains("boox") ||
            mod.contains("palma")

        val isHisenseEInk = (m.contains("hisense") || b.contains("hisense")) &&
            (HISENSE_EINK_MODEL_REGEX.containsMatchIn(mod) || HISENSE_EINK_MODEL_REGEX.containsMatchIn(d))

        val isOtherEInk = m.contains("kobo") || b.contains("kobo") ||
            m.contains("tolino") || b.contains("tolino") ||
            m.contains("supernote") || b.contains("supernote") ||
            m.contains("pocketbook") || b.contains("pocketbook") ||
            mod.contains("inkpalm") || d.contains("inkpalm")

        return isOnyxBoox || isHisenseEInk || isOtherEInk
    }

    /**
     * Detects if the host device is specifically an Onyx Boox device with EPD hardware.
     */
    fun isOnyxDevice(
        manufacturer: String? = null,
        brand: String? = null,
        model: String? = null,
        device: String? = null,
    ): Boolean {
        val m = (manufacturer ?: safeBuildManufacturer()).lowercase()
        val b = (brand ?: safeBuildBrand()).lowercase()
        val mod = (model ?: safeBuildModel()).lowercase()
        val d = (device ?: safeBuildDevice()).lowercase()
        return m.contains("onyx") || b.contains("onyx") || mod.contains("boox") || d.contains("boox") || mod.contains("palma")
    }

    private fun safeBuildManufacturer(): String = try { Build.MANUFACTURER.orEmpty() } catch (_: Throwable) { "" }
    private fun safeBuildBrand(): String = try { Build.BRAND.orEmpty() } catch (_: Throwable) { "" }
    private fun safeBuildModel(): String = try { Build.MODEL.orEmpty() } catch (_: Throwable) { "" }
    private fun safeBuildDevice(): String = try { Build.DEVICE.orEmpty() } catch (_: Throwable) { "" }

    val isCurrentDeviceEInk: Boolean by lazy {
        try {
            isEInkDevice()
        } catch (_: Throwable) {
            false
        }
    }

    val isOnyx: Boolean by lazy {
        try {
            isOnyxDevice()
        } catch (_: Throwable) {
            false
        }
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
