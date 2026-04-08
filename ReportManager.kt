package com.arus.app.core.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.arus.app.core.database.TierManager
import com.arus.app.core.database.TransactionModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class ReportFormat {
    PDF, CSV
}

enum class ReportPeriod(val title: String) {
    DAILY("Harian"),
    MONTHLY("Bulanan"),
    YEARLY("Tahunan")
}

object ReportManager {
    private const val TAG = "ARUS_REPORT"

    private suspend fun clearOldReports(context: Context) = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "reports")
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    suspend fun filterTransactionsByPeriod(
        transactions: List<TransactionModel>,
        period: ReportPeriod
    ): List<TransactionModel> = withContext(Dispatchers.Default) {
        val calendar = Calendar.getInstance()

        return@withContext transactions.filter { tx ->
            val txCal = Calendar.getInstance().apply { timeInMillis = tx.timestamp }
            when (period) {
                ReportPeriod.DAILY -> {
                    txCal.get(Calendar.YEAR) == calendar.get(Calendar.YEAR) &&
                            txCal.get(Calendar.DAY_OF_YEAR) == calendar.get(Calendar.DAY_OF_YEAR)
                }
                ReportPeriod.MONTHLY -> {
                    txCal.get(Calendar.YEAR) == calendar.get(Calendar.YEAR) &&
                            txCal.get(Calendar.MONTH) == calendar.get(Calendar.MONTH)
                }
                ReportPeriod.YEARLY -> {
                    txCal.get(Calendar.YEAR) == calendar.get(Calendar.YEAR)
                }
            }
        }
    }

    suspend fun generateFinancialReport(
        context: Context,
        transactions: List<TransactionModel>,
        period: ReportPeriod,
        format: ReportFormat,
        businessName: String = "Toko Arus"
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val displayName = TierManager.getOwnerName(context)
            if (!TierManager.isPlusUser(context)) {
                return@withContext Result.failure(Exception("Fitur Laporan khusus pelanggan Plus, $displayName. Yuk upgrade!"))
            }

            if (transactions.isEmpty()) {
                return@withContext Result.failure(Exception("Tidak ada data transaksi untuk diolah."))
            }

            val auditData = withContext(Dispatchers.Default) {
                val omzet = transactions.sumOf { it.totalAmount }
                val profit = transactions.sumOf { it.totalProfit }
                val modal = omzet - profit
                Triple(omzet, modal, profit)
            }

            clearOldReports(context)
            val exportDir = File(context.cacheDir, "reports").apply { if (!exists()) mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            val fileName = "Arus_Laporan_${period.name}_$timestamp"

            val uri = when (format) {
                ReportFormat.CSV -> createCsvReport(context, exportDir, fileName, transactions, auditData)
                ReportFormat.PDF -> createPdfReport(context, exportDir, fileName, businessName, period, auditData, transactions)
            }

            Result.success(uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun createCsvReport(
        context: Context, dir: File, fileName: String,
        txs: List<TransactionModel>, audit: Triple<Long, Long, Long>
    ): Uri = withContext(Dispatchers.IO) {
        val file = File(dir, "$fileName.csv")
        FileWriter(file).use { writer ->
            writer.append("LAPORAN KEUANGAN ARUS\n")
            writer.append("Total Omzet,Rp ${audit.first}\n")
            writer.append("Total Modal,Rp ${audit.second}\n")
            writer.append("Profit Bersih,Rp ${audit.third}\n\n")
            writer.append("ID Transaksi,Waktu,Items,Omzet (Rp),Profit (Rp)\n")

            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            txs.forEach { tx ->
                writer.append("\"${tx.id}\",\"${sdf.format(Date(tx.timestamp))}\",${tx.itemCount},${tx.totalAmount},${tx.totalProfit}\n")
            }
        }
        return@withContext FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private suspend fun createPdfReport(
        context: Context, dir: File, fileName: String, shopName: String,
        period: ReportPeriod, audit: Triple<Long, Long, Long>, txs: List<TransactionModel>
    ): Uri = withContext(Dispatchers.IO) {
        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // Ukuran standar A4
        var page = pdf.startPage(pageInfo)
        var canvas: Canvas = page.canvas
        val paint = Paint()

        // --- Styles ---
        val titlePaint = Paint().apply { typeface = Typeface.DEFAULT_BOLD; textSize = 22f; color = android.graphics.Color.parseColor("#064869") }
        val textPaint = Paint().apply { textSize = 10f; isAntiAlias = true }
        val headerTablePaint = Paint().apply { typeface = Typeface.DEFAULT_BOLD; textSize = 11f; color = android.graphics.Color.WHITE }

        // --- Helper Fungsi untuk Menggambar Header Tabel ---
        fun drawTableHeader(currentY: Float) {
            paint.color = android.graphics.Color.parseColor("#064869")
            canvas.drawRect(40f, currentY, 555f, currentY + 25f, paint)
            canvas.drawText("Waktu", 50f, currentY + 17f, headerTablePaint)
            canvas.drawText("Item", 180f, currentY + 17f, headerTablePaint)
            canvas.drawText("Omzet", 300f, currentY + 17f, headerTablePaint)
            canvas.drawText("Profit", 450f, currentY + 17f, headerTablePaint)
        }

        var y = 60f
        canvas.drawText(shopName.uppercase(), 40f, y, titlePaint)
        y += 20f
        canvas.drawText("Laporan Keuangan ${period.title}", 40f, y, Paint().apply { textSize = 14f; color = android.graphics.Color.GRAY })

        y += 40f
        paint.color = android.graphics.Color.parseColor("#F4F9FB")
        canvas.drawRoundRect(40f, y, 555f, y + 70f, 10f, 10f, paint)
        canvas.drawText("RINGKASAN PERFORMA", 60f, y + 25f, Paint().apply { typeface = Typeface.DEFAULT_BOLD; textSize = 10f; color = android.graphics.Color.parseColor("#2C95BA") })
        canvas.drawText("Total Omzet: ${formatRp(audit.first)}", 60f, y + 45f, textPaint)
        canvas.drawText("Total Modal: ${formatRp(audit.second)}", 220f, y + 45f, textPaint)
        canvas.drawText("PROFIT: ${formatRp(audit.third)}", 380f, y + 45f, Paint().apply { typeface = Typeface.DEFAULT_BOLD; textSize = 12f; color = android.graphics.Color.parseColor("#2E7D32") })

        y += 100f
        drawTableHeader(y)

        val rowSdf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        y += 45f

        txs.forEach { tx ->
            if (y > 780f) {
                pdf.finishPage(page)
                page = pdf.startPage(pageInfo)
                canvas = page.canvas

                y = 60f
                drawTableHeader(y)
                y += 45f
            }

            canvas.drawText(rowSdf.format(Date(tx.timestamp)), 50f, y, textPaint)
            canvas.drawText("${tx.itemCount}", 180f, y, textPaint)
            canvas.drawText(formatRp(tx.totalAmount), 300f, y, textPaint)
            canvas.drawText(formatRp(tx.totalProfit), 450f, y, Paint().apply { textSize = 10f; color = android.graphics.Color.parseColor("#2E7D32") })
            canvas.drawLine(40f, y + 5f, 555f, y + 5f, Paint().apply { color = android.graphics.Color.LTGRAY; strokeWidth = 0.5f })
            y += 20f
        }

        pdf.finishPage(page)

        val file = File(dir, "$fileName.pdf")
        pdf.writeTo(FileOutputStream(file))
        pdf.close()

        return@withContext FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun formatRp(amt: Long) = NumberFormat.getCurrencyInstance(Locale("id", "ID")).apply { maximumFractionDigits = 0 }.format(amt).replace("Rp", "Rp ")
}