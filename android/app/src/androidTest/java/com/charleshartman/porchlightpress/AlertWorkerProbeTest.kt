package com.charleshartman.porchlightpress

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.charleshartman.porchlightpress.work.AlertCheckWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device probe (Phase 7): inspects the live app state (prefs toggle,
 * storage paths) and runs [AlertCheckWorker] against the real repositories
 * (live NWS when the home place is in the US). Leaves `notifySevere` ON so
 * the follow-up scheduling check (WorkManager diagnostics) is meaningful.
 */
@RunWith(AndroidJUnit4::class)
class AlertWorkerProbeTest {
    @Test
    fun probePrefsAndRunWorker() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as PorchlightApp
        val container = app.container
        val before = runBlocking { container.prefs.snapshot() }
        Log.i(
            "PorchlightProbe",
            "prefs-before notifySevere=${before.notifySevere} " +
                "activeLocationId=${before.activeLocationId} lang=${before.appLanguage}",
        )
        Log.i("PorchlightProbe", "filesDir=${context.filesDir.absolutePath}")
        Log.i(
            "PorchlightProbe",
            "filesDirList=${context.filesDir.listFiles()?.map { it.name }?.sorted()}",
        )
        val dbDir = context.getDatabasePath("porchlight.db").parentFile
        Log.i("PorchlightProbe", "dbDirList=${dbDir?.listFiles()?.map { it.name }?.sorted()}")
        val dbPath = runBlocking {
            container.db.openHelper.writableDatabase.path
        }
        Log.i("PorchlightProbe", "roomPath=$dbPath")
        val home = runBlocking { container.db.savedLocationDao().home() }
        Log.i(
            "PorchlightProbe",
            "home=${home?.id} label=${home?.label} country=${home?.country}",
        )
        // Leave the toggle ON: the Phase 7 contract is worker-scheduled.
        runBlocking { container.prefs.setNotifySevere(true) }
        val worker = TestListenableWorkerBuilder<AlertCheckWorker>(context).build()
        val result = runBlocking { worker.doWork() }
        Log.i("PorchlightProbe", "workerResult=$result")
        assertEquals(ListenableWorker.Result.success(), result)
        val after = runBlocking { container.prefs.snapshot() }
        Log.i("PorchlightProbe", "prefs-after notifySevere=${after.notifySevere}")
    }
}
