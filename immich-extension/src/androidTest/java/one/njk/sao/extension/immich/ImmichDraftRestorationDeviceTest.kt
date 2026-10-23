package one.njk.sao.extension.immich

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import one.njk.sao.extension.runtime.EncryptedDraftStore
import one.njk.sao.immichprovider.ImmichConfig
import one.njk.sao.source.ExtensionProtocol
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** New ViewModels and copied saved-state emulate process recreation; no network is used. */
class ImmichDraftRestorationDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as Application
    private fun copy(saved: SavedStateHandle)=SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun create(saved: SavedStateHandle,holder: ViewModelStore): ImmichSettingsViewModel {
        lateinit var model: ImmichSettingsViewModel
        instrumentation.runOnMainSync { model=ImmichSettingsViewModel(app,saved); holder.put("settings",model) }
        return model
    }
    private suspend fun loaded(model: ImmichSettingsViewModel) { withTimeout(5000) { model.state.first { it.loaded } } }
    private fun assertOpaque(secret: String) {
        val records=File(app.noBackupFilesDir,"settings-drafts-v1/immich-settings").listFiles().orEmpty()
        assertTrue(records.isNotEmpty())
        records.filter(File::isFile).forEach { assertFalse(it.readBytes().toString(Charsets.ISO_8859_1).contains(secret)) }
    }

    @Test fun unsavedConnectionAndAlbumRestoreWithStableProfileIdentityAndPrivateKey(): Unit=runBlocking {
        val saved=SavedStateHandle(); val firstHolder=ViewModelStore(); val secondHolder=ViewModelStore()
        val secret="synthetic-immich-draft-${UUID.randomUUID()}"
        try {
            val first=create(saved,firstHolder); loaded(first)
            val profile=first.profileId
            lateinit var snapshot: SavedStateHandle
            lateinit var pending: Deferred<Unit>
            instrumentation.runOnMainSync {
                first.name="Unfinished library"; first.url="https://draft.example.test"; first.key=secret; first.albumId="selected-album"
                assertTrue(first.hasChanges)
                pending=requireNotNull(first.checkpoint())
                snapshot=copy(saved)
                firstHolder.clear() // The queued checkpoint must outlive this ViewModel.
            }
            val restored=create(snapshot,secondHolder); loaded(restored); pending.await()
            assertEquals(profile,restored.profileId)
            assertEquals("Unfinished library",restored.name); assertEquals("https://draft.example.test",restored.url)
            assertEquals(secret,restored.key); assertEquals("selected-album",restored.albumId)
            assertTrue(restored.hasChanges)
            assertFalse(snapshot.keys().joinToString { snapshot.get<Any?>(it).toString() }.contains(secret))
            assertOpaque(secret)
        } finally {
            instrumentation.runOnMainSync { firstHolder.clear(); secondHolder.clear() }
            saved.get<String>("private_draft")?.let { EncryptedDraftStore(app,"immich-settings").checkpoint(it,null).await() }
        }
    }

    @Test fun unchangedSavedConnectionKeepsTheOriginalUnsavedChangeBaseline(): Unit=runBlocking {
        val store=ConnectionStore(app); val id=UUID.randomUUID().toString()
        val saved=SavedStateHandle(mapOf(ExtensionProtocol.PROFILE to id))
        val firstHolder=ViewModelStore(); val secondHolder=ViewModelStore()
        store.save(id,"Existing library",ImmichConfig("https://saved.example.test","synthetic-existing-key",albumId="existing-album"))
        try {
            val first=create(saved,firstHolder); loaded(first); assertFalse(first.hasChanges)
            lateinit var snapshot: SavedStateHandle
            lateinit var pending: Deferred<Unit>
            instrumentation.runOnMainSync { pending=requireNotNull(first.checkpoint()); snapshot=copy(saved); firstHolder.clear() }
            pending.await()
            val restored=create(snapshot,secondHolder); loaded(restored)
            assertEquals(id,restored.profileId); assertFalse(restored.hasChanges)
            instrumentation.runOnMainSync { restored.key="partially-edited-key"; assertTrue(restored.hasChanges) }
        } finally {
            instrumentation.runOnMainSync { firstHolder.clear(); secondHolder.clear() }
            saved.get<String>("private_draft")?.let { EncryptedDraftStore(app,"immich-settings").checkpoint(it,null).await() }
            store.delete(id)
        }
    }

    @Test fun queuedDraftWritesCannotResurrectADiscardedRecord(): Unit=runBlocking {
        val drafts=EncryptedDraftStore(app,"immich-settings"); val id=UUID.randomUUID().toString()
        try {
            val pending=(0 until 20).map { drafts.checkpoint(id,JSONObject().put("secret","private-$it")) }
            val discarded=drafts.checkpoint(id,null)
            pending.awaitAll(); discarded.await()
            assertNull(EncryptedDraftStore(app,"immich-settings").read(id))
            drafts.checkpoint(id,JSONObject().put("secret","new-private-draft"))
            assertEquals("new-private-draft",EncryptedDraftStore(app,"immich-settings").read(id)?.getString("secret"))
        } finally { drafts.checkpoint(id,null).await() }
    }
}
