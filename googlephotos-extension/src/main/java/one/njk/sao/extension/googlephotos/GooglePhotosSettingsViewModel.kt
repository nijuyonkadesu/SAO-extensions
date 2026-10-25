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
import one.njk.sao.extension.runtime.EncryptedDraftStore
import org.json.JSONObject

internal class GooglePhotosSettingsViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    data class State(val loaded: Boolean = false, val working: Boolean = true, val name: String = "Google Photos",
        val config: GooglePhotosConfig = GooglePhotosConfig(), val status: Int = R.string.ready, val finished: Boolean = false,
        val albums: List<GooglePhotosProtocol.Album> = emptyList(), val nextAlbum: String? = null, val chooseAlbum: Boolean = false)
    private val runtime = GooglePhotosRuntime.get(app)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    val editing: String? = saved[ExtensionProtocol.PROFILE]
    val profile = editing ?: saved.get<String>("draft_profile") ?: UUID.randomUUID().toString().also { saved["draft_profile"]=it }
    private val drafts=EncryptedDraftStore(app,"googlephotos")
    private val draftId = saved.get<String>("private_draft") ?: UUID.randomUUID().toString().also { saved["private_draft"]=it }
    private var draftClosed = false
    private var previous: GooglePhotosProfile? = null
    private var credentialsEdited = false
    val hasChanges get() = state.value.loaded && (state.value.name != (previous?.name ?: "Google Photos") ||
        dirtyConfig() != (previous?.config ?: GooglePhotosConfig()))
    private var handshake: GooglePhotosConnect.Pending? = null

    init {
        viewModelScope.launch {
            try {
                previous = editing?.let { runtime.profiles.get(it) }
                val draft=drafts.read(draftId)?.also { require(it.getString("profile")==profile) }
                credentialsEdited=draft?.optBoolean("credentialsEdited") ?: false
                var config=draft?.optJSONObject("config")?.let { GooglePhotosConfig.read(it,validate=false) } ?: previous?.config ?: GooglePhotosConfig()
                if(!credentialsEdited) previous?.config?.let { current ->
                    config=config.copy(cookies=current.cookies,scopedCookies=current.scopedCookies,
                        nativeAccount=current.nativeAccount,nativeSession=current.nativeSession)
                }
                mutable.value = State(true, false, draft?.optString("name") ?: previous?.name ?: "Google Photos", config)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = State(working = false, status = R.string.storage_error) }
        }
    }
    fun name(value: String) { if (!state.value.working) mutable.value = state.value.copy(name = value) }
    fun checkpoint(discard: Boolean=false): Deferred<Unit>? {
        val current=state.value
        if(discard) draftClosed=true
        if(!current.loaded && !draftClosed) return null
        val value=if(draftClosed || current.finished) null else JSONObject().put("profile",profile).put("name",current.name)
            .put("config",current.config.json()).put("credentialsEdited",credentialsEdited)
        return drafts.checkpoint(draftId,value).also { pending -> pending.invokeOnCompletion { error ->
            if(error!=null && error !is CancellationException) viewModelScope.launch {
                mutable.value=state.value.copy(status=R.string.storage_error)
            }
        } }
    }
    private fun dirtyConfig(): GooglePhotosConfig {
        val config=state.value.config
        val baseline=previous?.config ?: GooglePhotosConfig()
        return if(credentialsEdited) config else config.copy(cookies=baseline.cookies,scopedCookies=baseline.scopedCookies,
            nativeAccount=baseline.nativeAccount,nativeSession=baseline.nativeSession)
    }
    fun account(value: String) {
        if (!state.value.working && value.toIntOrNull() != state.value.config.account) {
            credentialsEdited=true
            mutable.value = state.value.copy(config = state.value.config.copy(account = value.toIntOrNull() ?: -1, nativeAccount = "", nativeSession = ""))
        }
    }
    fun connect(languageTags: String, launch: (Intent) -> Unit) = work {
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
            launch(intent.putExtra(one.njk.sao.source.SourceLocales.EXTRA_LANGUAGE_TAGS,languageTags).putExtra(GooglePhotosConnect.EXTRA, pending.nonce).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            val result = withTimeout(115_000) { pending.result.await() }
            android.util.Log.i("SAO.GooglePhotos", "Native login received; exchanging web session")
            val config = GooglePhotosApi.connect(result.loginUrl, result.account, runtime.http())
            credentialsEdited=true
            mutable.value = state.value.copy(config = config, working = false, status = R.string.connected)
            checkpoint()?.await()
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
                val text = String(buffer, 0, length, Charsets.UTF_8)
                GooglePhotosCookies.parse(text) to GooglePhotosCookies.scoped(text)
            } ?: throw ExtensionFailure("INVALID_RESPONSE")
        }
        credentialsEdited=true
        mutable.value = state.value.copy(config = state.value.config.copy(cookies = cookies.first, scopedCookies = cookies.second,
            nativeAccount = "", nativeSession = ""), working = false, status = R.string.session_imported)
    }
    fun pasteSession(value: String) = work {
        val cookies = withContext(Dispatchers.IO) { GooglePhotosCookies.parse(value) to GooglePhotosCookies.scoped(value) }
        credentialsEdited=true
        mutable.value = state.value.copy(config = state.value.config.copy(cookies = cookies.first, scopedCookies = cookies.second,
            nativeAccount = "", nativeSession = ""), working = false, status = R.string.session_imported)
    }
    fun save() = work {
        val draft = state.value
        draft.config.validate()
        val (_,config)=useDraft(draft.config) { it.check() }
        runtime.profiles.save(profile, draft.name, config,preserveCurrentSession=previous!=null && !credentialsEdited)
        runtime.clearSessions()
        checkpoint(discard=true)?.await()
        mutable.value = draft.copy(working = false, finished = true)
    }
    fun check() = work {
        state.value.config.validate()
        val (_,config)=useDraft(state.value.config) { it.check() }
        mutable.value = state.value.copy(config = config, working = false, status = R.string.connected)
    }
    fun albums(next: Boolean = false) = work {
        val config = state.value.config.also { it.validate() }
        val (page,updated)=useDraft(config) { it.albums(state.value.nextAlbum.takeIf { next }) }
        mutable.value = state.value.copy(config = updated, working = false, albums = page.first, nextAlbum = page.second, chooseAlbum = true)
    }
    private suspend fun <T> useDraft(config: GooglePhotosConfig, block: suspend (GooglePhotosApi)->T): Pair<T,GooglePhotosConfig> = withContext(Dispatchers.IO) {
        val baseline=previous
        suspend fun read(api: GooglePhotosApi): Pair<T,GooglePhotosConfig> = block(api) to api.currentConfig().copy(album=config.album,albumName=config.albumName)
        if(baseline!=null && !credentialsEdited) {
            val current=runtime.profiles.get(profile)
            if(current.revision!=baseline.revision) throw ExtensionFailure("PROFILE_CHANGED")
            runtime.read(current,::read)
        } else read(runtime.check(config))
    }
    fun album(row: GooglePhotosProtocol.Album?) {
        mutable.value = state.value.copy(config = state.value.config.copy(album = row?.key.orEmpty(), albumName = row?.name.orEmpty()), chooseAlbum = false)
    }
    fun closeAlbums() { mutable.value = state.value.copy(chooseAlbum = false) }
    fun delete() = work {
        runtime.profiles.get(profile).let { runtime.profiles.delete(profile, it.revision) }
        runtime.clearSessions()
        checkpoint(discard=true)?.await()
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
