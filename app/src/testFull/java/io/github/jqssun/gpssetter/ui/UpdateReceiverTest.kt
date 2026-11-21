package io.github.jqssun.gpssetter.ui

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import io.github.jqssun.gpssetter.ui.viewmodel.MainViewModel
import io.github.jqssun.gpssetter.update.UpdateChecker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateReceiverTest {
    @Test fun onlyTheActiveDownloadCanReportAFailure() {
        val controller = Robolectric.buildActivity(MapActivity::class.java).setup()
        val home = controller.get()
        val model = ViewModelProvider(home)[MainViewModel::class.java]
        val manager = home.getSystemService(DownloadManager::class.java)
        val otherId = manager.enqueue(DownloadManager.Request(Uri.parse("https://example.org/other")))
        fun broadcast(id: Long) {
            home.sendBroadcast(Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE).setPackage(home.packageName)
                .putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, id))
            shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            model.startDownload(home, UpdateChecker.Update("release", "", "", "https://example.org/update.apk", "update.apk"))
            shadowOf(Looper.getMainLooper()).idle()
            val ours = otherId + 1
            shadowOf(shadowOf(manager).getRequest(ours)).status = DownloadManager.STATUS_FAILED
            broadcast(otherId)
            assertFalse(model.downloadState.value is MainViewModel.State.Failed)
            broadcast(ours)
            assertTrue(model.downloadState.value is MainViewModel.State.Failed)
        } finally {
            model.cancelDownload(home)
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause().stop().destroy()
        }
    }
}
