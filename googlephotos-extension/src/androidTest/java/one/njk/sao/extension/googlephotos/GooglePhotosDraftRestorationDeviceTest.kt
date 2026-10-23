package one.njk.sao.extension.googlephotos

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import one.njk.sao.extension.runtime.EncryptedDraftStore
import one.njk.sao.source.ExtensionProtocol
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** All sessions are synthetic; this test never opens Photos or contacts Google. */
class GooglePhotosDraftRestorationDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as Application
    private fun create(saved: SavedStateHandle,holder: ViewModelStore): GooglePhotosSettingsViewModel {
        lateinit var model: GooglePhotosSettingsViewModel
        instrumentation.runOnMainSync { model=GooglePhotosSettingsViewModel(app,saved); holder.put("settings",model) }
        return model
    }
    private suspend fun loaded(model: GooglePhotosSettingsViewModel) { withTimeout(5000) { model.state.first { it.loaded } } }
    private fun copy(saved: SavedStateHandle)=SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    @Test fun privateDraftSurvivesRecreationAndCannotResurrectAfterDiscard(): Unit=runBlocking {
        val saved=SavedStateHandle(); val firstHolder=ViewModelStore(); val restoredHolder=ViewModelStore()
        val secret="SID=synthetic-${UUID.randomUUID()}"
        try {
            val first=create(saved,firstHolder); loaded(first)
            instrumentation.runOnMainSync { first.name("Unfinished Photos"); first.pasteSession(secret) }
            withTimeout(5000) { first.state.first { !it.working && it.config.cookies==secret } }
            lateinit var snapshot: SavedStateHandle
            lateinit var pending: Deferred<Unit>
            instrumentation.runOnMainSync {
                first.account("not-yet-valid")
                pending=requireNotNull(first.checkpoint()); snapshot=copy(saved); firstHolder.clear()
            }
            val restored=create(snapshot,restoredHolder); loaded(restored); pending.await()
            assertEquals(first.profile,restored.profile)
            assertEquals("Unfinished Photos",restored.state.value.name)
            assertEquals(-1,restored.state.value.config.account)
            assertEquals(secret,restored.state.value.config.cookies); assertTrue(restored.hasChanges)
            assertFalse(snapshot.keys().joinToString { snapshot.get<Any?>(it).toString() }.contains(secret))
            File(app.noBackupFilesDir,"settings-drafts-v1/googlephotos").walkTopDown().filter(File::isFile).forEach {
                assertFalse(it.readBytes().toString(Charsets.ISO_8859_1).contains(secret))
            }
            lateinit var discarded: Deferred<Unit>
            instrumentation.runOnMainSync { restored.checkpoint(discard=true); discarded=requireNotNull(restored.checkpoint()) }
            discarded.await()
            assertNull(EncryptedDraftStore(app,"googlephotos").read(requireNotNull(snapshot["private_draft"])))
        } finally {
            instrumentation.runOnMainSync { firstHolder.clear(); restoredHolder.clear() }
            saved.get<String>("private_draft")?.let { EncryptedDraftStore(app,"googlephotos").checkpoint(it,null).await() }
        }
    }

    @Test fun restoringAnAlbumEditPreservesRenewedSessionCredentials(): Unit=runBlocking {
        val store=GooglePhotosRuntime.get(app).profiles; val id=UUID.randomUUID().toString()
        val saved=SavedStateHandle(mapOf(ExtensionProtocol.PROFILE to id))
        val firstHolder=ViewModelStore(); val restoredHolder=ViewModelStore()
        val row=store.save(id,"Existing Photos",GooglePhotosConfig(cookies="SID=synthetic-old"))
        try {
            val first=create(saved,firstHolder); loaded(first)
            lateinit var snapshot: SavedStateHandle
            lateinit var pending: Deferred<Unit>
            instrumentation.runOnMainSync {
                first.name("Renamed Photos"); pending=requireNotNull(first.checkpoint()); snapshot=copy(saved); firstHolder.clear()
            }
            pending.await(); store.refreshCookies(id,row.revision,"SID=synthetic-renewed")
            val restored=create(snapshot,restoredHolder); loaded(restored)
            assertEquals("Renamed Photos",restored.state.value.name)
            assertEquals("SID=synthetic-renewed",restored.state.value.config.cookies)
            assertTrue(restored.hasChanges)
        } finally {
            instrumentation.runOnMainSync { firstHolder.clear(); restoredHolder.clear() }
            saved.get<String>("private_draft")?.let { EncryptedDraftStore(app,"googlephotos").checkpoint(it,null).await() }
            store.delete(id,row.revision)
        }
    }
}
