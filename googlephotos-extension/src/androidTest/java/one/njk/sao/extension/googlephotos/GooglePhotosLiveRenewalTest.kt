package one.njk.sao.extension.googlephotos

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in only; verifies authentication without searching or reading any media. */
class GooglePhotosLiveRenewalTest {
    @Test fun selectedAccountRenewsWithoutOpeningPhotosActivity(): Unit = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveNativeRenewal")=="true")
        val app=instrumentation.targetContext
        val runtime=GooglePhotosRuntime.get(app)
        val legacy=args.getString("allowLegacyNative")=="true"
        val profiles=runtime.profiles.list().filter { legacy || it.config.nativeAccount.isNotEmpty() }
        val row=profiles.singleOrNull { args.getString("profile")?.let { id -> it.id==id } ?: (profiles.size==1) }
            ?: throw IllegalStateException("Connect the source through Vector first; specify a profile when several are configured")
        val foreground=instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
        assertFalse("Photos must be in the background for this check",foreground==GooglePhotosConnect.PHOTOS)
        // Older native connections omitted account identity. An explicit developer check may
        // bind the handshake to the identity reported by that existing signed-in session.
        // This does not enable renewal for imported sessions or alter a legacy profile.
        val account=row.config.nativeAccount.ifBlank {
            GooglePhotosApi(row.config,runtime.http()).check().account
                ?: throw IllegalStateException("Reconnect through Vector to record the selected account")
        }
        val coordinator=GooglePhotosSessionRenewal(runtime.profiles,{ account ->
            GooglePhotosNativeRenewal.connect(app,account,runtime.http())
        })
        val fresh=if(row.config.nativeAccount.isNotEmpty()) coordinator.renew(row)
            else row.copy(config=GooglePhotosNativeRenewal.connect(app,account,runtime.http()))
        assertEquals(row.revision,fresh.revision)
        assertTrue(fresh.config.nativeAccount.equals(account,ignoreCase=true))
        assertNotEquals(row.config.nativeSession,fresh.config.nativeSession)
        // Only the signed-in bootstrap page, never a library/media request.
        GooglePhotosApi(fresh.config,runtime.http()).check()
        assertFalse("Silent renewal must not bring Photos to the foreground",
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()==GooglePhotosConnect.PHOTOS)
        android.util.Log.i("SAO.GooglePhotos","Live silent renewal verified without reading media")
    }
}
