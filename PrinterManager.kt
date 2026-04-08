package com.arus.app.core.hardware

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@SuppressLint("MissingPermission")
class PrinterManager(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _isBluetoothEnabled = MutableStateFlow(bluetoothAdapter?.isEnabled == true)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                _isBluetoothEnabled.value = state == BluetoothAdapter.STATE_ON
                if (state == BluetoothAdapter.STATE_OFF) {
                    disconnect() 
                }
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(bluetoothStateReceiver, filter)
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private val INIT = byteArrayOf(0x1B, 0x40) // Initialize
        private val ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00)
        private val ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
        private val ALIGN_RIGHT = byteArrayOf(0x1B, 0x61, 0x02)
        private val BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01)
        private val BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)
        private val DOUBLE_HEIGHT = byteArrayOf(0x1B, 0x21, 0x10) // Text size * 2
        private val NORMAL_SIZE = byteArrayOf(0x1B, 0x21, 0x00) // Reset text size
        private val NEW_LINE = byteArrayOf(0x0A)
        private val DASH_LINE = "--------------------------------\n".toByteArray()
        private val EQUAL_LINE = "================================\n".toByteArray()
    }

    fun checkBluetoothState() {
        _isBluetoothEnabled.value = bluetoothAdapter?.isEnabled == true
    }

    fun getPairedPrinters(): List<BluetoothDevice> {
        if (bluetoothAdapter?.isEnabled == false) return emptyList()

        return try {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun connect(deviceAddress: String): Boolean {
        return withContext(Dispatchers.IO) {
            if (bluetoothAdapter?.isEnabled == false) return@withContext false
            if (_isConnected.value && bluetoothSocket?.remoteDevice?.address == deviceAddress) return@withContext true

            disconnect()
            bluetoothAdapter?.cancelDiscovery() // WAJIB! Biar koneksi lebih cepat

            val device = try {
                bluetoothAdapter?.getRemoteDevice(deviceAddress)
            } catch (e: Exception) { return@withContext false }

            if (device == null) return@withContext false

            try {
                bluetoothSocket = device.javaClass.getMethod("createInsecureRfcommSocketToServiceRecord", UUID::class.java).invoke(device, SPP_UUID) as BluetoothSocket?
                bluetoothSocket?.connect()
            } catch (e1: Exception) {
                try {
                    bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    bluetoothSocket?.connect()
                } catch (e2: Exception) {
                    try {
                        bluetoothSocket = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket?
                        bluetoothSocket?.connect()
                    } catch (e3: Exception) {
                        disconnect()
                        return@withContext false
                    }
                }
            }

            try {
                outputStream = bluetoothSocket?.outputStream
                _isConnected.value = true
                _connectedDeviceName.value = device.name ?: "Printer Kasir"
                return@withContext true
            } catch (e: IOException) {
                disconnect()
                return@withContext false
            }
        }
    }

    suspend fun printReceipt(
        storeName: String,
        items: List<Triple<String, String, String>>,
        total: String,
        tier: String = "ARUS_ONE",
        customFooter: String? = null,
        logoBitmap: Bitmap? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val os = outputStream ?: throw IOException("OutputStream Null")
                if (!_isConnected.value) throw IOException("Printer tidak terkoneksi")

                val isProTier = tier == "ARUS_PLUS" || tier == "TRIAL"

                os.write(INIT)
                os.write(ALIGN_CENTER)

                if (isProTier && logoBitmap != null) {
                    printBitmap(logoBitmap, os)
                    os.write(NEW_LINE)
                }

                os.write(BOLD_ON)
                if (isProTier) os.write(DOUBLE_HEIGHT)
                os.write("$storeName\n".toByteArray())
                os.write(NORMAL_SIZE)
                os.write(BOLD_OFF)

                val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("id", "ID"))
                val dateTime = sdf.format(Date())
                os.write("$dateTime\n".toByteArray())
                os.write(NEW_LINE)

                os.write(ALIGN_LEFT)
                os.write(if (isProTier) EQUAL_LINE else DASH_LINE)

                items.forEach { (name, qtyAtPrice, subTotal) ->
                    val safeName = if (name.length > 32) name.substring(0, 32) else name
                    os.write("$safeName\n".toByteArray())
                    val spaceCount = 32 - (qtyAtPrice.length + subTotal.length)
                    val spaces = if (spaceCount > 0) " ".repeat(spaceCount) else " "
                    os.write("$qtyAtPrice$spaces$subTotal\n".toByteArray())
                }

                os.write(if (isProTier) EQUAL_LINE else DASH_LINE)

                os.write(ALIGN_RIGHT)
                os.write(BOLD_ON)
                os.write("TOTAL: Rp $total\n".toByteArray())
                os.write(BOLD_OFF)
                os.write(NEW_LINE)

                os.write(ALIGN_CENTER)
                if (isProTier && !customFooter.isNullOrBlank()) {
                    os.write(customFooter.toByteArray())
                    os.write(NEW_LINE)
                } else {
                    os.write("Terima Kasih!\n".toByteArray())
                }

                if (!isProTier) {
                    os.write(NEW_LINE)
                    os.write("Powered by Arus - Kasir Pintar\n".toByteArray())
                }

                os.write(NEW_LINE)
                os.write(NEW_LINE)
                os.write(NEW_LINE)

                os.flush()
                true
            } catch (e: IOException) {

                disconnect()
                false
            } catch (e: Exception) {
                disconnect()
                false
            }
        }
    }

    fun vectorToBitmap(drawableId: Int, widthPx: Int = 384): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, drawableId) ?: return null
        val aspectRatio = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight.toFloat()
        val heightPx = (widthPx / aspectRatio).toInt()

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun printBitmap(bitmap: Bitmap, outputStream: OutputStream) {
        val targetWidth = 384
        val scale = targetWidth.toFloat() / bitmap.width
        val targetHeight = (bitmap.height * scale).toInt()
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)

        val width = scaledBitmap.width
        val height = scaledBitmap.height

        val xL = (width / 8 % 256).toByte()
        val xH = (width / 8 / 256).toByte()
        val yL = (height % 256).toByte()
        val yH = (height / 256).toByte()

        val command = byteArrayOf(0x1D, 0x76, 0x30, 0x00, xL, xH, yL, yH)
        outputStream.write(command)

        val pixels = IntArray(width * height)
        scaledBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val imageData = ByteArray((width / 8) * height)
        var index = 0

        for (y in 0 until height) {
            for (x in 0 until width step 8) {
                var byte: Byte = 0
                for (b in 0..7) {
                    if (x + b < width) {
                        val color = pixels[y * width + x + b]
                        val r = (color shr 16) and 0xFF
                        val g = (color shr 8) and 0xFF
                        val bColor = color and 0xFF

                        val luminance = (0.299 * r + 0.587 * g + 0.114 * bColor).toInt()
                        if (luminance < 128) {
                            byte = (byte.toInt() or (1 shl (7 - b))).toByte()
                        }
                    }
                }
                imageData[index++] = byte
            }
        }
        outputStream.write(imageData)
        scaledBitmap.recycle()
    }

    fun disconnect() {
        try {
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (e: Exception) {
            // Abaikan error saat menutup socket
        } finally {
            outputStream = null
            bluetoothSocket = null
            _isConnected.value = false
            _connectedDeviceName.value = null
        }
    }

    fun release() {
        try {
            context.unregisterReceiver(bluetoothStateReceiver)
        } catch (e: Exception) {}
        disconnect()
    }
}