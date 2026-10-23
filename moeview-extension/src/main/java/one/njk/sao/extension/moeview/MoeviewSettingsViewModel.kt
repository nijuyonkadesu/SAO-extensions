package one.njk.sao.extension.moeview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import one.njk.sao.source.ExtensionProtocol
import one.njk.sao.extension.runtime.EncryptedDraftStore
import one.njk.sao.moeview.data.Board
import one.njk.sao.moeview.data.SfwMode
import org.json.JSONObject
import java.util.UUID

class MoeviewSettingsViewModel(app: Application,private val saved: SavedStateHandle) : AndroidViewModel(app) {
    data class State(val loaded: Boolean=false,val working: Boolean=false,val name: String="Moeview",val config: MoeviewConfig=MoeviewConfig(),
        val status: Int=R.string.ready,val finished: Boolean=false)
    private val runtime=MoeviewRuntime.get(app)
    private val drafts=EncryptedDraftStore(app,"moeview-settings")
    private val verificationDrafts=BoardVerificationDrafts(app)
    private val mutable=MutableStateFlow(State()); val state=mutable.asStateFlow()
    val editing: String?=saved[ExtensionProtocol.PROFILE]
    val profile=editing ?: saved.get<String>("draft") ?: UUID.randomUUID().toString().also { saved["draft"]=it }
    private val draftId=saved.get<String>("private_draft") ?: UUID.randomUUID().toString().also { saved["private_draft"]=it }
    private var previous: MoeviewProfile?=null
    private data class Draft(val name: String,val config: MoeviewConfig) {
        fun json()=JSONObject().put("name",name).put("config",config.json())
    }
    private var initial=Draft("Moeview",MoeviewConfig())
    private var draftClosed=false
    val hasChanges get()=state.value.loaded && Draft(state.value.name,state.value.config)!=initial
    private val loading=viewModelScope.launch {
        try {
            previous=editing?.let { runtime.profiles.get(it) }
            initial=Draft(previous?.name ?: "Moeview",previous?.config ?: MoeviewConfig())
            val restored=drafts.read(draftId)?.let {
                require(it.getInt("version")==1 && it.getString("profile")==profile)
                initial=readDraft(it.getJSONObject("initial"))
                readDraft(it.getJSONObject("draft"))
            } ?: initial
            mutable.value=State(loaded=true,name=restored.name,config=restored.config)
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) {
            mutable.value=State(loaded=true,name=initial.name,config=initial.config,status=R.string.storage_error)
        }
        try { pendingVerification()?.let { consumeVerification(it) } }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { mutable.value=state.value.copy(status=R.string.storage_error) }
    }
    fun name(value: String) { if(state.value.loaded && !state.value.working) mutable.value=state.value.copy(name=value) }
    fun configure(change: (MoeviewConfig)->MoeviewConfig) {
        if(state.value.loaded && !state.value.working) mutable.value=state.value.copy(config=change(state.value.config),status=R.string.ready)
    }
    internal fun beginVerification(): BoardVerificationRequest? {
        if(!state.value.loaded || state.value.working) return null
        cancelVerification()
        return BoardVerificationRequest(UUID.randomUUID().toString(),profile,state.value.config.board).also {
            saved[BoardVerificationRequest.REQUEST]=it.id
            saved["verification_board"]=it.board.name
        }
    }
    internal fun completeVerification(token: String?): Job? {
        val request=pendingVerification()?.takeIf { it.id==token } ?: return null
        return viewModelScope.launch {
            loading.join()
            try { consumeVerification(request) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { mutable.value=state.value.copy(status=R.string.storage_error) }
        }
    }
    internal fun cancelVerification(): Deferred<Unit>? {
        val request=pendingVerification() ?: return null
        saved.remove<String>(BoardVerificationRequest.REQUEST); saved.remove<String>("verification_board")
        return verificationDrafts.remove(request)
    }
    private fun pendingVerification(): BoardVerificationRequest?=runCatching {
        BoardVerificationRequest(requireNotNull(saved.get<String>(BoardVerificationRequest.REQUEST)),profile,
            Board.valueOf(requireNotNull(saved.get<String>("verification_board"))))
    }.getOrNull()
    private suspend fun consumeVerification(request: BoardVerificationRequest) {
        val result=verificationDrafts.read(request) ?: return
        if(pendingVerification()!=request) return
        if(request.board==state.value.config.board) {
            configure { it.copy(clearance=result.cookies,browserAgent=result.agent) }
            // Commit the form before consuming a result whose platform callback may run only once.
            checkpoint()?.await()
        }
        verificationDrafts.remove(request).await()
        if(pendingVerification()==request) {
            saved.remove<String>(BoardVerificationRequest.REQUEST); saved.remove<String>("verification_board")
        }
    }
    /** Only lifecycle boundaries persist the form; credentials never enter SavedStateHandle. */
    fun checkpoint(discard: Boolean=false): Deferred<Unit>? {
        if(!discard && !draftClosed && !state.value.loaded) return null
        val value=if(discard || draftClosed || state.value.finished) null else JSONObject().put("version",1)
            .put("profile",profile).put("initial",initial.json()).put("draft",Draft(state.value.name,state.value.config).json())
        if(discard) { draftClosed=true; cancelVerification() }
        return drafts.checkpoint(draftId,value).also { pending -> viewModelScope.launch {
            try { pending.await() }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { mutable.value=state.value.copy(status=R.string.storage_error) }
        } }
    }
    private fun readDraft(value: JSONObject): Draft {
        val config=value.getJSONObject("config")
        // A draft may contain half-entered credentials or an otherwise invalid configuration.
        // Validation belongs to Check/Save, rather than discarding those edits on recreation.
        return Draft(value.getString("name"),MoeviewConfig(Board.valueOf(config.getString("board")),
            SfwMode.valueOf(config.getString("rating")),config.getString("tags"),config.getBoolean("proxy"),
            config.getString("login"),config.getString("key"),config.getString("clearance"),config.getString("agent")))
    }
    fun save()=work {
        val state=state.value; require(state.name.isNotBlank()); state.config.validate()
        val row=runtime.profiles.save(profile,state.name,state.config)
        if(previous?.revision!=row.revision) runtime.invalidate(profile)
        checkpoint(discard=true)
        mutable.value=state.copy(working=false,status=R.string.saved,finished=true)
    }
    fun check()=work {
        val config=state.value.config; config.validate()
        withContext(Dispatchers.IO) {
            val api=runtime.factory(config)
            try { api.search(config.board,config.tags,1,1,config.rating) } finally { runtime.close(api) }
        }
        mutable.value=state.value.copy(working=false,status=R.string.connected)
    }
    fun delete()=work {
        val row=runtime.profiles.get(profile)
        runtime.profiles.delete(profile,row.revision); runtime.invalidate(profile)
        checkpoint(discard=true)
        mutable.value=state.value.copy(working=false,finished=true)
    }
    private fun work(block: suspend ()->Unit) {
        if(!state.value.loaded || state.value.working) return
        mutable.value=state.value.copy(working=true,status=R.string.working)
        viewModelScope.launch {
            try { block() }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) {
                val failure=MoeviewRuntime.failure(error)
                val cause=if(error is one.njk.sao.moeview.data.net.NoTransportException) error.cause else error
                val message=when {
                    cause is one.njk.sao.moeview.data.net.HttpStatusException && cause.challenged -> R.string.verification_needed
                    error is IllegalArgumentException -> R.string.invalid_configuration
                    failure.code in setOf("HTTP_401","HTTP_403") -> R.string.access_denied
                    failure.code=="HTTP_429" -> R.string.rate_limited
                    failure.code in setOf("NETWORK","TIMEOUT","TLS") -> R.string.network_error
                    failure.code=="INVALID_RESPONSE" -> R.string.invalid_response
                    else -> R.string.storage_error
                }
                mutable.value=state.value.copy(working=false,status=message)
            }
        }
    }
}
