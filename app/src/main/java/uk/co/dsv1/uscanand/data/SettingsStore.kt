package uk.co.dsv1.uscanand.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.co.dsv1.uscanand.pdf.PageSize

enum class ImageQuality(val maxDimension: Int, val jpegQuality: Int) {
    STANDARD(2000, 80),
    HIGH(3000, 90),
}

data class ExportSettings(
    val pageSize: PageSize = PageSize.A4,
    val quality: ImageQuality = ImageQuality.STANDARD,
    val ocr: Boolean = true,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<ExportSettings> = _state.asStateFlow()

    fun update(transform: (ExportSettings) -> ExportSettings) {
        val updated = transform(_state.value)
        prefs.edit {
            putString(KEY_PAGE_SIZE, updated.pageSize.name)
            putString(KEY_QUALITY, updated.quality.name)
            putBoolean(KEY_OCR, updated.ocr)
        }
        _state.value = updated
    }

    private fun read(): ExportSettings {
        val defaults = ExportSettings()
        return ExportSettings(
            pageSize = enumOrDefault(prefs.getString(KEY_PAGE_SIZE, null), defaults.pageSize),
            quality = enumOrDefault(prefs.getString(KEY_QUALITY, null), defaults.quality),
            ocr = prefs.getBoolean(KEY_OCR, defaults.ocr),
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private companion object {
        const val KEY_PAGE_SIZE = "page_size"
        const val KEY_QUALITY = "quality"
        const val KEY_OCR = "ocr"
    }
}
