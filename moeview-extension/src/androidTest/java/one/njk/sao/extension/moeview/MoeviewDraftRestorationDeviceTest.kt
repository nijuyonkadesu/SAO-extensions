package one.njk.sao.extension.moeview

import android.app.Activity
import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import one.njk.sao.extension.runtime.EncryptedDraftStore
import one.njk.sao.moeview.data.Board
import one.njk.sao.moeview.data.SfwMode
import one.njk.sao.source.ExtensionProtocol
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Synthetic private drafts and a pre-encrypted result avoid all public-board/WebView requests. */
class MoeviewDraftRestorationDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as Application
    private fun copy(saved: SavedStateHandle)=SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun create(saved: SavedStateHandle,holder: ViewModelStore): MoeviewSettingsViewModel {
        lateinit var model: MoeviewSettingsViewModel
        instrumentation.runOnMainSync { model=MoeviewSettingsViewModel(app,saved); holder.put("settings",model) }
        return model
    }
    private suspend fun loaded(model: MoeviewSettingsViewModel) { withTimeout(5000) { model.state.first { it.loaded } } }

    @Test fun partialCredentialsAndOpaqueVerificationRestoreWithoutChangingDraftIdentity(): Unit=runBlocking {
        val saved=SavedStateHandle(); val firstHolder=ViewModelStore(); val secondHolder=ViewModelStore()
        val cookie="cf_clearance=synthetic-cookie-${UUID.randomUUID()}"
        var request: BoardVerificationRequest?=null
        try {
            val first=create(saved,firstHolder); loaded(first)
            val profile=first.profile
            lateinit var snapshot: SavedStateHandle
            lateinit var pending: Deferred<Unit>
            instrumentation.runOnMainSync {
                first.name("Unfinished board")
                first.configure { it.copy(board=Board.DANBOORU,rating=SfwMode.NSFW,tags="blue_hair",proxy=false,login="unfinished-user",apiKey="") }
                request=requireNotNull(first.beginVerification())
                pending=requireNotNull(first.checkpoint()); snapshot=copy(saved)
            }
            pending.await()
            val expected=requireNotNull(request)
            val results=BoardVerificationDrafts(app)
            results.write(expected,BoardVerificationResult(cookie,"synthetic-browser-agent"))
            ActivityScenario.launchActivityForResult<BoardVerificationActivity>(expected.intent(app)).use { scenario ->
                val result=scenario.result
                assertEquals(Activity.RESULT_OK,result.resultCode)
                assertEquals(setOf(BoardVerificationRequest.RESULT),result.resultData.extras?.keySet())
                assertEquals(expected.id,result.resultData.getStringExtra(BoardVerificationRequest.RESULT))
            }
            instrumentation.runOnMainSync { firstHolder.clear() }
            lateinit var restored: MoeviewSettingsViewModel
            var completion: Job?=null
            instrumentation.runOnMainSync {
                restored=MoeviewSettingsViewModel(app,snapshot); secondHolder.put("settings",restored)
                completion=restored.completeVerification(expected.id)
            }
            withTimeout(5000) { completion?.join(); restored.state.first { it.loaded && it.config.clearance==cookie } }
            assertEquals(profile,restored.profile); assertEquals("Unfinished board",restored.state.value.name)
            val config=restored.state.value.config
            assertEquals(Board.DANBOORU,config.board); assertEquals(SfwMode.NSFW,config.rating)
            assertEquals("blue_hair",config.tags); assertFalse(config.proxy)
            assertEquals("unfinished-user",config.login); assertEquals("",config.apiKey)
            assertEquals(cookie,config.clearance); assertEquals("synthetic-browser-agent",config.browserAgent)
            assertTrue(restored.hasChanges); assertNull(results.read(expected))
            assertFalse(snapshot.keys().joinToString { snapshot.get<Any?>(it).toString() }.contains(cookie))
            File(app.noBackupFilesDir,"settings-drafts-v1").walkTopDown().filter(File::isFile).forEach {
                assertFalse(it.readBytes().toString(Charsets.ISO_8859_1).contains(cookie))
            }
        } finally {
            instrumentation.runOnMainSync { firstHolder.clear(); secondHolder.clear() }
            saved.get<String>("private_draft")?.let { EncryptedDraftStore(app,"moeview-settings").checkpoint(it,null).await() }
            request?.let { BoardVerificationDrafts(app).remove(it).await() }
        }
    }

    @Test fun unchangedProfileKeepsItsBaselineAndVerificationCannotCrossProfileOwners(): Unit=runBlocking {
        val store=MoeviewRuntime.get(app).profiles; val id=UUID.randomUUID().toString()
        val row=store.save(id,"Existing board",MoeviewConfig(board=Board.DANBOORU,tags="smile"))
        val saved=SavedStateHandle(mapOf(ExtensionProtocol.PROFILE to id))
        val firstHolder=ViewModelStore(); val secondHolder=ViewModelStore()
        var request: BoardVerificationRequest?=null
        try {
            val first=create(saved,firstHolder); loaded(first); assertFalse(first.hasChanges)
            lateinit var snapshot: SavedStateHandle
            lateinit var pending: Deferred<Unit>
            instrumentation.runOnMainSync { pending=requireNotNull(first.checkpoint()); snapshot=copy(saved); firstHolder.clear() }
            pending.await()
            val restored=create(snapshot,secondHolder); loaded(restored); assertFalse(restored.hasChanges)
            instrumentation.runOnMainSync { request=restored.beginVerification() }
            val expected=requireNotNull(request); val results=BoardVerificationDrafts(app)
            results.write(expected,BoardVerificationResult("cf_clearance=synthetic-private-result","synthetic-agent"))
            try { results.read(expected.copy(profile=UUID.randomUUID().toString())); fail("Another profile must not consume this result") }
            catch(_: IllegalArgumentException) { }
            instrumentation.runOnMainSync { assertNull(restored.completeVerification(UUID.randomUUID().toString())) }
            val cancelled=CompletableDeferred<Deferred<Unit>?>()
            instrumentation.runOnMainSync { cancelled.complete(restored.cancelVerification()) }
            cancelled.await()?.await(); assertNull(results.read(expected)); assertEquals("",restored.state.value.config.clearance)
        } finally {
            instrumentation.runOnMainSync { firstHolder.clear(); secondHolder.clear() }
            saved.get<String>("private_draft")?.let { EncryptedDraftStore(app,"moeview-settings").checkpoint(it,null).await() }
            request?.let { BoardVerificationDrafts(app).remove(it).await() }
            store.delete(id,row.revision)
        }
    }
}
