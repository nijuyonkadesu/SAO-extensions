package one.njk.sao.extension.runtime

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID

/** Each test owns its namespace, synthetic records and Android Keystore key. */
class EncryptedDraftStoreDeviceTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace="runtime-test-${UUID.randomUUID()}"
    private val store=EncryptedDraftStore(context,namespace)
    private val directory=File(context.noBackupFilesDir,"settings-drafts-v1/$namespace")
    private val ids=mutableSetOf<String>()
    private fun id()=UUID.randomUUID().toString().also(ids::add)
    private fun file(id: String)=File(directory,MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) })

    @After fun cleanup(): Unit=runBlocking {
        withTimeout(10_000) { ids.forEach { store.remove(it) } }
        directory.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("sao-settings-draft-$namespace-v1")
    }

    @Test fun checkpointSnapshotsOpaqueJsonAndEncryptsBothKeysAndValuesOnDisk(): Unit=runBlocking {
        val id="../synthetic-private-record-${UUID.randomUUID()}".also(ids::add)
        val secret="synthetic-secret-${UUID.randomUUID()}"
        val original=JSONObject().put("credential",secret).put("nested",JSONObject().put("name","名字 📸"))
        val pending=store.checkpoint(id,original)
        original.put("credential","changed after checkpoint").getJSONObject("nested").put("name","changed")
        pending.await()
        val restored=EncryptedDraftStore(context,namespace).read(id)!!
        assertEquals(secret,restored.getString("credential"))
        assertEquals("名字 📸",restored.getJSONObject("nested").getString("name"))
        val records=directory.listFiles()!!.toList()
        assertEquals(1,records.size)
        assertTrue(records.single().name.matches(Regex("[a-f0-9]{64}")))
        val bytes=records.single().readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains(secret)); assertFalse(bytes.contains("credential")); assertFalse(bytes.contains(id))
    }

    @Test fun finalRemovalWaitsForQueuedSnapshotsAndFreshStoreReadsQueuedWrites(): Unit=runBlocking {
        val id=id()
        val snapshots=(0 until 30).map { index -> store.checkpoint(id,JSONObject().put("version",index)) }
        val removed=store.checkpoint(id,null)
        removed.await(); snapshots.awaitAll()
        assertNull(EncryptedDraftStore(context,namespace).read(id))
        assertFalse(file(id).exists())
        store.checkpoint(id,JSONObject().put("version",31))
        assertEquals(31,EncryptedDraftStore(context,namespace).read(id)!!.getInt("version"))
    }

    @Test fun cancellingACompletionHandleDoesNotCancelTheQueuedCheckpoint(): Unit=runBlocking {
        val id=id()
        // Hold the file lock to make cancellation happen before persistence can finish.
        val field=EncryptedDraftStore::class.java.getDeclaredField("lock").apply { isAccessible=true }
        val lock=field.get(store) as Mutex
        lock.lock()
        try { store.checkpoint(id,JSONObject().put("value","synthetic queued draft")).cancel() }
        finally { lock.unlock() }
        assertEquals("synthetic queued draft",EncryptedDraftStore(context,namespace).read(id)!!.getString("value"))
    }

    @Test fun ciphertextCannotBeMovedToAnotherRecordAndTamperingIsRejected(): Unit=runBlocking {
        val first=id(); val second=id()
        store.write(first,JSONObject().put("value","first synthetic secret"))
        store.write(second,JSONObject().put("value","second synthetic secret"))
        val original=file(first).readBytes()
        file(second).writeBytes(original)
        rejects(second)
        val tampered=original.clone().apply { this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte() }
        file(first).writeBytes(tampered)
        rejects(first)
    }

    @Test fun oversizedCheckpointFailsWithoutReplacingTheExistingEncryptedRecord(): Unit=runBlocking {
        val id=id()
        store.write(id,JSONObject().put("value","existing synthetic draft"))
        try {
            store.write(id,JSONObject().put("value","x".repeat(1024*1024)))
            fail("An oversized encrypted draft was accepted")
        } catch(_: IllegalArgumentException) { }
        assertEquals("existing synthetic draft",store.read(id)!!.getString("value"))
    }

    private suspend fun rejects(id: String) {
        try { store.read(id); fail("Unauthenticated ciphertext was accepted") }
        catch(_: java.security.GeneralSecurityException) { }
    }
}
