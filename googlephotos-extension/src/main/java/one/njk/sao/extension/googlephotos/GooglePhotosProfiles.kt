package one.njk.sao.extension.googlephotos

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import one.njk.sao.source.*
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class GooglePhotosConfig(val cookies: String = "", val account: Int = 0,
    val album: String = "", val albumName: String = "", val scopedCookies: List<Cookie> = emptyList(),
    val nativeAccount: String = "", val nativeSession: String = "") {
    fun validate() {
        require(account in 0..99 && album.length <= 512 && albumName.length <= 256)
        require(album.isEmpty() || GooglePhotosProtocol.validKey(album))
        GooglePhotosCookies.validate(cookies)
        require(scopedCookies.size <= 200)
        require(nativeAccount.isEmpty() || (nativeAccount.length in 3..320 && '@' in nativeAccount && nativeAccount.none(Char::isISOControl)))
        require(nativeSession.isEmpty() || runCatching { UUID.fromString(nativeSession) }.isSuccess)
        require(nativeAccount.isEmpty() == nativeSession.isEmpty())
    }
    fun json(): JSONObject = JSONObject().put("cookies", cookies).put("account", account)
        .put("album", album).put("albumName", albumName).put("nativeAccount", nativeAccount).put("nativeSession", nativeSession)
        .put("scopedCookies", JSONArray(scopedCookies.map {
            JSONObject().put("origin", "https://${it.domain}/").put("cookie", it.toString())
        }))
    companion object {
        fun read(json: JSONObject, validate: Boolean = true) = GooglePhotosConfig(json.getString("cookies"), json.getInt("account"),
            json.optString("album"), json.optString("albumName"), json.optJSONArray("scopedCookies")?.let { rows ->
                require(rows.length() <= 200)
                List(rows.length()) { i -> rows.getJSONObject(i).let {
                    val origin = it.getString("origin").toHttpUrl()
                    require(origin.isHttps && (origin.host == "google.com" || origin.host.endsWith(".google.com")))
                    requireNotNull(Cookie.parse(origin, it.getString("cookie")))
                } }
            }.orEmpty(), json.optString("nativeAccount"), json.optString("nativeSession")).also { if(validate) it.validate() }
    }
}

data class GooglePhotosProfile(val id: String, val name: String, val revision: String, val config: GooglePhotosConfig) {
    fun publicProfile() = ExtensionProfile(id, name, revision,
        config.albumName.ifBlank { "Google Photos · Account ${config.account + 1}" })
}

/** Private profiles and authenticated opaque references survive process death, with no result/media cache. */
class GooglePhotosProfiles(context: Context) {
    private val app=context.applicationContext
    private val file=AtomicFile(File(app.noBackupFilesDir,"googlephotos-profiles-v1"))
    private val lock=Mutex()
    private val key: SecretKey by lazy {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS,null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    suspend fun list(): List<GooglePhotosProfile> = withContext(Dispatchers.IO) { lock.withLock { read() } }
    suspend fun get(id: String)=list().firstOrNull { it.id==id } ?: throw ExtensionFailure("PROFILE_MISSING")
    suspend fun save(id: String,name: String,config: GooglePhotosConfig,preserveCurrentSession: Boolean = false): GooglePhotosProfile = withContext(Dispatchers.IO) { lock.withLock {
        UUID.fromString(id); require(name.isNotBlank() && name.length<=100); config.validate()
        val rows=read(); val prior=rows.firstOrNull { it.id==id }
        val effective=if(preserveCurrentSession && prior!=null) {
            if(prior.config.nativeAccount!=config.nativeAccount || prior.config.account!=config.account) throw ExtensionFailure("PROFILE_CHANGED")
            config.copy(cookies=prior.config.cookies,scopedCookies=prior.config.scopedCookies,nativeSession=prior.config.nativeSession)
        } else config
        val saved=GooglePhotosProfile(id,name.trim(),prior?.revision?.takeIf { prior.config==effective } ?: UUID.randomUUID().toString(),effective)
        write(rows.filterNot { it.id==id }+saved)
        if(prior!=null && prior.revision!=saved.revision) revoke(prior)
        saved
    } }
    suspend fun delete(id: String,revision: String) = withContext(Dispatchers.IO) { lock.withLock {
        val rows=read(); val row=rows.firstOrNull { it.id==id } ?: throw ExtensionFailure("PROFILE_MISSING")
        if(row.revision!=revision) throw ExtensionFailure("PROFILE_CHANGED")
        write(rows.filterNot { it.id==id }); revoke(row)
    } }
    /** Cookie rotation is authentication maintenance, not a new source identity. */
    suspend fun refreshCookies(id: String, revision: String, cookies: String, scopedCookies: List<Cookie>? = null,
        expectedSession: String? = null): Unit = withContext(Dispatchers.IO) { lock.withLock {
        GooglePhotosCookies.validate(cookies)
        val rows = read()
        val row = rows.firstOrNull { it.id == id && it.revision == revision } ?: throw ExtensionFailure("PROFILE_CHANGED")
        // A late response from the previous login must not overwrite newly renewed credentials.
        if (expectedSession != null && row.config.nativeSession != expectedSession) return@withLock
        val config = row.config.copy(cookies = cookies, scopedCookies = scopedCookies ?: row.config.scopedCookies)
        if (row.config != config) write(rows.map { if (it.id == id) it.copy(config = config) else it })
    } }
    suspend fun renewSession(expected: GooglePhotosProfile, fresh: GooglePhotosConfig): GooglePhotosProfile = withContext(Dispatchers.IO) { lock.withLock {
        fresh.validate()
        val rows=read()
        val row=rows.firstOrNull { it.id==expected.id && it.revision==expected.revision } ?: throw ExtensionFailure("PROFILE_CHANGED")
        if(row.config.nativeSession!=expected.config.nativeSession) return@withLock row
        if(fresh.nativeAccount.isEmpty() || !fresh.nativeAccount.equals(row.config.nativeAccount,ignoreCase=true) || fresh.account!=0)
            throw ExtensionFailure("HTTP_401")
        val renewed=row.copy(config=row.config.copy(cookies=fresh.cookies,scopedCookies=fresh.scopedCookies,nativeSession=fresh.nativeSession))
        write(rows.map { if(it.id==row.id) renewed else it })
        renewed
    } }
    fun seal(value: JSONObject): String=Base64.encodeToString(encrypt(value.toString().toByteArray()),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    fun open(token: String): JSONObject = try {
        require(token.length<=12_000)
        JSONObject(String(decrypt(Base64.decode(token,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)),Charsets.UTF_8))
    } catch(_: Exception) { throw ExtensionFailure("INVALID_RESPONSE") }
    private fun encrypt(value: ByteArray): ByteArray {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key) }
        return cipher.iv+cipher.doFinal(value)
    }
    private fun decrypt(value: ByteArray): ByteArray {
        require(value.size>=29)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,value.copyOfRange(0,12))); doFinal(value.copyOfRange(12,value.size))
        }
    }
    private fun read(): List<GooglePhotosProfile> {
        if(!file.baseFile.exists()) return emptyList()
        require(file.baseFile.length()<=8*1024*1024)
        val rows=JSONArray(String(decrypt(file.readFully()),Charsets.UTF_8))
        return List(rows.length()) { rows.getJSONObject(it).let { r -> GooglePhotosProfile(r.getString("id"),r.getString("name"),r.getString("revision"),GooglePhotosConfig.read(r.getJSONObject("config"))) } }
    }
    private fun write(rows: List<GooglePhotosProfile>) {
        val bytes=encrypt(JSONArray(rows.map { JSONObject().put("id",it.id).put("name",it.name).put("revision",it.revision).put("config",it.config.json()) }).toString().toByteArray())
        val output=file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch(error: Exception) { file.failWrite(output); throw error }
    }
    private fun revoke(row: GooglePhotosProfile) {
        val uri=Uri.Builder().scheme("content").authority("${app.packageName}.media").appendPath(row.id).appendPath(row.revision).build()
        app.revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    companion object { private const val ALIAS="googlephotos-source-v1" }
}
