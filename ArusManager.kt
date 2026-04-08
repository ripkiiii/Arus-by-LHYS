package com.arus.app.core.ai

import com.arus.app.BuildConfig
import com.arus.app.core.database.Product
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object ArusManager {

    private const val AI_TIMEOUT_MS = 15000L

    private val textModel by lazy {
        GenerativeModel(
            modelName = "gemini-2.5-flash",
            apiKey = BuildConfig.GEMINI_API_KEY,
            systemInstruction = content {
                text(
                    """
                    Nama kamu adalah Coco. Kamu adalah asisten AI dan sparring partner bisnis untuk pemilik UMKM di aplikasi Arus.
                    Gaya bahasamu: Otentik, cerdas, membumi, empatik, dan tidak menggunakan jargon IT yang rumit.
                    Gunakan gaya bicara profesional tapi santai, layaknya konsultan bisnis jalanan yang berpengalaman.
                    Jangan pernah gunakan format markdown tebal (**text**) atau miring, berikan teks murni (plain text).
                    Tugasmu:
                    1. Jawab pertanyaan dengan singkat, padat, dan langsung ke inti (Industrial Clean).
                    2. Berikan teguran empatik tapi tegas jika ada stok yang menipis atau keputusan bisnis yang salah.
                    3. Berikan ide bisnis yang taktis dan sangat bisa langsung dikerjakan (actionable).
                    """.trimIndent()
                )
            }
        )
    }

    private val jsonModel by lazy {
        GenerativeModel(
            modelName = "gemini-2.5-flash",
            apiKey = BuildConfig.GEMINI_API_KEY,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
            }
        )
    }

    suspend fun askCoco(question: String, contextData: List<Product>): String {
        return withContext(Dispatchers.IO) {
            try {
                val criticalItems = contextData.sortedBy { it.stock }.take(30)

                val inventoryContext = if (criticalItems.isEmpty()) {
                    "Tidak ada data barang yang relevan dengan pertanyaan ini."
                } else {
                    criticalItems.joinToString("\n") { "- ${it.name}: Harga Rp${it.price} (Stok: ${it.stock})" }
                }

                val prompt = """
                    Berikut adalah data sebagian Gudang/Toko saya yang relevan (Fokus pada stok kritis):
                    $inventoryContext
                    
                    Pertanyaan/Perintah saya:
                    "$question"
                    
                    Tugas: Analisa dan jawab berdasarkan data di atas. Jika stok kurang dari 5, sarankan untuk restock. Jawab dengan gaya bahasamu yang natural.
                """.trimIndent()

                val response = withTimeoutOrNull(AI_TIMEOUT_MS) {
                    textModel.generateContent(prompt)
                }

                val cleanResponse = response?.text?.replace("**", "")?.replace("_", "")?.trim()
                cleanResponse ?: "Sinyal lagi jelek nih Bos, Coco butuh waktu lebih buat mikir. Coba lagi ya!"
            } catch (e: Exception) {
                "Terjadi kendala koneksi ke server Coco. Coba lagi dalam beberapa saat."
            }
        }
    }

    suspend fun parseVoiceCommand(transcription: String, inventoryNames: List<String>): String {
        return withContext(Dispatchers.IO) {
            try {
                val availableItems = inventoryNames.joinToString(", ")

                val prompt = """
                    Saya adalah kasir. Barang di toko saya: [$availableItems].
                    Pelanggan berkata: "$transcription".
                    
                    Tugas: Ekstrak barang dan jumlah yang ingin dibeli berdasarkan ucapan pelanggan.
                    ATURAN MUTLAK:
                    1. Balas HANYA dengan format JSON List of Objects murni. 
                    2. Skema WAJIB: [{"nama_barang": "Nama Barang Sesuai Daftar", "jumlah": 2}]
                    3. Jika nama barang dari pelanggan tidak ada di daftar, ambigu, atau pesanan tidak jelas, balas dengan array kosong: []
                """.trimIndent()

                val response = withTimeoutOrNull(AI_TIMEOUT_MS) {
                    jsonModel.generateContent(prompt)
                } ?: return@withContext "[]"

                val jsonText = response.text?.trim() ?: "[]"

                if (jsonText.startsWith("{")) {
                    "[$jsonText]"
                } else if (!jsonText.startsWith("[")) {
                    "[]"
                } else {
                    jsonText
                }
            } catch (e: Exception) {
                "[]"
            }
        }
    }

    suspend fun parseVoiceExpense(transcription: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val prompt = """
                    Pemilik toko berkata: "$transcription".
                    
                    Tugas: Ekstrak catatan pengeluaran operasional dan nominal harganya (dalam angka murni).
                    ATURAN MUTLAK:
                    1. Balas HANYA dengan format JSON Object murni.
                    2. Skema WAJIB: {"catatan": "Beli Galon", "nominal": 20000}
                    3. Jika ucapan tidak mengandung unsur pengeluaran uang atau nominal tidak terdeteksi, balas dengan empty object: {}
                """.trimIndent()

                val response = withTimeoutOrNull(AI_TIMEOUT_MS) {
                    jsonModel.generateContent(prompt)
                } ?: return@withContext "{}"

                val jsonText = response.text?.trim() ?: "{}"

                if (!jsonText.startsWith("{")) {
                    "{}"
                } else {
                    jsonText
                }
            } catch (e: Exception) {
                "{}"
            }
        }
    }

    suspend fun generateBusinessAdvice(ownerName: String, salesSummary: String): String {
        return withContext(Dispatchers.IO) {
            try {
                if (salesSummary.isBlank() || salesSummary == "Belum ada penjualan") {
                    return@withContext "Belum ada data jualan hari ini. Tetap semangat, rezeki pasti ada jalannya!"
                }

                val prompt = """
                    Berikut adalah ringkasan penjualan toko saya hari ini:
                    $salesSummary
                    
                    Tugas: 
                    Berikan 1 saran bisnis (maksimal 2 kalimat) berdasarkan data di atas.
                    Gunakan sapaan '$ownerName' kepada saya secara dinamis di awal atau akhir kalimat.
                    Saran harus taktis, misalnya menyarankan paket bundling, promosi jam tertentu, atau evaluasi produk yang kurang laku.
                    PENTING: Balas dengan teks murni, tanpa ada tanda bintang (**) atau format markdown lainnya.
                """.trimIndent()

                val response = withTimeoutOrNull(AI_TIMEOUT_MS) {
                    textModel.generateContent(prompt)
                } ?: return@withContext "Koneksi ke server Coco AI sedang lambat, coba lagi nanti."

                val cleanResponse = response.text?.replace("**", "")?.replace("_", "")?.trim()
                    ?: "Wah, sistem AI lagi istirahat sebentar. Lanjut jualan dulu ya!"

                cleanResponse
            } catch (e: Exception) {
                "Koneksi ke server Coco AI sedang lambat, coba lagi nanti."
            }
        }
    }
}