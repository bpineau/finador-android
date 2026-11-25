package fin.android.data

import fin.android.domain.Account
import fin.android.domain.Asset
import fin.android.domain.AssetKind
import fin.android.domain.DividendEvent
import fin.android.domain.MarketData
import fin.android.domain.Money
import fin.android.domain.PricePoint
import fin.android.domain.PriceSeries
import fin.android.domain.TaxRule
import fin.android.domain.TxKind
import fin.android.market.CacheSidecar
import fin.android.market.DailyData
import fin.android.market.MultiSource
import fin.android.market.Provider
import fin.android.market.Ref
import fin.android.market.Yahoo
import fin.android.net.FakeHttpServer
import fin.android.remote.Backend
import fin.android.remote.Fetched
import fin.android.remote.GithubConfig
import fin.android.remote.RemoteConfig
import fin.android.remote.RemoteError
import fin.android.remote.Version
import fin.android.format.Ledger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class AppRepositoryTest {
    private val day = LocalDate.parse("2026-06-01")
    private val cfg = RemoteConfig("github", GithubConfig("synthetic-owner", "ledger-store"))
    private val pass = "synthetic-passphrase"
    private val clock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC)

    private class Remote(var data: ByteArray) : Backend {
        var offline = false
        override fun fetch(): Fetched {
            if (offline) throw RemoteError.Offline("offline")
            return Fetched(data, "1")
        }
        override fun push(data: ByteArray, base: Version?, message: String): Version {
            if (offline) throw RemoteError.Offline("offline")
            this.data = data
            return "1"
        }
        override fun checkAccess() {}
        override fun describe() = "synthetic remote"
    }
    private fun ledger() = Ledger.create(pass, t = 1, m = 8)
        .putAccount(Account("account", "CTO Meridia", "USD", TaxRule.None))
        .putAsset(Asset("asset", AssetKind.SECURITY, "Synthetic Fund", ticker = "GTWR", ccy = "USD"))
        .addTransaction(day, "account", "asset", TxKind.buy, BigDecimal.TEN, Money(BigDecimal("100"), "USD"))

    @Test fun failedReconnectNeverDeletesOfflineRecordsOrTheirBackup() = runBlocking {
        val dir = Files.createTempDirectory("repository-reconnect").toFile()
        try {
            val remote = Remote(ledger().toBytes())
            val secrets = InMemorySecretStore()
            val container = AppContainer(dir, File(dir, "cache"), secrets) { _, _ -> remote }
            val repo = AppRepository(container, clock)
            assertTrue(repo.onboard("synthetic-owner", "ledger-store", "portfolio.fin", "master", "token", pass).isSuccess)
            remote.offline = true
            assertTrue(repo.addTransaction(day, "account", null, TxKind.deposit, BigDecimal.ZERO,
                BigDecimal("20"), "USD", null).isSuccess)
            val file = container.workingCopy(cfg)
            val bytes = file.readBytes()
            val backup = File(file.path + ".bak").readBytes()
            remote.offline = false
            assertTrue(repo.onboard("synthetic-owner", "ledger-store", "portfolio.fin", "master", "token", "wrong").isFailure)
            assertArrayEquals(bytes, file.readBytes())
            assertArrayEquals(backup, File(file.path + ".bak").readBytes())
            assertEquals(pass, secrets.getPassphrase())
            assertEquals("token", secrets.getPat())
            assertTrue(repo.onboard("synthetic-owner", "ledger-store", "portfolio.fin", "master", "token", pass).isSuccess)
            assertEquals(2, repo.currentBook()!!.txs.size)
            assertTrue(repo.syncNow().isSuccess)
            assertEquals(2, Ledger.open(remote.data, pass).book.txs.size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun transactionRejectsAnAccountDeletedRemotelyWhileTheFormWasOpen() = runBlocking {
        val dir = Files.createTempDirectory("repository-deleted-account").toFile()
        try {
            val original = Ledger.create(pass, t = 1, m = 8)
                .putAccount(Account("account", "CTO Meridia", "USD", TaxRule.None))
            val remote = Remote(original.toBytes())
            val container = AppContainer(dir, File(dir, "cache"), InMemorySecretStore()) { _, _ -> remote }
            val repo = AppRepository(container, clock)
            repo.onboard("synthetic-owner", "ledger-store", "portfolio.fin", "master", "token", pass).getOrThrow()
            val deleted = original.deleteAccount("account").toBytes()
            remote.data = deleted
            val result = repo.addTransaction(day, "account", null, TxKind.deposit, BigDecimal.ZERO,
                BigDecimal.TEN, "USD", null)
            assertTrue(result.isFailure)
            assertArrayEquals(deleted, remote.data)
        } finally { dir.deleteRecursively() }
    }

    @Test fun staleEditorsCannotResurrectDeletedAccountsOrAssets() = runBlocking {
        val dir = Files.createTempDirectory("repository-deleted-entities").toFile()
        try {
            val original = Ledger.create(pass, t = 1, m = 8)
                .putAccount(Account("account", "CTO Meridia", "USD", TaxRule.None))
                .putAsset(Asset("asset", AssetKind.SECURITY, "Synthetic Fund", ccy = "USD"))
            val remote = Remote(original.toBytes())
            val container = AppContainer(dir, File(dir, "cache"), InMemorySecretStore()) { _, _ -> remote }
            val repo = AppRepository(container, clock)
            repo.onboard("synthetic-owner", "ledger-store", "portfolio.fin", "master", "token", pass).getOrThrow()
            val deleted = original.deleteAccount("account").deleteAsset("asset").toBytes()
            remote.data = deleted
            assertTrue(repo.editAccount("account", "New name", "USD", TaxRule.None, emptyList()).isFailure)
            assertTrue(repo.editAsset("asset", AssetKind.SECURITY, "New name", null, null,
                emptyList(), "USD", null, null).isFailure)
            assertArrayEquals(deleted, remote.data)
        } finally { dir.deleteRecursively() }
    }

    @Test fun invalidRepositoryTransactionNeverReachesWorkingCopy() = runBlocking {
        val dir = Files.createTempDirectory("repository-validation").toFile()
        try {
            val remote = Remote(ledger().toBytes())
            val container = AppContainer(dir, File(dir, "cache"), InMemorySecretStore()) { _, _ -> remote }
            val repo = AppRepository(container, clock)
            assertTrue(repo.onboard("synthetic-owner", "ledger-store", "portfolio.fin", "master", "token", pass).isSuccess)
            val file = container.workingCopy(cfg)
            val bytes = file.readBytes()
            assertTrue(repo.addTransaction(day, "account", "asset", TxKind.buy, BigDecimal.ZERO,
                BigDecimal.TEN, "USD", null).isFailure)
            assertArrayEquals(bytes, file.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun editedOrSyncedIdentityDropsLegacyQuotesBeforeValuation() = runBlocking {
        for (synced in listOf(false, true)) {
            val dir = Files.createTempDirectory("repository-cache").toFile()
            try {
                val original = ledger()
                val remote = Remote(original.toBytes())
                val container = AppContainer(dir, File(dir, "cache"), InMemorySecretStore()) { _, _ -> remote }
                val cache = container.marketCacheFile(original.fileId)
                CacheSidecar.write(cache, original.cacheKey, MarketData(
                    prices = mapOf("asset" to PriceSeries(listOf(PricePoint(day, 50.0)))), // legacy marker absent
                    fx = mapOf("EUR" to PriceSeries(listOf(PricePoint(day, 1.0)))),
                    dividends = mapOf("asset" to listOf(DividendEvent(day, 2.0))),
                ))
                val repo = AppRepository(container, clock)
                assertTrue(repo.onboard("synthetic-owner", "ledger-store", "portfolio.fin", "master", "token", pass).isSuccess)
                assertEquals(500.0, (repo.state.value as AppState.Ready).valuation.gross, 0.0)
                if (synced) {
                    remote.data = original.putAsset(original.book.assets.getValue("asset").copy(ticker = null)).toBytes()
                    assertTrue(repo.syncNow().isSuccess)
                } else {
                    assertTrue(repo.editAsset("asset", AssetKind.SECURITY, "Synthetic Fund", null, null,
                        emptyList(), "USD", null, null).isSuccess)
                }
                assertEquals(100.0, (repo.state.value as AppState.Ready).valuation.gross, 0.0)
                val persisted = CacheSidecar.read(cache, original.cacheKey)!!
                assertNull(persisted.prices["asset"])
                assertNull(persisted.dividends["asset"])
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun irrecoverableConfigurationPreservesSecretsAndLedgerWhileShowingOnboarding() = runBlocking {
        val dir = Files.createTempDirectory("repository-config").toFile()
        try {
            val secrets = InMemorySecretStore().apply { putPat("token"); putPassphrase(pass) }
            val container = AppContainer(dir, File(dir, "cache"), secrets)
            val file = container.workingCopy(cfg).apply { parentFile!!.mkdirs(); writeBytes(ledger().toBytes()) }
            val bytes = file.readBytes()
            File(dir, "config.json").writeText("{")
            val repo = AppRepository(container, clock)
            repo.refresh()
            assertEquals(AppState.Onboarding, repo.state.value)
            assertArrayEquals(bytes, file.readBytes())
            assertEquals("token", secrets.getPat())
            assertEquals(pass, secrets.getPassphrase())
        } finally { dir.deleteRecursively() }
    }

    @Test fun cacheWriteFailureCannotReportAnAlreadySavedTransactionAsFailed() = runBlocking {
        val dir = Files.createTempDirectory("repository-cache-unwritable").toFile()
        try {
            val initial = ledger()
            val remote = Remote(initial.toBytes())
            val container = AppContainer(dir, File(dir, "cache"), InMemorySecretStore()) { _, _ -> remote }
            val repo = AppRepository(container, clock)
            assertTrue(repo.onboard("synthetic-owner", "ledger-store", "portfolio.fin", "master", "token", pass).isSuccess)
            val blocked = container.marketCacheFile(initial.fileId).apply { mkdirs() }
            File(blocked, "entry").writeText("synthetic blocker") // a nonempty directory blocks rename
            val outcome = repo.addTransaction(day, "account", null, TxKind.deposit,
                BigDecimal.ZERO, BigDecimal.TEN, "USD", null)
            assertTrue(outcome.toString(), outcome.isSuccess)
            assertEquals(2, Ledger.open(remote.data, pass).book.txs.size)
            assertEquals(2, repo.currentBook()!!.txs.size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun changedInstrumentDropsOffHoursPrintBeforeValuingAndAfterFailedRefresh() = runBlocking {
        val dir = Files.createTempDirectory("repository-off-hours").toFile()
        val server = FakeHttpServer()
        try {
            val regular = Instant.parse("2026-05-29T20:00:00Z").epochSecond
            val pre = Instant.parse("2026-06-01T11:14:00Z").epochSecond
            server.dispatcher = object : FakeHttpServer.Dispatcher {
                override fun dispatch(request: FakeHttpServer.FakeRequest): FakeHttpServer.FakeResponse = when {
                    request.path.startsWith("/cookie") -> FakeHttpServer.FakeResponse()
                        .setResponseCode(404).addHeader("Set-Cookie", "A3=synthetic; Path=/")
                    request.path.startsWith("/v1/test/getcrumb") -> FakeHttpServer.FakeResponse().setBody("crumb")
                    request.path.startsWith("/v7/finance/quote") -> FakeHttpServer.FakeResponse().setBody(
                        """{"quoteResponse":{"result":[{"symbol":"GTWR","currency":"USD",
                        "regularMarketPrice":50,"regularMarketTime":$regular,
                        "preMarketPrice":60,"preMarketTime":$pre}]}}""",
                    )
                    else -> FakeHttpServer.FakeResponse().setBody("""{"chart":{"result":[],"error":null}}""")
                }
            }
            server.start()
            var available = true
            val provider = object : Provider {
                override val name = "synthetic"
                override fun daily(ref: Ref, from: LocalDate): DailyData? = if (available)
                    DailyData("USD", listOf(PricePoint(day, 50.0))) else null
            }
            val remote = Remote(ledger().toBytes())
            val container = AppContainer(dir, File(dir, "cache"), InMemorySecretStore()) { _, _ -> remote }
            val repo = AppRepository(container, clock,
                Yahoo(server.url("/").trimEnd('/'), server.url("/cookie")), MultiSource(listOf(provider)))
            assertTrue(repo.onboard("synthetic-owner", "ledger-store", "portfolio.fin", "master", "token", pass).isSuccess)
            container.saveConfig(container.loadConfig().copy(displayCurrency = "USD"))
            repo.setExtendedHours(true)
            assertEquals(600.0, (repo.state.value as AppState.Ready).valuation.gross, 0.0)
            assertTrue((repo.state.value as AppState.Ready).offHours.containsKey("asset"))
            available = false
            assertTrue(repo.editAsset("asset", AssetKind.SECURITY, "Synthetic Fund", "OTHER", null,
                emptyList(), "USD", null, null).isSuccess)
            assertTrue((repo.state.value as AppState.Ready).offHours.isEmpty())
            assertEquals(100.0, (repo.state.value as AppState.Ready).valuation.gross, 0.0)
            repo.refreshQuotes()
            assertEquals(100.0, (repo.state.value as AppState.Ready).valuation.gross, 0.0)
            assertTrue((repo.state.value as AppState.Ready).offHours.isEmpty())
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    /** A remote file that does not exist yet, behind a token that may or may not write. */
    private class NewRemote : Backend {
        var visible = true
        var canWrite = false
        var data: ByteArray? = null
        override fun checkAccess() { if (!visible) throw RemoteError.Auth("synthetic: repository not visible") }
        override fun fetch(): Fetched = data?.let { Fetched(it, "1") } ?: throw RemoteError.Missing()
        override fun push(data: ByteArray, base: Version?, message: String): Version {
            if (!canWrite) throw RemoteError.Auth("synthetic: read-only token")
            this.data = data
            return "1"
        }
        override fun describe() = "synthetic new remote"
    }

    @Test fun firstPushMustSucceedBeforeOnboardingSavesANewLedger() = runBlocking {
        val dir = Files.createTempDirectory("repository-initial-conflict").toFile()
        try {
            val remote = object : Backend by NewRemote() {
                override fun push(data: ByteArray, base: Version?, message: String): Version =
                    throw RemoteError.Conflict()
            }
            val container = AppContainer(dir, File(dir, "cache"), InMemorySecretStore()) { _, _ -> remote }
            val repo = AppRepository(container, clock)
            val result = repo.onboard("synthetic-owner", "ledger-store", "portfolio.fin", "master", "token", pass)
            assertTrue(result.isFailure)
            assertFalse(container.loadConfig().isGithub)
            assertFalse(container.workingCopy(cfg).exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun failedFirstPushLeavesNothingThatRefusesAnotherPassphrase() = runBlocking {
        val dir = Files.createTempDirectory("repository-first-push").toFile()
        try {
            val remote = NewRemote()
            val secrets = InMemorySecretStore()
            val container = AppContainer(dir, File(dir, "cache"), secrets) { _, _ -> remote }
            val repo = AppRepository(container, clock)
            assertTrue(repo.onboard("synthetic-owner", "ledger-store", "portfolio.fin", "master", "token", "first").isFailure)
            assertFalse(container.workingCopy(cfg).exists())
            assertNull(secrets.getPassphrase())
            remote.canWrite = true
            val retry = repo.onboard("synthetic-owner", "ledger-store", "portfolio.fin", "master", "token", pass)
            assertTrue(retry.toString(), retry.isSuccess)
            assertEquals(0, Ledger.open(remote.data!!, pass).book.txs.size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun firstConnectionRequiresAConfirmedRemoteFileLookup() = runBlocking {
        for (accessOffline in listOf(false, true)) {
            val dir = Files.createTempDirectory("repository-first-offline").toFile()
            try {
                val original = ledger().toBytes()
                var offline = true
                val remote = object : Backend {
                    override fun checkAccess() {
                        if (accessOffline && offline) throw RemoteError.Offline("synthetic offline")
                    }
                    override fun fetch(): Fetched {
                        if (offline) throw RemoteError.Offline("synthetic offline")
                        return Fetched(original, "1")
                    }
                    override fun push(data: ByteArray, base: Version?, message: String): Version =
                        error("an existing remote must not be replaced during connection")
                    override fun describe() = "synthetic existing remote"
                }
                val secrets = InMemorySecretStore()
                val container = AppContainer(dir, File(dir, "cache"), secrets) { _, _ -> remote }
                val repo = AppRepository(container, clock)
                val result = repo.onboard("synthetic-owner", "ledger-store", "portfolio.fin", "master", "token", pass)
                assertTrue("A failed lookup must not create an unrelated ledger: $result", result.isFailure)
                assertFalse(container.workingCopy(cfg).exists())
                assertFalse(container.loadConfig().isGithub)
                assertNull(secrets.getPassphrase())
                offline = false
                assertTrue(repo.onboard("synthetic-owner", "ledger-store", "portfolio.fin", "master", "token", pass).isSuccess)
                assertArrayEquals(original, container.workingCopy(cfg).readBytes())
                assertEquals(1, repo.currentBook()!!.txs.size)
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun existingCopyCanStillReconnectAndAcceptWritesOffline() = runBlocking {
        val dir = Files.createTempDirectory("repository-reconnect-offline").toFile()
        try {
            val remote = Remote(ledger().toBytes())
            val backend = object : Backend by remote {
                override fun checkAccess() {
                    if (remote.offline) throw RemoteError.Offline("synthetic offline")
                }
            }
            val container = AppContainer(dir, File(dir, "cache"), InMemorySecretStore()) { _, _ -> backend }
            val repo = AppRepository(container, clock)
            assertTrue(repo.onboard("synthetic-owner", "ledger-store", "portfolio.fin", "master", "token", pass).isSuccess)
            remote.offline = true
            assertTrue(repo.onboard("synthetic-owner", "ledger-store", "portfolio.fin", "master", "token", pass).isSuccess)
            assertTrue(repo.addTransaction(day, "account", null, TxKind.deposit, BigDecimal.ZERO,
                BigDecimal.TEN, "USD", null).isSuccess)
            assertEquals(2, repo.currentBook()!!.txs.size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun repositoryTheTokenCannotSeeNeverStartsAnEmptyLedger() = runBlocking {
        val dir = Files.createTempDirectory("repository-invisible").toFile()
        try {
            val remote = NewRemote().apply { visible = false; canWrite = true }
            val container = AppContainer(dir, File(dir, "cache"), InMemorySecretStore()) { _, _ -> remote }
            val repo = AppRepository(container, clock)
            assertTrue(repo.onboard("synthetic-owner", "ledger-store", "portfolio.fin", "master", "token", pass).isFailure)
            assertFalse(container.workingCopy(cfg).exists())
            assertNull(remote.data)
            assertFalse(container.loadConfig().isGithub)
        } finally { dir.deleteRecursively() }
    }

    @Test fun changedInstrumentIsReportedOnceByTheNextRefresh() = runBlocking {
        val dir = Files.createTempDirectory("repository-requoted").toFile()
        val server = FakeHttpServer().apply {
            dispatcher = object : FakeHttpServer.Dispatcher {
                override fun dispatch(request: FakeHttpServer.FakeRequest) =
                    FakeHttpServer.FakeResponse().setResponseCode(404)
            }
            start()
        }
        try {
            val provider = object : Provider {
                override val name = "synthetic"
                override fun daily(ref: Ref, from: LocalDate): DailyData = DailyData("USD", listOf(PricePoint(day, 50.0)))
            }
            val container = AppContainer(dir, File(dir, "cache"), InMemorySecretStore()) { _, _ -> Remote(ledger().toBytes()) }
            val repo = AppRepository(container, clock,
                Yahoo(server.url("/").trimEnd('/'), server.url("/cookie")), MultiSource(listOf(provider)))
            assertTrue(repo.onboard("synthetic-owner", "ledger-store", "portfolio.fin", "master", "token", pass).isSuccess)
            val changed = "ticker, ISIN or currency changed"
            assertTrue(repo.refreshQuotes().none { changed in it })
            assertTrue(repo.editAsset("asset", AssetKind.SECURITY, "Synthetic Fund", "OTHER", null,
                emptyList(), "USD", null, null).isSuccess)
            val warnings = repo.refreshQuotes()
            assertTrue(warnings.toString(), warnings.first().startsWith("OTHER: $changed"))
            assertTrue(repo.refreshQuotes().none { changed in it })
        } finally { server.shutdown(); dir.deleteRecursively() }
    }
}
