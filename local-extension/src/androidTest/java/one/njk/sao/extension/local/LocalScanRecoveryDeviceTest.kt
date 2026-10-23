package one.njk.sao.extension.local

import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import one.njk.sao.source.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class LocalScanRecoveryDeviceTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext
    private val store=LocalStore.get(app)
    private val id=UUID.randomUUID().toString()
    private val root=DocumentsContract.buildTreeDocumentUri("${app.packageName}.fixtures","root/recovery-$id").toString()
    private val other=DocumentsContract.buildTreeDocumentUri("${app.packageName}.fixtures","root/other-$id").toString()
    private fun library()=store.save(id,"Scan recovery",listOf(Folder(root,"First"),Folder(other,"Other"))).also { store.pause(id,true) }
    private fun add(library: Library, root: String, name: String, generation: String="fixture"): LocalAsset {
        val tree=Uri.parse(root)
        val uri=DocumentsContract.buildDocumentUriUsingTree(tree,"${DocumentsContract.getTreeDocumentId(tree)}/$name").toString()
        store.discovered(id,generation,root,uri,name,"${library.folders.single { it.uri==root }.name}/$name","image/png",1,32,library.revision)
        return store.search(id,MediaQuery(name,mode=SearchMode.FILENAME)).single()
    }
    private fun vector(asset: LocalAsset) { store.embedded(asset,FloatArray(512) { if(it==0) 1f else 0f },null) }
    private fun hasVector(asset: LocalAsset)=store.vectors(id,MediaQuery(),asset.id-1,asset.id).use { it.moveToFirst() }
    private fun finish(generation: String) {
        while(true) { val directory=store.directory(id) ?: break; store.directoryDone(id,directory,generation) }
        store.scanFinished(id,generation)
    }
    @After fun cleanup() { FolderIndexer.cancel(app,id); store.delete(id) }

    @Test fun restartingOneRootAfterCompletedScanPreservesOtherRootsCorrectionsAndVectors() {
        val library=library()
        val first=add(library,root,"first.png"); val retained=add(library,other,"retained.png")
        store.corrections(retained.id,id,"Retained title","Retained description","😂",ocr="Retained text")
        store.analyzed(retained,Analysis(listOf("Generated"),"Recognized"),false,null); vector(retained)
        val generation=store.scan(library)
        add(library,root,first.filename,generation); add(library,other,retained.filename,generation)
        finish(generation)
        assertTrue(hasVector(retained))

        store.restartFolder(id,root)
        val restarted=store.scan(store.library(id))
        assertNotEquals(generation,restarted)
        assertEquals(root,store.directory(id)!!.root)
        add(library,root,first.filename,restarted)
        finish(restarted)

        val current=store.asset(retained.id,id)
        assertEquals("Retained title",current.title); assertEquals("Retained description",current.description)
        assertEquals("😂",current.tags); assertEquals("Retained text",current.ocrOverride)
        assertEquals("Generated",current.labels); assertFalse(current.pending); assertTrue(hasVector(retained))
        assertEquals(2,store.status(id).total)
    }

    @Test fun mutableUnorderedListingResumesWithoutSkippingShiftedEntriesAndMakesProgressAfterDeadline()=runBlocking {
        val library=library()
        val names=(0 until 220).map { "file-${it.toString().padStart(3,'0')}.png" }
        val retained=add(library,root,names[100]); val absent=add(library,root,"absent.png")
        store.corrections(retained.id,id,"Corrected","Description","🐱"); vector(retained)
        val generation=store.scan(library); val directory=store.directory(id)!!
        fun listing(values: List<String>)=MatrixCursor(arrayOf("document","name","mime","modified","size")).apply {
            values.forEach { name -> addRow(arrayOf<Any>("${DocumentsContract.getTreeDocumentId(Uri.parse(root))}/$name",name,"image/png",1L,32L)) }
        }
        fun visited()=store.readableDatabase.rawQuery("SELECT count(*) FROM directory_entries WHERE profile=? AND root=?",arrayOf(id,root)).use { it.moveToFirst(); it.getInt(0) }
        try { listing(names).use { FolderDiscovery.read(store,library,directory,generation,it,0) { 1 } }; fail("Expected a scan slice") }
        catch(_: IndexSliceFinished) { }
        assertEquals(100,visited())
        store.checkpoint(id,directory,100,generation) // A historic offset must never skip rows in a fresh SAF query.
        val changed=names.drop(1) // Removal shifts file-100 before the old offset.
        try { listing(changed).use { FolderDiscovery.read(store,library,directory,generation,it,0) { 1 } }; fail("Expected another slice") }
        catch(_: IndexSliceFinished) { }
        assertEquals(200,visited()) // The expired repeated prefix did not prevent new work.
        listing(changed.reversed()).use { FolderDiscovery.read(store,library,directory,generation,it,0) { 1 } }
        assertEquals(220,visited())
        store.directoryDone(id,directory,generation)
        val otherDirectory=store.directory(id)!!; store.directoryDone(id,otherDirectory,generation)
        store.scanFinished(id,generation)

        assertEquals("Corrected",store.asset(retained.id,id).title); assertTrue(hasVector(retained))
        try { store.asset(absent.id,id); fail("A proven-absent asset remained after a complete traversal") }
        catch(error: ExtensionFailure) { assertEquals("HTTP_404",error.code) }
    }

    @Test fun staleGenerationCannotCompleteOrPruneAReplacementScan() {
        val library=library(); val retained=add(library,root,"retained.png")
        val old=store.scan(library); val oldDirectory=store.directory(id)!!
        store.beginIndexRequest(library,"replacement")
        val current=store.scan(library)
        store.directoryDone(id,oldDirectory,old); store.scanFinished(id,old)
        assertNotEquals(old,current); assertEquals(root,store.directory(id)!!.root)
        assertEquals(retained.id,store.asset(retained.id,id).id)
    }

    @Test fun versionEightUpgradeRestartsDiscoveryAndKeepsDurableMetadata() {
        val file=File(app.cacheDir,"scan-migration-$id.db")
        try { android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            store.onCreate(db)
            db.execSQL("DROP TABLE directory_entries"); db.execSQL("DROP TABLE scan_roots")
            db.execSQL("INSERT INTO profiles(id,name,revision) VALUES('fixture','Fixture','1')")
            db.execSQL("INSERT INTO folders(profile,uri,name) VALUES('fixture','root','Root')")
            db.execSQL("INSERT INTO scans VALUES('fixture','1','old')")
            db.execSQL("INSERT INTO directories(profile,root,document,path,position) VALUES('fixture','root','root','Root',100)")
            db.execSQL("INSERT INTO assets(profile,root,uri,filename,filename_search,path,path_search,mime,modified,size,signature,seen,tags) VALUES('fixture','root','content://fixture/retained','retained.png','retained.png','retained.png','retained.png','image/png',1,32,'signature','old','Corrected')")
            store.onUpgrade(db,8,9)
            db.rawQuery("SELECT tags FROM assets",null).use { assertTrue(it.moveToFirst()); assertEquals("Corrected",it.getString(0)) }
            for(table in listOf("scans","directories","scan_roots","directory_entries")) db.rawQuery("SELECT count(*) FROM $table",null).use {
                it.moveToFirst(); assertEquals(0,it.getInt(0))
            }
        } } finally { file.delete() }
    }
}
