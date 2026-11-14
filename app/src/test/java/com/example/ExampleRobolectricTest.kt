package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.FormatUtils
import com.example.data.network.LinkInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("StreamVault", appName)
  }

  @Test
  fun `normalizeUrl extracts valid url from shared text`() {
    val inspector = LinkInspector()
    assertEquals(
      "https://example.com/movie.mp4",
      inspector.normalizeUrl("Watch this movie: https://example.com/movie.mp4 now!")
    )
    assertEquals(
      "https://cdn.example.org/video.mkv",
      inspector.normalizeUrl("cdn.example.org/video.mkv")
    )
    assertNull(inspector.normalizeUrl("   "))
  }

  @Test
  fun `formatBytes formats sizes cleanly`() {
    assertEquals("512 B", FormatUtils.formatBytes(512L))
    assertEquals("1.0 KB", FormatUtils.formatBytes(1024L))
    assertEquals("1.5 MB", FormatUtils.formatBytes(1572864L))
  }
}
