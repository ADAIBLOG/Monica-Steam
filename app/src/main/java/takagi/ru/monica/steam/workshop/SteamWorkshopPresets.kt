package takagi.ru.monica.steam.workshop

import android.content.Context
import java.io.File

internal object SteamWorkshopPresets {
    @Volatile private var instance: WorkshopPresetRepository? = null
    fun get(context: Context): WorkshopPresetRepository = instance ?: synchronized(this) {
        instance ?: FileWorkshopPresetRepository(File(context.applicationContext.filesDir, "workshop-presets"))
            .also { instance = it }
    }
}
