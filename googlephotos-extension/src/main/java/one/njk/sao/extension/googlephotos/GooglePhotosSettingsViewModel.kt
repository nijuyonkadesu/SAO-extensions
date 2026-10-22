package one.njk.sao.extension.googlephotos

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import one.njk.sao.source.ExtensionFailure
import one.njk.sao.source.ExtensionProtocol
import java.util.UUID

internal class GooglePhotosSettingsViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    data class State(val loaded: Boolean = false, val working: Boolean = true, val name: String = "Google Photos",
        val config: GooglePhotosConfig = GooglePhotosConfig(), val status: Int = R.string.ready, val finished: Boolean = false,
        val albums: List<GooglePhotosProtocol.Album> = emptyList(), val nextAlbum: String? = null, val chooseAlbum: Boolean = false)
    private val runtime = GooglePhotosRuntime.get(app)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    val editing: String? = saved[ExtensionProtocol.PROFILE]
    val profile = editing ?: UUID.randomUUID().toString()
    private var previous: GooglePhotosProfile? = null
    val hasChanges get() = state.value.loaded && (state.value.name != (previous?.name ?: "Google Photos") ||
        state.value.config != (previous?.config ?: GooglePhotosConfig()))
    private var handshake: GooglePhotosConnect.Pending? = null

    init {
        viewModelScope.launch {
            try {
                previous = editing?.let { runtime.profiles.get(it) }
                mutable.value = State(true, false, previous?.name ?: "Google Photos", previous?.config ?: GooglePhotosConfig())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = State(working = false, status = R.string.storage_error) }
        }
    }
    fun name(value: String) { if (!state.value.working) mutable.value = state.value.copy(name = value) }
    fun account(value: String) {
        if (!state.value.working) mutable.value = state.value.copy(config = state.value.config.copy(account = value.toIntOrNull() ?: -1))
    }
    fun connect(launch: (Intent) -> Unit) = work {
        val app = getApplication<Application>()
        val intent = withContext(Dispatchers.IO) {
            val info = app.packageManager.getApplicationInfo(GooglePhotosConnect.PHOTOS, 0)
            if (!GooglePhotosConnect.isPhotos(app.packageManager, info.uid)) throw ExtensionFailure("ACCOUNT_MISSING")
            app.packageManager.getLaunchIntentForPackage(GooglePhotosConnect.PHOTOS) ?: throw ExtensionFailure("ACCOUNT_MISSING")
        }
        val pending = GooglePhotosConnect.begin().also { handshake = it }
        var visibility: Uri? = null
        try {
            withContext(Dispatchers.IO) { visibility = GooglePhotosVisibility.grant(app) }
            android.util.Log.i("SAO.GooglePhotos", "Connect requested; temporary package visibility granted")
            mutable.value = state.value.copy(status = R.string.waiting_vector)
            launch(intent.putExtra(GooglePhotosConnect.EXTRA, pending.nonce).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            val result = withTimeout(115_000) { pending.result.await() }
            android.util.Log.i("SAO.GooglePhotos", "Native login received; exchanging web session")
            val config = GooglePhotosApi.connect(result.loginUrl, result.account, runtime.http())
            mutable.value = state.value.copy(config = config, working = false, status = R.string.connected)
            android.util.Log.i("SAO.GooglePhotos", "Web session connected")
        } finally {
            GooglePhotosConnect.cancel(pending.nonce); handshake = null
            withContext(NonCancellable + Dispatchers.IO) { visibility?.let { GooglePhotosVisibility.revoke(app, it) } }
        }
    }
    fun cancelConnection() { handshake?.result?.completeExceptionally(ExtensionFailure("CONNECT_CANCELLED")) }
    fun import(uri: Uri) = work {
        val cookies = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(128 * 1024 + 1)
                var length = 0
                while (length < buffer.size) {
                    val count = input.read(buffer, length, buffer.size - length)
                    if (count < 0) break
                    length += count
                }
                require(length < buffer.size)
                GooglePhotosCookies.parse(String(buffer, 0, length, Charsets.UTF_8))
            } ?: throw ExtensionFailure("INVALID_RESPONSE")
        }
        mutable.value = state.value.copy(config = state.value.config.copy(cookies = cookies), working = false, status = R.string.session_imported)
    }
    fun pasteSession(value: String) = work {
        val cookies = withContext(Dispatchers.IO) { GooglePhotosCookies.parse(value) }
        mutable.value = state.value.copy(config = state.value.config.copy(cookies = cookies), working = false, status = R.string.session_imported)
    }
    fun save() = work {
        val draft = state.value
        draft.config.validate()
        val api = runtime.check(draft.config)
        withContext(Dispatchers.IO) { api.check() }
        runtime.profiles.save(profile, draft.name, api.currentConfig())
        runtime.clearSessions()
        mutable.value = draft.copy(working = false, finished = true)
    }
    fun check() = work {
        state.value.config.validate()
        val api = runtime.check(state.value.config)
        withContext(Dispatchers.IO) { api.check() }
        mutable.value = state.value.copy(config = api.currentConfig(), working = false, status = R.string.connected)
    }
    fun albums(next: Boolean = false) = work {
        val config = state.value.config.also { it.validate() }
        val api = runtime.check(config)
        val page = withContext(Dispatchers.IO) { api.albums(state.value.nextAlbum.takeIf { next }) }
        mutable.value = state.value.copy(config = api.currentConfig(), working = false, albums = page.first, nextAlbum = page.second, chooseAlbum = true)
    }
    fun album(row: GooglePhotosProtocol.Album?) {
        mutable.value = state.value.copy(config = state.value.config.copy(album = row?.key.orEmpty(), albumName = row?.name.orEmpty()), chooseAlbum = false)
    }
    fun closeAlbums() { mutable.value = state.value.copy(chooseAlbum = false) }
    fun delete() = work {
        runtime.profiles.get(profile).let { runtime.profiles.delete(profile, it.revision) }
        runtime.clearSessions()
        mutable.value = state.value.copy(working = false, finished = true)
    }
    private fun work(block: suspend () -> Unit) {
        if (!state.value.loaded || state.value.working) return
        mutable.value = state.value.copy(working = true, status = R.string.working)
        viewModelScope.launch {
            try { block() }
            catch (_: TimeoutCancellationException) {
                android.util.Log.w("SAO.GooglePhotos", "Native connection timed out")
                mutable.value = state.value.copy(working = false, status = R.string.vector_missing)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val code = GooglePhotosRuntime.failure(error).code
                android.util.Log.w("SAO.GooglePhotos", "Settings operation failed: $code (${error.javaClass.simpleName})")
                val message = when {
                    error is android.content.pm.PackageManager.NameNotFoundException || code == "ACCOUNT_MISSING" -> R.string.photos_missing
                    error is IllegalArgumentException -> R.string.invalid_configuration
                    code == "AUTH_UNSUPPORTED" -> R.string.native_auth_unavailable
                    code == "CONNECT_CANCELLED" -> R.string.connect_cancelled
                    code in setOf("HTTP_401", "HTTP_403") -> R.string.auth_error
                    code == "HTTP_429" -> R.string.rate_limited
                    code.startsWith("HTTP_") -> R.string.service_error
                    code in setOf("NETWORK", "TIMEOUT", "TLS") -> R.string.network_error
                    code == "INVALID_RESPONSE" -> R.string.invalid_response
                    else -> R.string.storage_error
                }
                mutable.value = state.value.copy(working = false, status = message)
            }
        }
    }
    override fun onCleared() { handshake?.let { GooglePhotosConnect.cancel(it.nonce) } }
}
