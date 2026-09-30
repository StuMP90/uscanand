package uk.co.dsv1.uscanand

import android.app.Application
import android.content.Context
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.dsv1.uscanand.data.DocumentRepository
import uk.co.dsv1.uscanand.data.SettingsStore
import uk.co.dsv1.uscanand.pdf.ExportManager

class UScanApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var repository: DocumentRepository
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var exporter: ExportManager
        private set

    override fun onCreate() {
        super.onCreate()
        repository = DocumentRepository(this)
        settings = SettingsStore(this)
        exporter = ExportManager(this, repository, settings, appScope)
        appScope.launch { repository.load() }
    }

    /** Adds images to a document in the background, so the import outlives the screen that started it. */
    fun importPages(docId: String, uris: List<Uri>) {
        appScope.launch {
            val failed = repository.addImages(docId, uris)
            if (failed > 0) {
                withContext(Dispatchers.Main) {
                    val message = resources.getQuantityString(R.plurals.import_failed, failed, failed)
                    Toast.makeText(this@UScanApp, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

val Context.app: UScanApp get() = applicationContext as UScanApp
