package fin.android.data

import fin.android.crypto.Bytes
import fin.android.crypto.Hashes
import fin.android.domain.Money
import fin.android.domain.TxKind
import fin.android.format.Ledger
import fin.android.remote.Backend
import fin.android.remote.Fetched
import fin.android.remote.GithubConfig
import fin.android.remote.RemoteConfig
import fin.android.remote.Version
import fin.android.storage.AtomicFile
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.time.LocalDate

class AppContainerTest {
    private val cfg = RemoteConfig("github", GithubConfig("synthetic-owner", "ledger-store"))
    private fun legacyFile(dir: File): File {
        val gh = cfg.github!!
        val slug = Bytes.toHex(Hashes.sha256("${gh.owner}/${gh.repo}/${gh.path}".toByteArray())).take(16)
        return File(dir, "checkout/$slug.fin")
    }

    @Test fun checkoutsAreSeparatedByBranchAndUnambiguousPath() {
        val dir = Files.createTempDirectory("checkout-identity").toFile()
        try {
            val container = AppContainer(dir, File(dir, "cache"), InMemorySecretStore())
            val gh = requireNotNull(cfg.github)
            val other = cfg.copy(github = gh.copy(branch = "preview"))
            assertNotEquals(container.workingCopy(cfg), container.workingCopy(other))
            val left = cfg.copy(github = gh.copy(path = "a/b", branch = "c"))
            val right = cfg.copy(github = gh.copy(path = "a", branch = "b/c"))
            assertNotEquals(container.workingCopy(left), container.workingCopy(right))
        } finally { dir.deleteRecursively() }
    }

    @Test fun reconfigurationAndForgetMigrateDirtyLegacyCopyOnlyToSavedBranch() {
        for (scenario in listOf("reconfigure", "forget", "recover")) {
            val forget = scenario == "forget"
            val dir = Files.createTempDirectory("checkout-migration").toFile()
            try {
                val base = Ledger.create("migration", t = 1, m = 8)
                val dirty = base.addTransaction(LocalDate.parse("2026-06-01"), "account", null,
                    TxKind.deposit, BigDecimal.ZERO, Money(BigDecimal.TEN, "USD")).toBytes()
                val old = legacyFile(dir).apply { parentFile!!.mkdirs(); writeBytes(dirty) }
                AtomicFile.backupOf(old).writeBytes(base.toBytes())
                File(old.parentFile, old.nameWithoutExtension + ".state.json").writeText("""{"sha":"1","dirty":true}""")
                RemoteConfig.save(File(dir, "config.json"), cfg)
                if (scenario == "recover") {
                    RemoteConfig.save(File(dir, "config.json"), cfg.copy(extendedHours = true))
                    File(dir, "config.json").writeText("{")
                }
                val other = cfg.copy(github = cfg.github!!.copy(branch = "preview"))
                val remote = base.toBytes()
                val container = AppContainer(dir, File(dir, "cache"), InMemorySecretStore()) { _, _ ->
                    object : Backend {
                        override fun fetch() = Fetched(remote, "1")
                        override fun push(data: ByteArray, base: Version?, message: String): Version = error("must not push")
                        override fun checkAccess() {}
                        override fun describe() = "synthetic remote"
                    }
                }
                if (forget) container.clearConfig() else container.saveConfig(other)
                val migrated = container.workingCopy(cfg)
                assertArrayEquals(dirty, migrated.readBytes())
                assertTrue(AtomicFile.backupOf(migrated).exists())
                assertTrue(container.buildSync(cfg, "token").state().dirty)
                container.buildSync(other, "token").openForRead("migration")
                assertEquals(0, Ledger.open(container.workingCopy(other).readBytes(), "migration").book.txs.size)
                assertEquals(1, Ledger.open(migrated.readBytes(), "migration").book.txs.size)
                if (forget) assertFalse(container.loadConfig().isGithub)
                container.loadConfig() // repeat migration/startup is harmless
                assertArrayEquals(dirty, migrated.readBytes())
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun interruptedMigrationResumesAfterStateAndBackupMoved() {
        val dir = Files.createTempDirectory("checkout-migration-interrupted").toFile()
        try {
            RemoteConfig.save(File(dir, "config.json"), cfg)
            val container = AppContainer(dir, File(dir, "cache"), InMemorySecretStore())
            val old = legacyFile(dir).apply { parentFile!!.mkdirs(); writeText("synthetic encrypted bytes") }
            val target = container.workingCopy(cfg)
            File(target.parentFile, target.nameWithoutExtension + ".state.json").writeText("""{"dirty":true}""")
            AtomicFile.backupOf(target).writeText("synthetic backup")
            container.loadConfig()
            assertEquals("synthetic encrypted bytes", target.readText())
            assertEquals("synthetic backup", AtomicFile.backupOf(target).readText())
            assertTrue(container.buildSync(cfg, "token").state().dirty)
            container.loadConfig()
            assertEquals("synthetic encrypted bytes", target.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun forgetCannotRecoverForgottenConfigurationFromItsBackup() {
        val dir = Files.createTempDirectory("checkout-forget").toFile()
        try {
            val container = AppContainer(dir, File(dir, "cache"), InMemorySecretStore())
            container.saveConfig(cfg)
            container.saveConfig(cfg.copy(extendedHours = true))
            container.clearConfig()
            assertFalse(container.loadConfig().isGithub)
        } finally { dir.deleteRecursively() }
    }

    @Test fun unknownLegacyBranchIsNeverAssignedToNewConfiguration() {
        for (brokenConfig in listOf(false, true)) {
            val dir = Files.createTempDirectory("checkout-unknown-branch").toFile()
            try {
                val old = legacyFile(dir).apply { parentFile!!.mkdirs(); writeText("synthetic local edits") }
                AtomicFile.backupOf(old).writeText("synthetic legacy backup")
                if (brokenConfig) File(dir, "config.json").writeText("{")
                val container = AppContainer(dir, File(dir, "cache"), InMemorySecretStore())
                val other = cfg.copy(github = cfg.github!!.copy(branch = "preview"))
                container.saveConfig(other)
                container.loadConfig()
                container.buildSync(other, "token")
                val restarted = AppContainer(dir, File(dir, "cache"), InMemorySecretStore())
                restarted.loadConfig()
                restarted.buildSync(other, "token")
                assertEquals("synthetic local edits", old.readText())
                assertEquals("synthetic legacy backup", AtomicFile.backupOf(old).readText())
                assertFalse(container.workingCopy(other).exists())
                assertFalse(restarted.workingCopy(other).exists())
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun retainedLegacyCopyIsNeverMigratedToALaterBranch() {
        val dir = Files.createTempDirectory("checkout-retained-legacy").toFile()
        try {
            val old = legacyFile(dir).apply { parentFile!!.mkdirs(); writeText("synthetic legacy edits") }
            RemoteConfig.save(File(dir, "config.json"), cfg)
            val container = AppContainer(dir, File(dir, "cache"), InMemorySecretStore())
            container.workingCopy(cfg).writeText("synthetic current checkout")
            container.loadConfig() // retain both copies because the destination already exists
            val other = cfg.copy(github = cfg.github!!.copy(branch = "preview"))
            container.saveConfig(other)
            container.loadConfig()
            val restarted = AppContainer(dir, File(dir, "cache"), InMemorySecretStore())
            restarted.loadConfig()
            restarted.buildSync(other, "token")
            assertEquals("synthetic legacy edits", old.readText())
            assertEquals("synthetic current checkout", container.workingCopy(cfg).readText())
            assertFalse(restarted.workingCopy(other).exists())
        } finally { dir.deleteRecursively() }
    }
}
