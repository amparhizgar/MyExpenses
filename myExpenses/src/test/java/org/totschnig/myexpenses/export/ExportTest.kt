/*   This file is part of My Expenses.
 *   My Expenses is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   My Expenses is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with My Expenses.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.totschnig.myexpenses.export

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Expect
import org.apache.commons.text.translate.UnicodeEscaper
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.totschnig.myexpenses.R
import org.totschnig.myexpenses.db2.Repository
import org.totschnig.myexpenses.model.Account
import org.totschnig.myexpenses.model.AccountType
import org.totschnig.myexpenses.model.CrStatus
import org.totschnig.myexpenses.model.CurrencyContext
import org.totschnig.myexpenses.model.ExportFormat
import org.totschnig.myexpenses.model.Money
import org.totschnig.myexpenses.model.PaymentMethod
import org.totschnig.myexpenses.model.SplitTransaction
import org.totschnig.myexpenses.model.Transaction
import org.totschnig.myexpenses.model.Transfer
import org.totschnig.myexpenses.model.saveTagLinks
import org.totschnig.myexpenses.model.write
import org.totschnig.myexpenses.preference.PrefHandler
import org.totschnig.myexpenses.provider.DatabaseConstants
import org.totschnig.myexpenses.provider.TransactionProvider
import org.totschnig.myexpenses.util.CurrencyFormatter
import org.totschnig.myexpenses.viewmodel.data.Category
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*


@RunWith(RobolectricTestRunner::class)
class ExportTest {
    private val openingBalance = 100L
    private val expense1 = 10L

    //status cleared
    private val expense2 = 20L
    private val income1 = 30L
    private val income2 = 40L
    private val transferP = 50L

    //status reconciled
    private val transferN = 60L
    private val expense3 = 100L
    private val income3 = 100L
    private val split1 = 70L
    private val part1 = 40L
    private val part2 = 30L

    @Suppress("DEPRECATION")
    private var base = Date(117, 11, 15, 12, 0, 0)
    private var baseSinceEpoch = base.time / 1000
    private var date: String = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(base)
    private lateinit var outFile: File

    @Before
    fun setUp() {
        outFile = File(context.cacheDir, FILE_NAME)
    }

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val repository: Repository
        get() = Repository(context,
            Mockito.mock(CurrencyContext::class.java),
            Mockito.mock(CurrencyFormatter::class.java),
            Mockito.mock(PrefHandler::class.java))

    @get:Rule
    val expect: Expect = Expect.create()

    private fun writeCategory(label: String, parentId: Long? = null) =
        ContentUris.parseId(repository.saveCategory(Category(label = label, parentId = parentId))!!)

    private fun insertData1(): Account {
        val tag1Id = write("Tag One")
        val tag2Id = write("Tags, Tags, Tags")
        val account1 = Account("Account 1", openingBalance, "Account 1")
        account1.type = AccountType.BANK
        account1.save()
        val account2 = Account("Account 2", openingBalance, "Account 2")
        account2.save()
        val cat1Id = writeCategory("Main")
        val cat2Id = writeCategory("Sub", cat1Id)
        val op = Transaction.getNewInstance(account1.id) ?: throw IllegalStateException()
        op.amount = Money(account1.currencyUnit, -expense1)
        op.methodId = PaymentMethod.find("CHEQUE")
        op.crStatus = CrStatus.CLEARED
        op.referenceNumber = "1"
        op.date = baseSinceEpoch
        op.save()
        context.contentResolver.applyBatch(
            TransactionProvider.AUTHORITY,
            saveTagLinks(listOf(tag1Id, tag2Id), op.id, null, true)
        )
        op.amount = (Money(account1.currencyUnit, -expense2))
        op.catId = cat1Id
        op.payee = "N.N."
        op.crStatus = CrStatus.UNRECONCILED
        op.referenceNumber = "2"
        op.date = baseSinceEpoch + 1
        op.saveAsNew()
        op.amount = Money(account1.currencyUnit, income1)
        op.catId = cat2Id
        op.payee = null
        op.methodId = null
        op.referenceNumber = null
        op.date = baseSinceEpoch + 2
        op.saveAsNew()
        val contentValues = ContentValues(1)
        contentValues.put(DatabaseConstants.KEY_PICTURE_URI, "file://sdcard/picture.png")
        context.contentResolver.update(
            ContentUris.withAppendedId(Transaction.CONTENT_URI, op.id),
            contentValues,
            null,
            null
        )
        op.amount = Money(account1.currencyUnit, income2)
        op.comment = "Note for myself with \"quote\""
        op.date = baseSinceEpoch + 3
        op.saveAsNew()
        val transfer = Transfer.getNewInstance(account1.id, account2.id)
            ?: throw IllegalStateException()
        transfer.setAmount(Money(account1.currencyUnit, transferP))
        transfer.crStatus = CrStatus.RECONCILED
        transfer.date = baseSinceEpoch + 4
        transfer.save()
        transfer.crStatus = CrStatus.UNRECONCILED
        transfer.setAmount(Money(account1.currencyUnit, -transferN))
        transfer.date = baseSinceEpoch + 5
        transfer.saveAsNew()
        val split = SplitTransaction.getNewInstance(account1.id) ?: throw IllegalStateException()
        split.amount = Money(account1.currencyUnit, split1)
        split.date = baseSinceEpoch + 6
        val part = Transaction.getNewInstance(account1.id, split.id)
            ?: throw IllegalStateException()
        part.amount = Money(account1.currencyUnit, part1)
        part.catId = cat1Id
        part.status = DatabaseConstants.STATUS_UNCOMMITTED
        part.save()
        part.amount = Money(account1.currencyUnit, part2)
        part.catId = cat2Id
        part.saveAsNew()
        split.status = DatabaseConstants.STATUS_NONE
        split.save(true)
        return account1
    }

    private fun insertData2(account: Account) {
        val op = Transaction.getNewInstance(account.id) ?: throw IllegalStateException()
        op.amount = Money(account.currencyUnit, -expense3)
        op.methodId = PaymentMethod.find("CHEQUE")
        op.comment = "Expense inserted after first export"
        op.referenceNumber = "3"
        op.date = baseSinceEpoch
        op.save()
        op.amount = Money(account.currencyUnit, income3)
        op.comment = "Income inserted after first export"
        op.payee = "N.N."
        op.methodId = null
        op.referenceNumber = null
        op.date = baseSinceEpoch + 1
        op.saveAsNew()
    }

    private fun insertData3(): Pair<Account, Account> {
        var op: Transaction?
        val account1 = Account("Account 1", openingBalance, "Account 1")
        account1.type = AccountType.BANK
        account1.save()
        val account2 = Account("Account 2", openingBalance, "Account 2")
        account2.save()
        op = Transaction.getNewInstance(account1.id)
        if (op == null) {
            throw IllegalStateException()
        }
        op.amount = Money(account1.currencyUnit, -expense1)
        op.methodId = PaymentMethod.find("CHEQUE")
        op.crStatus = CrStatus.CLEARED
        op.referenceNumber = "1"
        op.date = baseSinceEpoch
        op.save()
        op = Transaction.getNewInstance(account2.id)
        if (op == null) {
            throw IllegalStateException()
        }
        op.amount = Money(account1.currencyUnit, -expense1)
        op.methodId = PaymentMethod.find("CHEQUE")
        op.crStatus = CrStatus.CLEARED
        op.referenceNumber = "1"
        op.date = baseSinceEpoch
        op.save()
        return Pair(account1, account2)
    }

    @Test
    fun testExportQIF() {
        val linesQIF = arrayOf(
            "!Account",
            "NAccount 1",
            "TBank",
            "^",
            "!Type:Bank",
            "D$date",
            "T-0.10",
            "C*",
            "N1",
            "^",
            "D$date",
            "T-0.20",
            "LMain",
            "PN.N.",
            "N2",
            "^",
            "D$date",
            "T0.30",
            "LMain:Sub",
            "^",
            "D$date",
            "T0.40",
            "MNote for myself with \"quote\"",
            "LMain:Sub",
            "^",
            "D$date",
            "T0.50",
            "L[Account 2]",
            "CX",
            "^",
            "D$date",
            "T-0.60",
            "L[Account 2]",
            "^",
            "D$date",
            "T0.70",
            "LMain",
            "SMain",
            "$0.40",
            "SMain:Sub",
            "$0.30",
            "^"
        )
        try {
            expect.that(
                exportAll(
                    insertData1(),
                    ExportFormat.QIF,
                    notYetExportedP = false,
                    append = false,
                    withAccountColumn = false
                ).isSuccess
            ).isTrue()
            compare(linesQIF)
        } catch (e: IOException) {
            expect.withMessage("Could not export expenses. Error: ${e.message}").fail()
        }
    }

    @Test
    fun testExportCSV() {
        val linesCSV = arrayOf(
            csvHeader(';', false),
            "\"\";\"" + date + "\";\"\";\"0\";\"0.10\";\"\";\"\";\"" + context.getString(R.string.pm_cheque)
                    + "\";\"*\";\"1\";\"\";\"Tag One, 'Tags, Tags, Tags'\"",
            "\"\";\"$date\";\"N.N.\";\"0\";\"0.20\";\"Main\";\"\";\"" + context.getString(
                R.string.pm_cheque
            )
                    + "\";\"\";\"2\";\"\";\"\"",
            "\"\";\"$date\";\"\";\"0.30\";\"0\";\"Main:Sub\";\"\";\"\";\"\";\"\";\"picture.png\";\"\"",
            "\"\";\"$date\";\"\";\"0.40\";\"0\";\"Main:Sub\";\"Note for myself with \"\"quote\"\"\";\"\";\"\";\"\";\"\";\"\"",
            "\"\";\"$date\";\"\";\"0.50\";\"0\";\"[Account 2]\";\"\";\"\";\"X\";\"\";\"\";\"\"",
            "\"\";\"$date\";\"\";\"0\";\"0.60\";\"[Account 2]\";\"\";\"\";\"\";\"\";\"\";\"\"",
            "\"*\";\"$date\";\"\";\"0.70\";\"0\";\"Main\";\"\";\"\";\"\";\"\";\"\";\"\"",
            "\"-\";\"$date\";\"\";\"0.40\";\"0\";\"Main\";\"\";\"\";\"\";\"\";\"\";\"\"",
            "\"-\";\"$date\";\"\";\"0.30\";\"0\";\"Main:Sub\";\"\";\"\";\"\";\"\";\"\";\"\""
        )
        try {
            expect.that(
                exportAll(
                    insertData1(),
                    ExportFormat.CSV,
                    notYetExportedP = false,
                    append = false,
                    withAccountColumn = false
                ).isSuccess
            ).isTrue()
            compare(linesCSV)
        } catch (e: IOException) {
            expect.withMessage("Could not export expenses. Error: ${e.message}").fail()
        }
    }

    @Test
    fun testExportCSVCustomFormat() {
        val date = SimpleDateFormat("M/d/yyyy", Locale.US).format(base)
        val linesCSV = arrayOf(
            csvHeader(',', false),
            "\"\",\"" + date + "\",\"\",\"0\",\"0,10\",\"\",\"\",\"" + context.getString(R.string.pm_cheque)
                    + "\",\"*\",\"1\",\"\",\"Tag One, 'Tags, Tags, Tags'\"",
            "\"\",\"$date\",\"N.N.\",\"0\",\"0,20\",\"Main\",\"\",\"" + context.getString(
                R.string.pm_cheque
            )
                    + "\",\"\",\"2\",\"\",\"\"",
            "\"\",\"$date\",\"\",\"0,30\",\"0\",\"Main:Sub\",\"\",\"\",\"\",\"\",\"picture.png\",\"\"",
            "\"\",\"$date\",\"\",\"0,40\",\"0\",\"Main:Sub\",\"Note for myself with \"\"quote\"\"\",\"\",\"\",\"\",\"\",\"\"",
            "\"\",\"$date\",\"\",\"0,50\",\"0\",\"[Account 2]\",\"\",\"\",\"X\",\"\",\"\",\"\"",
            "\"\",\"$date\",\"\",\"0\",\"0,60\",\"[Account 2]\",\"\",\"\",\"\",\"\",\"\",\"\"",
            "\"*\",\"$date\",\"\",\"0,70\",\"0\",\"Main\",\"\",\"\",\"\",\"\",\"\",\"\"",
            "\"-\",\"$date\",\"\",\"0,40\",\"0\",\"Main\",\"\",\"\",\"\",\"\",\"\",\"\"",
            "\"-\",\"$date\",\"\",\"0,30\",\"0\",\"Main:Sub\",\"\",\"\",\"\",\"\",\"\",\"\""
        )
        try {
            expect.that(
                CsvExporter(
                    insertData1(),
                    null,
                    false,
                    "M/d/yyyy",
                    ',',
                    "UTF-8",
                    true,
                    ',',
                    false
                )
                    .export(
                        context,
                        lazy { Result.success(DocumentFile.fromFile(outFile)) },
                        false
                    ).isSuccess
            ).isTrue()
            compare(linesCSV)
        } catch (e: IOException) {
            expect.withMessage("Could not export expenses. Error: ${e.message}").fail()
        }
    }

    @Test
    fun testQIFCategorySanitizer() {
        val unicodeEscaper = UnicodeEscaper()
        val colonEscaped = unicodeEscaper.translate(":")
        val slashEscaped = unicodeEscaper.translate("/")
        val linesQIF = arrayOf(
            "!Account",
            "NAccount 1",
            "TBank",
            "^",
            "!Type:Bank",
            "D$date",
            "T0.40",
            "LWith${slashEscaped}and${colonEscaped}Main:With${slashEscaped}and${colonEscaped}Sub",
            "^"
        )

        val account = Account("Account 1", openingBalance, "Account 1").apply {
            type = AccountType.BANK
            save()
        }
        val op = Transaction.getNewInstance(account.id) ?: throw IllegalStateException()
        op.amount = Money(account.currencyUnit, income2)
        op.catId = writeCategory("With/and:Sub", writeCategory("With/and:Main"))
        op.date = baseSinceEpoch
        op.save()

        try {
            expect.that(
                exportAll(
                    account,
                    ExportFormat.QIF,
                    notYetExportedP = false,
                    append = false,
                    withAccountColumn = false
                ).isSuccess
            ).isTrue()
            compare(linesQIF)
        } catch (e: IOException) {
            expect.withMessage("Could not export expenses. Error: ${e.message}").fail()
        }
    }

    @Test
    fun testExportNotYetExported() {
        val linesCSV = arrayOf(
            csvHeader(';', false),
            "\"\";\"" + date + "\";\"\";\"0\";\"1.00\";\"\";\"Expense inserted after first export\";\""
                    + context.getString(R.string.pm_cheque) + "\";\"\";\"3\";\"\";\"\"",
            "\"\";\"$date\";\"N.N.\";\"1.00\";\"0\";\"\";\"Income inserted after first export\";\"\";\"\";\"\";\"\";\"\""
        )
        val account = insertData1()
        expect.that(
            exportAll(
                account,
                ExportFormat.CSV,
                notYetExportedP = false,
                append = false,
                withAccountColumn = false
            ).isSuccess
        ).isTrue()
        account.markAsExported(null)
        outFile.delete()
        insertData2(account)
        expect.that(
            exportAll(
                account,
                ExportFormat.CSV,
                notYetExportedP = true,
                append = false,
                withAccountColumn = false
            ).isSuccess
        ).isTrue()
        compare(linesCSV)
    }

    @Test
    @Throws(IOException::class)
    fun testExportMultipleAccountsToOneFileCSV() {
        val (account1, account2) = insertData3()
        val linesCSV = arrayOf(
            csvHeader(';', true),
            "\"" + account1.label + "\";\"\";\"" + date + "\";\"\";\"0\";\"0.10\";\"\";\"\";\"" + context.getString(
                R.string.pm_cheque
            )
                    + "\";\"*\";\"1\";\"\";\"\"",
            "\"" + account2.label + "\";\"\";\"" + date + "\";\"\";\"0\";\"0.10\";\"\";\"\";\"" + context.getString(
                R.string.pm_cheque
            )
                    + "\";\"*\";\"1\";\"\";\"\""
        )
        expect.that(
            exportAll(
                account1,
                ExportFormat.CSV,
                notYetExportedP = false,
                append = false,
                withAccountColumn = true
            ).isSuccess
        ).isTrue()
        expect.that(
            exportAll(
                account2,
                ExportFormat.CSV,
                notYetExportedP = false,
                append = true,
                withAccountColumn = true
            ).isSuccess
        ).isTrue()
        compare(linesCSV)
    }

    @Test
    @Throws(IOException::class)
    fun testExportMultipleAccountsToOneFileQIF() {
        val (account1, account2) = insertData3()
        val linesQIF = arrayOf(
            "!Account",
            "NAccount 1",
            "TBank",
            "^",
            "!Type:Bank",
            "D$date",
            "T-0.10",
            "C*",
            "N1",
            "^",
            "!Account",
            "NAccount 2",
            "TCash",
            "^",
            "!Type:Cash",
            "D$date",
            "T-0.10",
            "C*",
            "N1",
            "^"
        )
        expect.that(
            exportAll(
                account1,
                ExportFormat.QIF,
                notYetExportedP = false,
                append = false,
                withAccountColumn = true
            ).isSuccess
        ).isTrue()
        expect.that(
            exportAll(
                account2,
                ExportFormat.QIF,
                notYetExportedP = false,
                append = true,
                withAccountColumn = true
            ).isSuccess
        ).isTrue()
        compare(linesQIF)
    }

    @Test
    fun testCategoryExporter() {
        val cat1Id = writeCategory("Main")
        writeCategory("Sub", cat1Id)
        val result = CategoryExporter.export(context, "UTF-8", lazyFile)
        result.onSuccess {
            compare(arrayOf("!Type:Cat", "NMain", "^", "NMain:Sub", "^"))
        }.onFailure {
            expect.fail()
        }
    }

    private fun compare(lines: Array<String>) {
        FileInputStream(outFile).use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var count = 0
                while (reader.readLine()?.also { line ->
                        //println("[$count]: $line")
                        expect.withMessage("Lines do not match").that(line).isEqualTo(lines[count])
                    } != null) {
                    count++
                }
                expect.that(count).isEqualTo(lines.size)
            }
        }
    }

    private fun csvHeader(separator: Char, withAccountColumn: Boolean): String {
        val sb = StringBuilder()
        val resArray = intArrayOf(
            R.string.split_transaction,
            R.string.date, R.string.payer_or_payee,
            R.string.income,
            R.string.expense,
            R.string.category,
            R.string.comment,
            R.string.method,
            R.string.status,
            R.string.reference_number,
            R.string.picture,
            R.string.tags
        )
        if (withAccountColumn) {
            sb.append('"').append(context.getString(R.string.account)).append('"').append(separator)
        }
        val iterator = resArray.iterator()
        while (iterator.hasNext()) {
            val res = iterator.next()
            sb.append('"').append(context.getString(res)).append('"')
            if (iterator.hasNext()) {
                sb.append(separator)
            }
        }
        return sb.toString()
    }

    @Throws(IOException::class)
    private fun exportAll(
        account: Account,
        format: ExportFormat,
        notYetExportedP: Boolean,
        append: Boolean,
        withAccountColumn: Boolean
    ): Result<*> {
        val exporter = if (format == ExportFormat.CSV) CsvExporter(
            account,
            null,
            notYetExportedP,
            "dd/MM/yyyy",
            '.',
            "UTF-8",
            !append,
            ';',
            withAccountColumn
        ) else QifExporter(account, null, notYetExportedP, "dd/MM/yyyy", '.', "UTF-8")
        return exporter.export(
            context,
            lazyFile,
            append
        )
    }

    private val lazyFile = lazy { Result.success(DocumentFile.fromFile(outFile)) }

    companion object {
        private const val FILE_NAME = "TEST"
    }
}