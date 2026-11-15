package app.devdeck.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.devdeck.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apk: String = "Humantize-DevApp.apk",
    val notes: String = "",
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Checks the GitHub release "latest" for a newer build and offers to download it. */
class Updater(private val context: Context, private val http: OkHttpClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null

    val installedVersion: String get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    /** [manual] = the user asked; then "no update" is reported too. */
    fun check(manual: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (manual) _state.value = UpdateState.Checking
            _state.value = try {
                val info = withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url(BASE + "version.json").build()).execute().use { r ->
                        if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                        json.decodeFromString(UpdateInfo.serializer(), r.body!!.string())
                    }
                }
                when {
                    info.versionCode > BuildConfig.VERSION_CODE -> UpdateState.Available(info)
                    manual -> UpdateState.UpToDate
                    else -> UpdateState.Idle
                }
            } catch (e: Exception) {
                if (manual) UpdateState.Failed("Update-Prüfung fehlgeschlagen: ${e.message}") else UpdateState.Idle
            }
        }
    }

    fun dismiss() { job?.cancel(); _state.value = UpdateState.Idle }

    /**
     * Opens the APK download in the browser; Android's own installer takes over from there. The app
     * deliberately does not download and install APKs itself (Play Protect treats that as malware behaviour).
     */
    fun openDownload(info: UpdateInfo) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(BASE + info.apk)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        _state.value = UpdateState.Idle
    }

    companion object {
        const val BASE = "https://github.com/PatschakeJustin/ClaudeWeb2/releases/download/latest/"
    }
}
