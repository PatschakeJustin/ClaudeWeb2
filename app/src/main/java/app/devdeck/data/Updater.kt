package app.devdeck.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
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
import java.io.File

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
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class NeedsPermission(val info: UpdateInfo, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Checks the GitHub release "latest" for a newer build, downloads it and hands it to the package installer. */
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

    fun download(info: UpdateInfo) {
        job?.cancel()
        job = scope.launch {
            _state.value = UpdateState.Downloading(info, 0f)
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val target = File(dir, "Humantize-DevApp-${info.versionCode}.apk")
                    http.newCall(Request.Builder().url(BASE + info.apk).build()).execute().use { r ->
                        if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                        val body = r.body!!
                        val total = body.contentLength().takeIf { it > 0 }
                        body.byteStream().use { input ->
                            target.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (total != null) _state.value = UpdateState.Downloading(info, done.toFloat() / total)
                                }
                            }
                        }
                    }
                    target
                }
                install(info, file)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Failed("Download fehlgeschlagen: ${e.message}")
            }
        }
    }

    /** Opens the system installer; if the app may not install packages yet, asks for that first. */
    fun install(info: UpdateInfo, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(info, file)
            return
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        _state.value = UpdateState.Idle
    }

    fun openInstallPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    companion object {
        const val BASE = "https://github.com/PatschakeJustin/ClaudeWeb2/releases/download/latest/"
    }
}
