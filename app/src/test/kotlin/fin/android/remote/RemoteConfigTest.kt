package fin.android.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Duration

class RemoteConfigTest {
    @Test
    fun roundTrips() {
        val f = File.createTempFile("cfg", ".json").apply { deleteOnExit() }
        val cfg = RemoteConfig("github", GithubConfig("bpineau", "finador-data", "portfolio.fin", "master"), "30m")
        RemoteConfig.save(f, cfg)
        val loaded = RemoteConfig.load(f)
        assertEquals(cfg, loaded)
        assertTrue(loaded.isGithub)
        assertEquals(Duration.ofMinutes(30), loaded.readPullAfterDuration())
    }

    @Test
    fun defaultsWhenMissing() {
        val cfg = RemoteConfig.load(File("/nonexistent/path/finador-xyz.json"))
        assertEquals("local", cfg.source)
        assertFalse(cfg.isGithub)
    }

    @Test
    fun parsesDurations() {
        assertEquals(Duration.ofHours(1), RemoteConfig.parseGoDuration("1h"))
        assertEquals(Duration.ofMinutes(15), RemoteConfig.parseGoDuration("15m"))
        assertEquals(Duration.ofSeconds(45), RemoteConfig.parseGoDuration("45s"))
        assertEquals(Duration.ofHours(1), RemoteConfig.parseGoDuration("garbage")) // safe fallback
    }
}
