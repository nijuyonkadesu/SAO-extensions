package one.njk.sao.extension.local

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.njk.sao.source.ExtensionProtocol
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class LocalSettingsViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    data class State(val loaded: Boolean=false,val working: Boolean=false,val folders: List<Folder> = emptyList(),
        val library: Library?=null,val index: IndexStatus?=null,val folderIndexes: Map<String,IndexStatus> = emptyMap(),
        internal val estimates: IndexEstimates=IndexEstimates(),val indexWork: IndexWork=IndexWork.IDLE,val model: ClipModels.Status=ClipModels.Status(),val useContext: Boolean=false,
        val selectedModel: ClipModel=ClipCatalog.immich,val nsfw: Boolean=false,val importMethod: ImportMethod=ImportMethod.FOLDER,val error: Int?=null,val finished: Boolean=false)
    private data class Progress(val overview: IndexOverview?,val work: IndexWork,val model: ClipModel,val modelStatus: ClipModels.Status)
    private val store=LocalStore.get(app)
    private val mutable=MutableStateFlow(State())
    private val selectedModel=MutableStateFlow(ClipCatalog.immich)
    val state=mutable.asStateFlow()
    val editing: String?=saved[ExtensionProtocol.PROFILE]
    val profile=editing ?: saved.get<String>("draftProfile") ?: UUID.randomUUID().toString().also { saved["draftProfile"]=it }
    var importTarget: String?
        get()=saved["importTarget"]
        set(value) { saved["importTarget"]=value }
    private val importPreferences=Mutex()
    private val selections=mutableMapOf<String,List<String>>()
    internal val importsUnchanged get()=state.value.library?.let { state.value.folders.map { folder -> Triple(folder.uri,folder.name,folder.kind) }==it.folders.map { folder -> Triple(folder.uri,folder.name,folder.kind) } && selections.isEmpty() }==true
    private val strings=one.njk.sao.source.SourceLocales.context(app,saved.get<String>(one.njk.sao.source.SourceLocales.EXTRA_LANGUAGE_TAGS))
    var name=strings.getString(R.string.default_name)
        set(value) { field=value; if(state.value.loaded) saved[DRAFT_NAME]=value }
    private data class Draft(val name: String,val folders: List<Triple<String,String,CollectionKind>>,val context: Boolean,val model: String,val nsfw: Boolean)
    private fun draft()=state.value.let { Draft(name,it.folders.map { folder -> Triple(folder.uri,folder.name,folder.kind) },it.useContext,it.selectedModel.id,it.nsfw) }
    private var initial: Draft?=null
    val hasChanges get()=state.value.loaded && (initial!=draft() || selections.isNotEmpty())
    init { viewModelScope.launch {
        try {
            val overview=withContext(Dispatchers.IO) {
                try { editing?.let(store::overview) }
                catch(error: one.njk.sao.source.ExtensionFailure) { if(error.code=="PROFILE_MISSING") null else throw error }
            }
            val method=withContext(Dispatchers.IO) {
                runCatching { ImportMethod.valueOf(app.getSharedPreferences("imports",0).getString("method",ImportMethod.FOLDER.name)!!) }.getOrDefault(ImportMethod.FOLDER)
            }
            val library=overview?.library
            val selected=ClipCatalog.get(library?.contextModel ?: ClipModels.MODEL); selectedModel.value=selected
            val ready=withContext(Dispatchers.IO) { ClipModels.ready(app,selected) }
            if(library!=null) name=library.name
            mutable.value=State(folders=library?.folders.orEmpty(),library=library,
                index=overview?.total,folderIndexes=overview?.folders.orEmpty(),model=ClipModels.Status(ready=ready),useContext=library?.contextSearch ?: ready,selectedModel=selected,importMethod=method,nsfw=library?.nsfw ?: false)
            initial=draft()
            restoreDraft()
            mutable.update { it.copy(loaded=true) }
            mutable.subscriptionCount.map { it>0 }.distinctUntilChanged().flatMapLatest { subscribed ->
                if(!subscribed) emptyFlow() else {
                    // Timing events reuse the latest durable snapshot instead of querying the
                    // whole library again. Database refreshes retain their existing triggers.
                    combine(store.changes,FolderIndexer.observe(app,profile),selectedModel.flatMapLatest { selected ->
                        ClipModels.observe(app,selected).map { selected to it }
                    }) { _,work,pair -> work to pair }.mapLatest { (work,pair) ->
                        val updated=if(mutable.value.library!=null) withContext(Dispatchers.IO) { store.overview(profile) } else null
                        Progress(updated,work,pair.first,pair.second)
                    }.combine(IndexTimings.current.map { it?.takeIf { timing -> timing.profile==profile } }.distinctUntilChanged()) { progress,timing -> progress to timing }
                }
            }.collect { (progress,timing) ->
                val updated=progress.overview; val model=progress.modelStatus
                val wasEnabled=state.value.useContext
                mutable.update { before -> before.copy(library=updated?.library,index=updated?.total,folderIndexes=updated?.folders.orEmpty(),
                    estimates=indexEstimates(updated,progress.work,timing),indexWork=progress.work,
                    model=model,selectedModel=progress.model,useContext=before.useContext || !before.model.ready && model.ready && saved.get<Boolean>(DRAFT_CONTEXT_EXPLICIT)!=false) }
                if(wasEnabled!=state.value.useContext) saved[DRAFT_CONTEXT]=state.value.useContext
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { initial=draft(); mutable.update { it.copy(loaded=true,error=R.string.storage_error) } }
    } }
    fun folder(uri: Uri) { viewModelScope.launch {
        try {
            require(DocumentsContract.isTreeUri(uri))
            val title=withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri)),
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null }
                    ?: throw java.io.IOException("Folder unavailable")
            }
            mutable.update { before -> before.copy(folders=if(before.folders.any { it.uri==uri.toString() }) before.folders else before.folders+Folder(uri.toString(),title),error=null) }
            saveCollections()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(error=R.string.folder_error) } }
    } }
    fun importMethod(index: Int) {
        val method=ImportMethod.entries[index]
        mutable.update { it.copy(importMethod=method) }
        viewModelScope.launch(Dispatchers.IO) { importPreferences.withLock {
            getApplication<Application>().getSharedPreferences("imports",0).edit().putString("method",state.value.importMethod.name).apply()
        } }
    }
    fun media(uris: List<Uri>) {
        val target=importTarget; importTarget=null
        if(uris.isEmpty()) return
        viewModelScope.launch {
            mutable.update { it.copy(working=true,error=null) }
            try {
                val accepted=withContext(Dispatchers.IO) {
                    uris.distinct().filter { uri ->
                        require(uri.scheme=="content")
                        try { MediaImport.details(getApplication(),LocalStore.SelectedMedia(uri.toString(),System.currentTimeMillis()),android.os.CancellationSignal()); true }
                        catch(_: UnsupportedOperationException) { false }
                    }.map(Uri::toString)
                }
                require(accepted.isNotEmpty())
                val root=target ?: "selection:${UUID.randomUUID()}"
                selections[root]=(selections[root].orEmpty()+accepted).distinct()
                mutable.update { before -> before.copy(folders=if(before.folders.any { it.uri==root }) before.folders else before.folders+Folder(root,strings.getString(R.string.selected_media),kind=CollectionKind.SELECTION)) }
                saveCollections()
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { mutable.update { it.copy(error=R.string.media_import_error) } }
            finally { mutable.update { it.copy(working=false) } }
        }
    }
    fun pickerUnavailable() { mutable.update { it.copy(error=R.string.picker_unavailable) } }
    fun rename(folder: Folder, name: String) { if(name.isNotBlank()) {
        mutable.update { before -> before.copy(folders=before.folders.map { if(it.uri==folder.uri) it.copy(name=name.trim()) else it }) }; saveCollections()
    } }
    fun remove(folder: Folder) { selections.remove(folder.uri); mutable.update { before -> before.copy(folders=before.folders.filterNot { it.uri==folder.uri }) }; saveCollections() }
    fun nsfw(enabled: Boolean) { mutable.update { it.copy(nsfw=enabled) }; saved[DRAFT_NSFW]=enabled }
    fun useContext(enabled: Boolean) {
        val choice=enabled && state.value.model.ready
        mutable.update { it.copy(useContext=choice) }; saved[DRAFT_CONTEXT]=choice; saved[DRAFT_CONTEXT_EXPLICIT]=choice
    }
    fun selectModel(index: Int) {
        if(state.value.working) return
        val selected=ClipCatalog.all[index]
        if(selected==state.value.selectedModel) return
        mutable.update { it.copy(selectedModel=selected,model=ClipModels.Status(),useContext=false) }
        saved[DRAFT_MODEL]=selected.id; saved[DRAFT_CONTEXT]=false; saved.remove<Boolean>(DRAFT_CONTEXT_EXPLICIT)
        selectedModel.value=selected
    }
    fun modelFolder(uri: Uri) {
        val selected=state.value.selectedModel
        action { ClipModels.select(getApplication(),selected,uri); releaseUnusedGrants(getApplication(),store) }
    }
    fun downloadModel() { val selected=state.value.selectedModel; action { ClipModels.download(getApplication(),selected) } }
    fun pauseDownload() { val selected=state.value.selectedModel; action { ClipModels.cancel(getApplication(),selected) } }
    private fun action(block: suspend () -> Unit) {
        if(!state.value.loaded || state.value.working) return
        mutable.update { it.copy(working=true,error=null) }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error=R.string.storage_error) } }
            finally { mutable.update { it.copy(working=false) } }
        }
    }
    fun save() {
        if(name.isBlank() || state.value.folders.isEmpty()) { mutable.update { it.copy(error=R.string.configuration_error) }; return }
        val additions=selections.mapValues { it.value.toList() }
        val folders=state.value.folders.toList(); val title=name; val nsfw=state.value.nsfw; val useContext=state.value.useContext; val selected=state.value.selectedModel
        action {
            val app=getApplication<Application>(); val resolver=app.contentResolver
            try {
                folders.filter { it.kind==CollectionKind.TREE }.forEach {
                    try { resolver.takePersistableUriPermission(Uri.parse(it.uri),Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                    catch(_: SecurityException) { resolver.takePersistableUriPermission(Uri.parse(it.uri),Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
                additions.values.flatten().distinct().forEach { resolver.takePersistableUriPermission(Uri.parse(it),Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val prior=state.value.library
                val library=store.save(profile,title,folders,contextSearch=useContext && ClipModels.ready(app,selected),contextModel=selected.id,selections=additions,nsfw=nsfw)
                if(prior!=null && prior.revision!=library.revision) {
                    app.revokeUriPermission(mediaScope(app,prior),Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    app.revokeUriPermission(store.changesUri(prior),Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                releaseUnusedGrants(app,store)
                val foldersChanged=prior?.folders?.associate { it.uri to it.name }!=library.folders.associate { it.uri to it.name }
                FolderIndexer.schedule(app,library,force=foldersChanged || prior?.revision!=library.revision,
                    reset=prior?.folders?.map { it.uri }!=library.folders.map { it.uri })
                FolderIndexer.configureChecks(app)
                mutable.update { it.copy(library=library,finished=true) }
            } catch (error: Exception) { releaseUnusedGrants(app,store); throw error }
        }
    }
    fun rescan()=action {
        val app=getApplication<Application>(); val library=store.library(profile)
        store.pause(profile,false); store.retryFailures(profile)
        FolderIndexer.schedule(app,library.copy(paused=false),force=true)
    }
    fun pause()=action {
        val app=getApplication<Application>(); val library=store.library(profile)
        store.pause(profile,!library.paused)
        if(library.paused) FolderIndexer.schedule(app,library.copy(paused=false),force=true,reset=false) else FolderIndexer.cancel(app,profile)
        FolderIndexer.configureChecks(app)
    }
    fun pauseFolder(folder: Folder)=action {
        val app=getApplication<Application>()
        FolderIndexer.change(app,profile) {
            val paused=store.library(profile).folders.single { it.uri==folder.uri }.paused
            store.pauseFolder(profile,folder.uri,!paused)
        }
        FolderIndexer.schedule(app,store.library(profile),force=true,reset=false)
        FolderIndexer.configureChecks(app)
    }
    fun restartFolder(folder: Folder)=action {
        val app=getApplication<Application>()
        FolderIndexer.change(app,profile) { store.restartFolder(profile,folder.uri) }
        FolderIndexer.schedule(app,store.library(profile),force=true,reset=false)
    }
    fun delete()=action {
        val app=getApplication<Application>(); val library=store.library(profile)
        LocalImports(app,store,library).abandon()
        FolderIndexer.cancel(app,profile); store.delete(profile); releaseUnusedGrants(app,store)
        FolderIndexer.configureChecks(app)
        app.revokeUriPermission(mediaScope(app,library),Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.revokeUriPermission(store.changesUri(library),Intent.FLAG_GRANT_READ_URI_PERMISSION)
        mutable.update { it.copy(finished=true) }
    }
    /** UI drafts contain only local collection references/options; no credentials or media bytes. */
    private fun saveCollections() {
        saved[DRAFT_FOLDERS]=ArrayList(state.value.folders.map { folder -> Bundle().apply {
            putString("uri",folder.uri); putString("name",folder.name); putString("kind",folder.kind.name)
        } })
        saved[DRAFT_SELECTIONS]=Bundle().apply { selections.forEach { (root,uris) -> putStringArrayList(root,ArrayList(uris)) } }
    }
    private fun restoreDraft() {
        val restoredModel=saved.get<String>(DRAFT_MODEL)?.let(ClipCatalog::get) ?: state.value.selectedModel
        val folders=saved.get<ArrayList<Bundle>>(DRAFT_FOLDERS)?.map { row ->
            Folder(requireNotNull(row.getString("uri")),requireNotNull(row.getString("name")),kind=CollectionKind.valueOf(requireNotNull(row.getString("kind"))))
        } ?: state.value.folders
        saved.get<Bundle>(DRAFT_SELECTIONS)?.let { bundle ->
            bundle.keySet().forEach { root -> selections[root]=bundle.getStringArrayList(root).orEmpty().toList() }
        }
        name=saved[DRAFT_NAME] ?: name
        mutable.update { it.copy(folders=folders,selectedModel=restoredModel,
            model=if(restoredModel==it.selectedModel) it.model else ClipModels.Status(),
            useContext=saved[DRAFT_CONTEXT] ?: it.useContext,nsfw=saved[DRAFT_NSFW] ?: it.nsfw) }
        selectedModel.value=restoredModel
    }
    private companion object {
        const val DRAFT_NAME="draftName"
        const val DRAFT_FOLDERS="draftFolders"
        const val DRAFT_SELECTIONS="draftSelections"
        const val DRAFT_MODEL="draftModel"
        const val DRAFT_CONTEXT="draftContext"
        const val DRAFT_CONTEXT_EXPLICIT="draftContextExplicit"
        const val DRAFT_NSFW="draftNsfw"
    }
}
