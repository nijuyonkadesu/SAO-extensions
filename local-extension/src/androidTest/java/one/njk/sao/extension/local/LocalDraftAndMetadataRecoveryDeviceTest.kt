package one.njk.sao.extension.local

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.provider.DocumentsContract
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import one.njk.sao.source.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class LocalDraftAndMetadataRecoveryDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext.applicationContext as Application
    private val store=LocalStore.get(app)
    private val ids=mutableListOf<String>()
    private val files=mutableListOf<File>()
    private val models=ViewModelStore()
    private val root=DocumentsContract.buildTreeDocumentUri("${app.packageName}.fixtures","root")
    private fun model(handle: SavedStateHandle): LocalSettingsViewModel {
        lateinit var value: LocalSettingsViewModel
        instrumentation.runOnMainSync { value=LocalSettingsViewModel(app,handle); models.put(UUID.randomUUID().toString(),value) }
        return value
    }
    private fun restored(handle: SavedStateHandle): SavedStateHandle {
        lateinit var bundle: Bundle
        instrumentation.runOnMainSync { bundle=handle.savedStateProvider().saveState() }
        val parcel=Parcel.obtain()
        return try {
            parcel.writeBundle(bundle); parcel.setDataPosition(0)
            SavedStateHandle.createHandle(parcel.readBundle(javaClass.classLoader),null)
        } finally { parcel.recycle() }
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync { models.clear() }
        ids.forEach { FolderIndexer.cancel(app,it); store.delete(it) }
        files.forEach { it.delete() }
    }

    @Test fun newLocalDraftRestoresProfileCollectionsSelectionsAndOptionsWithoutSavingALibrary()=runBlocking {
        val handle=SavedStateHandle(); val original=model(handle)
        withTimeout(10_000) { original.state.first { it.loaded } }
        val filename="draft-${UUID.randomUUID()}.png"
        val file=File(app.filesDir,"test-documents/$filename").also { files+=it; it.parentFile!!.mkdirs() }
        instrumentation.context.assets.open("happy.png").use { input -> file.outputStream().use { input.copyTo(it) } }
        val media=DocumentsContract.buildDocumentUri("${app.packageName}.fixtures","root/$filename")
        instrumentation.runOnMainSync {
            original.name="Draft reactions"; original.nsfw(true); original.useContext(false)
            original.selectModel(1); original.useContext(false)
            original.folder(root)
        }
        withTimeout(10_000) { original.state.first { it.folders.any { folder -> folder.uri==root.toString() } } }
        instrumentation.runOnMainSync { original.media(listOf(media)) }
        val before=withTimeout(10_000) { original.state.first { !it.working && it.folders.size==2 } }
        val selected=before.folders.single { it.kind==CollectionKind.SELECTION }
        instrumentation.runOnMainSync { original.rename(selected,"Picked draft") }
        val saved=restored(handle)
        instrumentation.runOnMainSync { models.clear() }
        val recovered=model(saved)
        val state=withTimeout(10_000) { recovered.state.first { it.loaded } }
        assertEquals(original.profile,recovered.profile); assertEquals("Draft reactions",recovered.name)
        assertEquals(listOf(root.toString(),selected.uri),state.folders.map { it.uri })
        assertEquals("Picked draft",state.folders.last().name); assertEquals(CollectionKind.SELECTION,state.folders.last().kind)
        assertEquals(ClipCatalog.lite,state.selectedModel); assertFalse(state.useContext); assertTrue(state.nsfw)
        assertTrue(recovered.hasChanges); assertNull(state.library)
        assertTrue(store.libraries().none { it.id==recovered.profile })
        val selections=saved.get<Bundle>("draftSelections")!!
        assertEquals(listOf(media.toString()),selections.getStringArrayList(selected.uri))
        // Restoration must use independent Bundle values, not a live reference to the first ViewModel.
        instrumentation.runOnMainSync { recovered.remove(state.folders.last()) }
        assertEquals(1,recovered.state.value.folders.size)
    }

    @Test fun editingDraftRestoresRemovedAndRenamedCollectionsWhileKeepingSavedBaseline()=runBlocking {
        val id=UUID.randomUUID().toString().also(ids::add)
        val other=DocumentsContract.buildTreeDocumentUri("${app.packageName}.fixtures","root/other")
        store.save(id,"Saved title",listOf(Folder(root.toString(),"First"),Folder(other.toString(),"Other")))
        store.pause(id,true)
        val handle=SavedStateHandle(mapOf(ExtensionProtocol.PROFILE to id)); val original=model(handle)
        withTimeout(10_000) { original.state.first { it.loaded } }
        instrumentation.runOnMainSync {
            original.name="Unsaved title"
            original.rename(original.state.value.folders.first(),"Renamed draft")
            original.remove(original.state.value.folders.last()); original.nsfw(true)
        }
        val recovered=model(restored(handle))
        val state=withTimeout(10_000) { recovered.state.first { it.loaded } }
        assertEquals(id,recovered.profile); assertEquals("Unsaved title",recovered.name)
        assertEquals("Renamed draft",state.folders.single().name); assertTrue(state.nsfw); assertTrue(recovered.hasChanges)
        assertEquals("Saved title",state.library!!.name); assertEquals(2,state.library!!.folders.size)
        assertEquals("Saved title",store.library(id).name)
    }

    @Test fun metadataRefreshRemovesFalseMatchesReordersLoadedRowsAndContinuesWithoutGaps()=runBlocking {
        val id=UUID.randomUUID().toString().also(ids::add)
        val other=DocumentsContract.buildTreeDocumentUri("${app.packageName}.fixtures","root/other")
        val library=store.save(id,"Metadata refresh",listOf(Folder(root.toString(),"First"),Folder(other.toString(),"Other")))
        store.pause(id,true)
        fun add(index: Int, time: Long=index.toLong(), folder: Uri=root) {
            val name="record-$index.png"
            store.discovered(id,"fixture",folder.toString(),DocumentsContract.buildDocumentUriUsingTree(folder,"${DocumentsContract.getTreeDocumentId(folder)}/$name").toString(),name,name,"image/png",time,32,store.library(id).revision)
        }
        repeat(145) { add(it) }
        add(1000,1000,other)
        val oldest=store.search(id,MediaQuery("record-0.png",mode=SearchMode.FILENAME)).single()
        val removedMatch=store.search(id,MediaQuery("record-144.png",mode=SearchMode.FILENAME)).single()
        store.corrections(oldest.id,id,"","matches","fixture")
        store.corrections(removedMatch.id,id,"","matches","fixture")
        assertEquals(2,metadataWindow(store,id,"matches",60).items.size)
        store.corrections(removedMatch.id,id,"","different","fixture")
        assertEquals(listOf(oldest.id),metadataWindow(store,id,"matches",60).items.map { it.id })

        val loaded=metadataWindow(store,id,"",120)
        assertEquals(120,loaded.items.size); assertTrue(loaded.more)
        // Remove a collection and move a previously loaded row to the head of the current order.
        store.save(id,library.name,listOf(library.folders.first()))
        add(1,2000)
        val refreshed=metadataWindow(store,id,"",120)
        assertEquals("record-1.png",refreshed.items.first().filename)
        assertTrue(refreshed.items.none { it.root==other.toString() }); assertTrue(refreshed.more)
        val expanded=metadataWindow(store,id,"",180)
        assertEquals(145,expanded.items.size); assertFalse(expanded.more)
        assertEquals(145,expanded.items.map { it.id }.distinct().size)
        assertEquals(refreshed.items.map { it.id },expanded.items.take(120).map { it.id })
    }
}
