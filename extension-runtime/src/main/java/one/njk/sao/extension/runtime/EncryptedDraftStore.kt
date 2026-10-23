package one.njk.sao.extension.runtime

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Private form drafts, independent of saved profiles and Android's plain saved-state bundles. */
class EncryptedDraftStore(context: Context, private val namespace: String) {
    init { require(namespace.matches(Regex("[a-z][a-z0-9-]{0,63}"))) }
    private val directory=File(context.noBackupFilesDir,"settings-drafts-v1/$namespace")
    private val alias="sao-settings-draft-$namespace-v1"
    private val lock=locks.getOrPut(directory.absolutePath) { Mutex() }

    suspend fun read(id: String): JSONObject? {
        val pending=synchronized(checkpoints) { checkpoints[recordKey(id)] }
        pending?.await()
        return withContext(Dispatchers.IO) { lock.withLock { readLocked(id)?.let(::JSONObject) } }
    }

    suspend fun write(id: String, value: JSONObject) { checkpoint(id,value).await() }
    suspend fun remove(id: String) { checkpoint(id,null).await() }
    private suspend fun removeFile(id: String) = withContext(Dispatchers.IO) { lock.withLock { file(id).delete() } }

    /**
     * Snapshot on a lifecycle boundary, without retaining a ViewModel or Activity. Newer queued
     * snapshots supersede older ones; a final removal cannot be overtaken by an earlier write.
     * The returned deferred retains failures for the UI to report instead of crashing a worker.
     * Cancelling that deferred does not cancel persistence or break the queued write ordering.
     */
    fun checkpoint(id: String, value: JSONObject?): Deferred<Unit> {
        val key=recordKey(id)
        val text=value?.toString()
        return synchronized(checkpoints) {
            val previous=checkpoints[key]
            val result=CompletableDeferred<Unit>()
            lateinit var current: Deferred<Unit>
            current=scope.async(start=CoroutineStart.LAZY) {
                previous?.join()
                if(synchronized(checkpoints) { checkpoints[key]===current }) {
                    if(text==null) removeFile(id) else writeText(id,text)
                }
            }
            checkpoints[key]=current
            current.invokeOnCompletion { error ->
                synchronized(checkpoints) {
                    if(checkpoints[key]===current) checkpoints.remove(key)
                }
                if(error==null) result.complete(Unit) else result.completeExceptionally(error)
            }
            current.start()
            result
        }
    }

    private suspend fun writeText(id: String, text: String) = withContext(Dispatchers.IO) { lock.withLock {
        val plain=text.toByteArray(Charsets.UTF_8)
        require(plain.size<=MAX_BYTES)
        val existing=try { readLocked(id) } catch(_: Exception) { null }
        if(existing==text) return@withLock
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE,key()); updateAAD(associatedData(id))
        }
        val bytes=cipher.iv+cipher.doFinal(plain)
        directory.mkdirs()
        val file=file(id)
        val output=file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch(error: Exception) { file.failWrite(output); throw error }
    } }

    private fun readLocked(id: String): String? {
        val file=file(id)
        val bytes=try { file.openRead().use { input ->
            val output=ByteArrayOutputStream()
            val buffer=ByteArray(16*1024)
            while(true) {
                val count=input.read(buffer)
                if(count<0) break
                require(output.size()+count<=MAX_BYTES+28)
                output.write(buffer,0,count)
            }
            output.toByteArray()
        } } catch(_: FileNotFoundException) { return null }
        require(bytes.size>=29)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            updateAAD(associatedData(id))
        }
        return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
    }

    private fun file(id: String): AtomicFile {
        require(id.isNotBlank() && id.length<=256)
        val name=MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return AtomicFile(File(directory,name))
    }
    private fun recordKey(id: String): String {
        require(id.isNotBlank() && id.length<=256)
        return "${directory.absolutePath}\u0000$id"
    }
    private fun associatedData(id: String)="$namespace\u0000$id".toByteArray(Charsets.UTF_8)
    private fun key(): SecretKey = synchronized(keyLocks.getOrPut(alias) { Any() }) {
        val keys=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias,null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    companion object {
        private const val MAX_BYTES=1024*1024
        private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        private val locks=ConcurrentHashMap<String,Mutex>()
        private val keyLocks=ConcurrentHashMap<String,Any>()
        private val checkpoints=mutableMapOf<String,Deferred<Unit>>()
    }
}
