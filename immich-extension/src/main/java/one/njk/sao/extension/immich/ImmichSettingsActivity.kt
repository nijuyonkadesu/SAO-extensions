package one.njk.sao.extension.immich

import android.content.Intent
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.ViewModelProvider
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import one.njk.sao.extension.immich.databinding.ActivityImmichSettingsBinding
import one.njk.sao.source.ExtensionProtocol
import one.njk.sao.source.settings.SourceIntakeSection
import one.njk.sao.source.settings.SourceSettingsGuard
import one.njk.sao.source.settings.SourceScopeChips

class ImmichSettingsActivity : one.njk.sao.source.settings.SourceSettingsActivity() {
    private val model by lazy { ViewModelProvider(this)[ImmichSettingsViewModel::class.java] }
    private var sourceCreated=false
    override fun onSourceCreate(savedInstanceState: Bundle?,caller: String) {
        sourceCreated=true
        val binding = ActivityImmichSettingsBinding.inflate(layoutInflater); setContentView(binding.root)
        // The encrypted ViewModel draft is authoritative, including credentials and partial input.
        listOf(binding.sourceName,binding.serverUrl,binding.apiKey,binding.album).forEach { it.isSaveEnabled=false }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        val intake=SourceIntakeSection(this,binding.root.findViewById(R.id.intake),binding.root)
        val guard=SourceSettingsGuard(this,binding.saveConnection,{ model.hasChanges },{ model.state.value.working }) { model.check(true) }
        var populated=false
        binding.back.setOnClickListener { guard.leave() }
        binding.sourceName.doAfterTextChanged { if(populated) model.name=it?.toString().orEmpty() }
        binding.serverUrl.doAfterTextChanged { if(populated) { model.url=it?.toString().orEmpty(); intake.editable(model.importsUnchanged) } }
        binding.apiKey.doAfterTextChanged { if(populated) { model.key=it?.toString().orEmpty(); intake.editable(model.importsUnchanged) } }
        fun renderScope() {
            val selected=model.albumId!=null
            binding.allMedia.isChecked=!selected
            binding.album.isActivated=selected
            binding.album.text=model.state.value.albums.firstOrNull { it.id==model.albumId }?.albumName
                ?: getString(if(selected) R.string.immich_saved_album else R.string.choose_album)
        }
        SourceScopeChips(binding.allMedia,binding.album,{
            model.albumId=null; intake.editable(model.importsUnchanged); renderScope()
        },{
            val state=model.state.value
            if(state.albums.isEmpty()) model.check(false)
            else MaterialAlertDialogBuilder(this).setTitle(R.string.immich_album_scope)
                .setSingleChoiceItems(state.albums.map { it.albumName }.toTypedArray(),state.albums.indexOfFirst { it.id==model.albumId }) { dialog,index ->
                    model.albumId=state.albums[index].id; intake.editable(model.importsUnchanged); renderScope(); dialog.dismiss()
                }.setNegativeButton(android.R.string.cancel,null).show()
        })
        binding.testConnection.setOnClickListener { model.check(false) }
        binding.saveConnection.setOnClickListener { model.check(true) }
        binding.deleteSource.isVisible=model.editingId!=null
        binding.deleteSource.setOnClickListener { model.delete() }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { state ->
            if(state.loaded && !populated) {
                binding.sourceName.setText(model.name); binding.serverUrl.setText(model.url); binding.apiKey.setText(model.key); populated=true
            }
            model.importConnection?.let { connection ->
                val app=applicationContext
                intake.configure(requireNotNull(caller),connection.id,connection.revision) { ImmichImports(app,connection).options() }
                intake.editable(model.importsUnchanged && !state.working)
            }
            renderScope()
            binding.status.text=state.version?.let { getString(R.string.connection_status_version,getString(state.status),it) } ?: getString(state.status)
            binding.status.isVisible=state.status!=R.string.immich_setup_description
            binding.progress.isVisible=state.working || !state.loaded
            listOf(binding.sourceName,binding.serverUrl,binding.apiKey,binding.album,binding.allMedia,binding.testConnection,binding.saveConnection,binding.deleteSource)
                .forEach { it.isEnabled=state.loaded && !state.working }
            if(state.finished) {
                setResult(RESULT_OK,Intent().putExtra(ExtensionProtocol.PROFILE,model.profileId)); finish()
            }
        } } }
    }
    override fun onPause() {
        if(sourceCreated) model.checkpoint(discard=isFinishing)
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if(sourceCreated) model.checkpoint()
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        if(sourceCreated && isFinishing) model.checkpoint(discard=true)
        super.onDestroy()
    }
}
