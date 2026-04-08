package com.arus.app.core.utils

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.arus.app.core.database.TransactionModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExportManager {
    private const val TAG = "ARUS_EXPORT"

    private suspend fun clearOldExports(context: Context) = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports")
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    suspend fun exportTransactionsToCSV(
        context: Context,
        transactions: List<TransactionModel>
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            clearOldExports(context)

            val exportDir = File(context.cacheDir, "exports")
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale("id", "ID")).format(Date())
            val fileName = "Arus_Jurnal_$timestamp.csv"
            val file = File(exportDir, fileName)

            FileWriter(file).use { writer ->
                writer.append("ID Transaksi,Tanggal & Waktu,Jumlah Item,Total Harga (Rp),Total Profit (Rp),Metode Pembayaran\n")

                val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("id", "ID"))

                transactions.forEach { tx ->

                    writer.append("\"${tx.id}\",\"${dateStr}\",${tx.itemCount},${tx.totalAmount},${tx.totalProfit},\"${tx.paymentMethod}\"\n")
                }
                writer.flush()
            }

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}