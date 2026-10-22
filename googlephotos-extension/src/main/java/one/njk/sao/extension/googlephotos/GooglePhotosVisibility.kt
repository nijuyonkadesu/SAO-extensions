package one.njk.sao.extension.googlephotos

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** A read grant makes the granting package visible on Android 11+. No account or media data is exposed. */
internal object GooglePhotosVisibility {
    // File and PackageManager work belongs on the caller's IO dispatcher.
    fun grant(context: Context): Uri {
        val marker = File(context.filesDir, "native_connect/ready").apply {
            parentFile?.mkdirs()
            if (!exists()) createNewFile()
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.connect", marker).also {
            context.grantUriPermission(GooglePhotosConnect.PHOTOS, it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun revoke(context: Context, uri: Uri) {
        context.revokeUriPermission(GooglePhotosConnect.PHOTOS, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
