package one.njk.sao.extension.immich

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import one.njk.sao.immichprovider.ImmichConfig
import one.njk.sao.immich.*
import one.njk.sao.source.ExtensionProtocol
import one.njk.sao.extension.runtime.EncryptedDraftStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ImmichSettingsViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    data class State(val loaded: Boolean = false, val working: Boolean = false, val status: Int = R.string.immich_setup_description,
        val version: String? = null, val albums: List<ImmichAlbum> = emptyList(), val finished: Boolean = false)
    private val store = ConnectionStore(app)
    private val drafts = EncryptedDraftStore(app,"immich-settings")
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    val editingId: String? = saved[ExtensionProtocol.PROFILE]
    val profileId = editingId ?: saved.get<String>("draft_profile") ?: UUID.randomUUID().toString().also { saved["draft_profile"]=it }
    private val draftId = saved.get<String>("private_draft") ?: UUID.randomUUID().toString().also { saved["private_draft"]=it }
    var name = "Immich"; var url = ""; var key = ""; var albumId: String? = null
    private var previous: ConnectionStore.Connection? = null
    private data class Draft(val name: String,val url: String,val key: String,val album: String?) {
        fun json()=JSONObject().put("name",name).put("url",url).put("key",key).put("album",album)
    }
    private fun draft()=Draft(name,url,key,albumId)
    private var initial: Draft?=null
    private var draftClosed=false
    val hasChanges get()=state.value.loaded && initial!=draft()
    internal val importConnection get()=previous
    internal val importsUnchanged get()=previous?.config?.let { it.serverUrl==url.trim() && it.apiKey==key.trim() && it.albumId==albumId }==true
    init { viewModelScope.launch {
        try {
            previous = editingId?.let { store.get(it) }
            previous?.let { name=it.name; url=it.config.serverUrl; key=it.config.apiKey; albumId=it.config.albumId }
            initial=draft()
            val restored=drafts.read(draftId)?.also {
                require(it.getInt("version")==1 && it.getString("profile")==profileId)
                val draft=readDraft(it.getJSONObject("draft"))
                initial=readDraft(it.getJSONObject("initial"))
                name=draft.name; url=draft.url; key=draft.key; albumId=draft.album
            }
            val albums=restored?.optJSONArray("albums")
            mutable.value = State(loaded=true,version=restored?.optString("serverVersion")?.takeIf(String::isNotBlank),
                albums=if(albums==null) emptyList() else List(albums.length()) { index -> albums.getJSONObject(index).let {
                    ImmichAlbum(it.getString("id"),it.getString("name"))
                } })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if(initial==null) initial=draft(); mutable.value = State(loaded=true,status=R.string.immich_storage_error) }
    } }
    /** Form changes stay in memory; lifecycle boundaries queue one encrypted, conflated snapshot. */
    fun checkpoint(discard: Boolean=false): Deferred<Unit>? {
        if(!discard && !draftClosed && !state.value.loaded) return null
        val value=if(discard || draftClosed || state.value.finished) null else JSONObject().put("version",1)
            .put("profile",profileId).put("initial",requireNotNull(initial).json()).put("draft",draft().json())
            .put("serverVersion",state.value.version).put("albums",JSONArray(state.value.albums.map {
                JSONObject().put("id",it.id).put("name",it.albumName)
            }))
        if(discard) draftClosed=true
        return drafts.checkpoint(draftId,value).also { pending -> viewModelScope.launch {
            try { pending.await() }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { mutable.value=state.value.copy(status=R.string.immich_storage_error) }
        } }
    }
    private fun readDraft(value: JSONObject)=Draft(value.getString("name"),value.getString("url"),value.getString("key"),
        value.optString("album").takeUnless { value.isNull("album") || it.isBlank() })
    fun check(save: Boolean) {
        if (!state.value.loaded || state.value.working) return
        mutable.value = mutable.value.copy(working=true,status=R.string.immich_checking)
        viewModelScope.launch {
            try {
                require(name.isNotBlank())
                val config = ImmichConfig(url.trim(),key.trim(),albumId=albumId)
                config.connection
                if (save && previous?.config?.identity == config.identity) {
                    store.save(profileId,name,config)
                    checkpoint(discard=true)
                    mutable.value = mutable.value.copy(working=false,status=R.string.immich_saved,finished=true)
                    return@launch
                }
                val checked = withContext(Dispatchers.IO) { ImmichClient(config.connection,retryReadConnections=false).use { client ->
                    val version = client.api.version()
                    if (!version.supportsStructuredSearch) throw SetupFailure(R.string.immich_version_unsupported)
                    val permissions = client.api.apiKey()
                    if (listOf("asset.read","asset.view","asset.download","tag.read").any { !permissions.allows(it) })
                        throw SetupFailure(R.string.immich_permissions_missing)
                    val albums = if (permissions.allows("album.read")) client.api.albums() else emptyList()
                    if (config.albumId != null && albums.none { it.id == config.albumId }) throw SetupFailure(R.string.immich_album_unavailable)
                    client.api.search(MetadataSearch(SearchFilter(type=EnumFilter(eq="IMAGE"),
                        albumIds=config.albumId?.let { IdsFilter(any=listOf(it)) }),size=1))
                    version.toString() to albums
                } }
                if (save) { store.save(profileId,name,config); checkpoint(discard=true) }
                mutable.value = State(true,false,if (save) R.string.immich_saved else R.string.immich_connected,checked.first,checked.second,save)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val status = when (error) {
                    is SetupFailure -> error.resource
                    is IllegalArgumentException -> R.string.immich_invalid_connection
                    is retrofit2.HttpException -> if (error.code() in setOf(401,403)) R.string.immich_auth_error else R.string.immich_server_error
                    is java.io.IOException -> R.string.immich_network_error
                    else -> R.string.immich_storage_error
                }
                mutable.value = mutable.value.copy(working=false,status=status)
            }
        }
    }
    fun delete() {
        if (state.value.working) return
        viewModelScope.launch {
            try { store.delete(profileId); checkpoint(discard=true); mutable.value = mutable.value.copy(finished=true,status=R.string.source_removed) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(status=R.string.immich_storage_error) }
        }
    }
    private class SetupFailure(val resource: Int) : Exception()
}
