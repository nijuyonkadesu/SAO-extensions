package one.njk.sao.extension.googlephotos

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import one.njk.sao.extension.googlephotos.databinding.ActivityGooglephotosSettingsBinding
import one.njk.sao.source.ExtensionProtocol
import one.njk.sao.source.settings.SourceSettingsActivity
import one.njk.sao.source.settings.SourceSettingsGuard

class GooglePhotosSettingsActivity : SourceSettingsActivity() {
    private val model by lazy { ViewModelProvider(this)[GooglePhotosSettingsViewModel::class.java] }
    private val document = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::import) }
    override fun onSourceCreate(savedInstanceState: Bundle?, caller: String) {
        val binding = ActivityGooglephotosSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val guard = SourceSettingsGuard(this, binding.saveSource, { model.hasChanges }, { model.state.value.working }, model::save)
        binding.back.setOnClickListener { guard.leave() }
        var rendering = false
        var albumDialog: androidx.appcompat.app.AlertDialog? = null
        binding.sourceName.doAfterTextChanged { if (!rendering) model.name(it.toString()) }
        binding.accountIndex.doAfterTextChanged { if (!rendering) model.account(it.toString()) }
        binding.connectVector.setOnClickListener { model.connect(::startActivity) }
        binding.cancelConnection.setOnClickListener { model.cancelConnection() }
        binding.checkService.setOnClickListener { model.check() }
        binding.chooseAlbum.setOnClickListener { model.albums() }
        binding.importSession.setOnClickListener { document.launch(arrayOf("text/*", "application/octet-stream")) }
        binding.pasteSession.setOnClickListener {
            val input = TextInputEditText(this).apply {
                hint = getString(R.string.session_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3; maxLines = 6
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                isSaveEnabled = false
            }
            val holder = android.widget.FrameLayout(this).apply {
                val margin = (24 * resources.displayMetrics.density).toInt(); setPadding(margin, margin / 2, margin, 0); addView(input)
            }
            MaterialAlertDialogBuilder(this).setTitle(R.string.paste_session).setView(holder)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.use_session) { _, _ -> model.pasteSession(input.text.toString()); input.text?.clear() }.show()
        }
        binding.showFallback.setOnClickListener { binding.fallback.isVisible = !binding.fallback.isVisible }
        binding.saveSource.setOnClickListener { model.save() }
        binding.removeSource.setOnClickListener { model.delete() }
        binding.removeSource.isVisible = model.editing != null
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    rendering = true
                    if (binding.sourceName.text.toString() != state.name) binding.sourceName.setText(state.name)
                    val accountSlot = String.format(java.util.Locale.ROOT, "%d", state.config.account)
                    if (binding.accountIndex.text.toString() != accountSlot) binding.accountIndex.setText(accountSlot)
                    binding.chooseAlbum.text = state.config.albumName.ifBlank { getString(R.string.whole_library) }
                    binding.sessionStatus.setText(if (state.config.cookies.isEmpty()) R.string.not_connected else R.string.session_ready)
                    rendering = false
                    listOf(binding.sourceName, binding.accountIndex, binding.connectVector, binding.checkService, binding.chooseAlbum,
                        binding.importSession, binding.pasteSession, binding.saveSource, binding.removeSource).forEach {
                        it.isEnabled = state.loaded && !state.working
                    }
                    binding.chooseAlbum.isEnabled = state.loaded && !state.working && state.config.cookies.isNotEmpty()
                    binding.status.setText(state.status); binding.progress.isVisible = state.working
                    binding.cancelConnection.isVisible = state.working && state.status == R.string.waiting_vector
                    if (state.chooseAlbum && albumDialog == null) {
                        albumDialog = MaterialAlertDialogBuilder(this@GooglePhotosSettingsActivity).setTitle(R.string.album_heading)
                            .setItems((listOf(getString(R.string.whole_library)) + state.albums.map { "${it.name} (${it.count})" }).toTypedArray()) { _, index ->
                                model.album(state.albums.getOrNull(index - 1))
                            }.setNegativeButton(android.R.string.cancel) { _, _ -> model.closeAlbums() }
                            .apply {
                                if (state.nextAlbum != null) setNeutralButton(R.string.more_albums) { _, _ -> model.closeAlbums(); model.albums(next = true) }
                            }.create().also { dialog ->
                                dialog.setOnDismissListener { albumDialog = null; if (model.state.value.chooseAlbum) model.closeAlbums() }
                                dialog.show()
                            }
                    }
                    if (state.finished) {
                        setResult(RESULT_OK, Intent().putExtra(ExtensionProtocol.PROFILE, model.profile)); finish()
                    }
                }
            }
        }
    }
}
