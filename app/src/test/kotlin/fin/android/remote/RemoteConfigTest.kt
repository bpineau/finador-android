package fin.android.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Duration
import java.nio.file.Files
import fin.android.storage.AtomicFile

class RemoteConfigTest {
    @Test
    fun roundTrips() {
        val f = File.createTempFile("cfg", ".json").apply { deleteOnExit() }
        val cfg = RemoteConfig("github", GithubConfig("synthetic-owner", "ledger-store", "portfolio.fin", "master"), "30m")
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

    @Test fun truncatedConfigRecoversTheLastGoodSettings() {
        val dir = Files.createTempDirectory("config-recovery").toFile()
        try {
            val file = File(dir, "config.json")
            val first = RemoteConfig("github", GithubConfig("synthetic-owner", "ledger-store"))
            RemoteConfig.save(file, first)
            RemoteConfig.save(file, first.copy(extendedHours = true))
            file.writeText("""{"source":"github","extendedHours":tr""")
            assertEquals(first, RemoteConfig.load(file))
            assertEquals(first, RemoteConfig.load(file)) // promoted recovery stays readable
            assertTrue(AtomicFile.backupOf(file).exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun irrecoverableConfigReturnsToOnboardingAndPreservesCheckout() {
        val dir = Files.createTempDirectory("config-unreadable").toFile()
        try {
            val file = File(dir, "config.json").apply { writeText("{") }
            val checkout = File(dir, "checkout.fin").apply { writeText("synthetic encrypted bytes") }
            assertFalse(RemoteConfig.load(file).isGithub)
            assertEquals("synthetic encrypted bytes", checkout.readText())
        } finally { dir.deleteRecursively() }
    }
}
