package one.njk.sao.extension.moeview

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.DynamicColors
import one.njk.sao.extension.moeview.databinding.ActivityBoardVerificationBinding
import one.njk.sao.moeview.data.ApiKind
import one.njk.sao.moeview.data.Board
import one.njk.sao.moeview.data.SfwMode
import one.njk.sao.moeview.data.ratingClause
import kotlinx.coroutines.*

/** Visible, user-completed verification only. No JS bridge, automation or background WebView. */
class BoardVerificationActivity : AppCompatActivity() {
    private var web: WebView?=null
    private val drafts by lazy { BoardVerificationDrafts(applicationContext) }
    private var request: BoardVerificationRequest?=null
    private var delivered=false
    private var saving: Job?=null
    @SuppressLint("SetJavaScriptEnabled") // Cloudflare's visible challenge requires JavaScript.
    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this); super.onCreate(savedInstanceState); enableEdgeToEdge()
        val request=BoardVerificationRequest.from(intent) ?: run { finish(); return }
        this.request=request
        val board=request.board
        val binding=ActivityBoardVerificationBinding.inflate(layoutInflater); setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        binding.cancel.setOnClickListener { finish() }
        val origin=board.directBase
        binding.finishVerification.setOnClickListener {
            if(saving?.isActive==true) return@setOnClickListener
            val cookies=MoeviewAuthentication.cloudflareCookies(CookieManager.getInstance().getCookie(origin).orEmpty())
            if(!cookies.split(';').any { it.trim().startsWith("cf_clearance=") }) binding.status.setText(R.string.verification_missing)
            else {
                val result=BoardVerificationResult(cookies,binding.web.settings.userAgentString)
                binding.finishVerification.isEnabled=false
                saving=lifecycleScope.launch {
                    try {
                        drafts.write(request,result)
                        withContext(Dispatchers.IO) { CookieManager.getInstance().flush() }
                        deliver(request)
                    } catch(cancelled: CancellationException) { throw cancelled }
                    catch(_: Exception) { binding.status.setText(R.string.storage_error); binding.finishVerification.isEnabled=true }
                }
            }
        }
        fun openWeb() { web=binding.web.apply {
            settings.javaScriptEnabled=true; settings.domStorageEnabled=true
            settings.allowFileAccess=false; settings.allowContentAccess=false
            settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false); settings.javaScriptCanOpenWindowsAutomatically=false
            CookieManager.getInstance().setAcceptCookie(true)
            webViewClient=object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest): Boolean =
                    request.isForMainFrame && (request.url.scheme!="https" || request.url.host!=Uri.parse(origin).host || request.url.port !in setOf(-1,443))
                override fun onRenderProcessGone(view: WebView,detail: RenderProcessGoneDetail): Boolean { finish(); return true }
            }
            val safe=ratingClause(board.apiKind,SfwMode.SFW).orEmpty()
            val path=when(board.apiKind) { ApiKind.MOEBOORU -> "/post?tags="; ApiKind.DANBOORU -> "/posts?tags="; ApiKind.GELBOORU -> "/index.php?page=post&s=list&tags=" }
            loadUrl(origin+path+Uri.encode(safe))
        } }
        lifecycleScope.launch {
            try {
                // A completed challenge may have been encrypted just before process recreation.
                if(drafts.read(request)!=null) { deliver(request); return@launch }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { binding.status.setText(R.string.storage_error) }
            lifecycle.withResumed { openWeb() }
        }
    }
    private fun deliver(request: BoardVerificationRequest) {
        delivered=true
        setResult(RESULT_OK,Intent().putExtra(BoardVerificationRequest.RESULT,request.id))
        finish()
    }
    override fun onPause() { web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }
    override fun onDestroy() {
        if(isFinishing && !delivered) request?.let { drafts.remove(it) }
        web?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.stopLoading(); it.destroy() }; web=null
        super.onDestroy()
    }
}
