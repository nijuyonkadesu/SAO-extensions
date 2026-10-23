package one.njk.sao.extension.moeview

import android.content.Context
import android.content.Intent
import one.njk.sao.extension.runtime.EncryptedDraftStore
import one.njk.sao.moeview.data.Board
import org.json.JSONObject
import java.util.UUID

/** Only the request identity crosses Activity/saved-state boundaries; cookies remain private. */
internal data class BoardVerificationRequest(val id: String,val profile: String,val board: Board) {
    init { UUID.fromString(id); UUID.fromString(profile) }
    fun intent(context: Context)=Intent(context,BoardVerificationActivity::class.java)
        .putExtra(REQUEST,id).putExtra(PROFILE,profile).putExtra(BOARD,board.name)
    companion object {
        const val REQUEST="verification_request"
        const val PROFILE="verification_profile"
        const val BOARD="board"
        const val RESULT="verification_token"
        fun from(intent: Intent): BoardVerificationRequest?=runCatching {
            BoardVerificationRequest(requireNotNull(intent.getStringExtra(REQUEST)),
                requireNotNull(intent.getStringExtra(PROFILE)),Board.valueOf(requireNotNull(intent.getStringExtra(BOARD))))
        }.getOrNull()
    }
}

internal data class BoardVerificationResult(val cookies: String,val agent: String)

internal class BoardVerificationDrafts(context: Context) {
    private val store=EncryptedDraftStore(context,"moeview-verification")
    suspend fun write(request: BoardVerificationRequest,result: BoardVerificationResult) {
        validate(result)
        store.checkpoint(request.id,JSONObject().put("version",1).put("request",request.id).put("profile",request.profile)
            .put("board",request.board.name).put("cookies",result.cookies).put("agent",result.agent)).await()
    }
    suspend fun read(request: BoardVerificationRequest): BoardVerificationResult?=store.read(request.id)?.let {
        require(it.getInt("version")==1 && it.getString("request")==request.id &&
            it.getString("profile")==request.profile && it.getString("board")==request.board.name)
        BoardVerificationResult(it.getString("cookies"),it.getString("agent")).also(::validate)
    }
    fun remove(request: BoardVerificationRequest)=store.checkpoint(request.id,null)
    private fun validate(result: BoardVerificationResult) {
        require(result.cookies.length<=8192 && result.agent.length<=1024 &&
            result.cookies==MoeviewAuthentication.cloudflareCookies(result.cookies))
        require(result.cookies.split(';').any { it.trim().startsWith("cf_clearance=") })
        require(result.agent.none { it=='\r' || it=='\n' })
    }
}
