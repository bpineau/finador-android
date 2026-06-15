package fin.android.format

import fin.android.crypto.Ids
import fin.android.domain.Account
import fin.android.domain.Asset
import fin.android.domain.AssetKind
import fin.android.domain.Money
import fin.android.domain.TaxRule
import fin.android.domain.TxKind
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Writes Android-produced `.fin` files for the cross-implementation gate so the Go `finador` CLI
 * can be pointed at them. Not a pure assertion test - driven by `scripts/crossimpl.sh`.
 */
class CrossImplProducerTest {
    private val outDir = File(System.getProperty("crossimpl.out") ?: "build/crossimpl")

    @Test
    fun writeAndroidMutatedSample() {
        val pw = "finador-format-spec-v3"
        val sample = javaClass.getResourceAsStream("/sample.ledger")!!.use { it.readBytes() }
        val mutated = Ledger.open(sample, pw).addTransaction(
            date = LocalDate.parse("2026-06-12"),
            account = "06fc2cjx2bvtjjxmtmcj2wg", // PEA BforBank
            asset = null,
            kind = TxKind.deposit,
            qty = BigDecimal.ZERO,
            amount = Money(BigDecimal("5000"), "EUR"),
            note = "from-android",
        ).putAccount(
            // A net-new account written by Android - the Go CLI must read it back (crossimpl.sh §3).
            Account(Ids.newId(), "Android Test Account", "USD", TaxRule.Gains(BigDecimal("0.25")), listOf("android-acct")),
        ).putAsset(
            // A net-new asset written by Android - the Go CLI must read it back (crossimpl.sh §3).
            Asset(Ids.newId(), AssetKind.SECURITY, "Android Test Asset", "AND.TST", "US0000000000",
                listOf("android-asset"), "USD", "test/group", 0.15),
        )
        outDir.mkdirs()
        File(outDir, "android-mutated.fin").writeBytes(mutated.toBytes())
    }
}
