package expo.modules.bluetoothhid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Membuat HP berperan sebagai keyboard Bluetooth Low Energy (HID over GATT, HOGP).
 *
 * Dipakai sebagai cadangan kalau profil HID Device klasik tidak tersedia: Android 8 ke bawah,
 * atau HP yang memblokir profil itu. Tidak butuh Android 9, cukup Android 5 dengan dukungan
 * BLE peripheral. Laptop harus punya Bluetooth LE (Windows 10/11 sudah mendukung).
 *
 * Izin BLUETOOTH_CONNECT dan BLUETOOTH_ADVERTISE (Android 12+) harus sudah diberikan.
 */
@SuppressLint("MissingPermission")
class BleHidKeyboard(
  private val context: Context,
  private val onState: (status: String, device: BluetoothDevice?) -> Unit,
) {
  private val manager: BluetoothManager? =
    context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
  private val adapter: BluetoothAdapter? = manager?.adapter

  private val sendExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  private val pending = mutableListOf<BluetoothGattService>()

  @Volatile private var server: BluetoothGattServer? = null
  @Volatile private var advertiser: BluetoothLeAdvertiser? = null
  @Volatile private var inputReport: BluetoothGattCharacteristic? = null
  @Volatile private var outputReport: BluetoothGattCharacteristic? = null
  @Volatile private var host: BluetoothDevice? = null
  @Volatile private var subscribed = false
  @Volatile private var advertising = false
  @Volatile private var sentLatch: CountDownLatch? = null
  @Volatile private var currentStatus = "stopped"

  private val gattCallback = object : BluetoothGattServerCallback() {
    override fun onServiceAdded(gattStatus: Int, service: BluetoothGattService) {
      if (gattStatus != BluetoothGatt.GATT_SUCCESS || !addNextService()) {
        update("failed", null)
      }
    }

    override fun onConnectionStateChange(device: BluetoothDevice, gattStatus: Int, newState: Int) {
      when (newState) {
        BluetoothProfile.STATE_CONNECTED -> {
          host = device
          subscribed = false
          stopAdvertising()
          update("connecting", device)
        }
        BluetoothProfile.STATE_DISCONNECTED -> {
          val current = host
          if (current == null || current.address == device.address) {
            host = null
            subscribed = false
            update("ready", null)
            startAdvertising()
          }
        }
      }
    }

    override fun onCharacteristicReadRequest(
      device: BluetoothDevice,
      requestId: Int,
      offset: Int,
      characteristic: BluetoothGattCharacteristic,
    ) {
      respondToRead(device, requestId, offset, characteristicValue(characteristic))
    }

    override fun onDescriptorReadRequest(
      device: BluetoothDevice,
      requestId: Int,
      offset: Int,
      descriptor: BluetoothGattDescriptor,
    ) {
      val isInput = descriptor.characteristic === inputReport
      val value: ByteArray? = when (descriptor.uuid) {
        DESC_CCCD ->
          if (isInput && subscribed) byteArrayOf(0x01, 0x00) else byteArrayOf(0x00, 0x00)
        DESC_REPORT_REFERENCE ->
          byteArrayOf(HidDescriptor.REPORT_ID.toByte(), (if (isInput) 1 else 2).toByte())
        else -> null
      }
      respondToRead(device, requestId, offset, value)
    }

    override fun onCharacteristicWriteRequest(
      device: BluetoothDevice,
      requestId: Int,
      characteristic: BluetoothGattCharacteristic,
      preparedWrite: Boolean,
      responseNeeded: Boolean,
      offset: Int,
      value: ByteArray?,
    ) {
      // Protocol Mode, Control Point, dan LED report diterima saja, tidak ada yang perlu diproses.
      if (responseNeeded) {
        server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
      }
    }

    override fun onDescriptorWriteRequest(
      device: BluetoothDevice,
      requestId: Int,
      descriptor: BluetoothGattDescriptor,
      preparedWrite: Boolean,
      responseNeeded: Boolean,
      offset: Int,
      value: ByteArray?,
    ) {
      var result = BluetoothGatt.GATT_SUCCESS
      if (descriptor.uuid == DESC_CCCD) {
        if (descriptor.characteristic === inputReport) {
          val enabled = value != null && value.isNotEmpty() && (value[0].toInt() and 0x01) != 0
          if (host == null) host = device
          subscribed = enabled
          // Laptop baru dianggap terhubung setelah ia berlangganan notifikasi keyboard.
          update(if (enabled) "connected" else "connecting", host)
        }
      } else {
        result = BluetoothGatt.GATT_WRITE_NOT_PERMITTED
      }
      if (responseNeeded) {
        server?.sendResponse(device, requestId, result, offset, value)
      }
    }

    override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
      server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
    }

    override fun onNotificationSent(device: BluetoothDevice, gattStatus: Int) {
      sentLatch?.countDown()
    }
  }

  private val advertiseCallback = object : AdvertiseCallback() {
    override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
      if (host == null) update("ready", null)
    }

    override fun onStartFailure(errorCode: Int) {
      if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
        if (host == null) update("ready", null)
      } else {
        advertising = false
        update("failed", null)
      }
    }
  }

  private fun update(newStatus: String, device: BluetoothDevice?) {
    currentStatus = newStatus
    onState(newStatus, device)
  }

  /** Mengembalikan "ok", "no_bluetooth", "bluetooth_off", "unsupported", "permission", atau "failed". */
  fun start(): String {
    val bt = adapter ?: return "no_bluetooth"
    if (!bt.isEnabled) return "bluetooth_off"

    if (server != null) {
      // Sudah berjalan, kirim ulang status terakhir ke JS.
      update(currentStatus, host)
      return "ok"
    }

    return try {
      // Null kalau chipset HP tidak bisa menjadi BLE peripheral.
      val adv = bt.bluetoothLeAdvertiser ?: return "unsupported"
      val srv = manager?.openGattServer(context, gattCallback) ?: return "failed"
      advertiser = adv
      server = srv

      update("starting", null)
      synchronized(pending) {
        pending.clear()
        pending.addAll(buildServices())
      }
      if (addNextService()) {
        "ok"
      } else {
        stop()
        update("failed", null)
        "failed"
      }
    } catch (e: SecurityException) {
      stop()
      "permission"
    }
  }

  fun stop() {
    runCatching { stopAdvertising() }
    runCatching { server?.close() }
    server = null
    advertiser = null
    inputReport = null
    outputReport = null
    host = null
    subscribed = false
    sentLatch?.countDown()
    sendExecutor.shutdown()
  }

  /** Daftar perangkat yang sudah dipasangkan, dalam bentuk JSON array. */
  fun pairedDevicesJson(): String = bondedDevicesJson(adapter)

  /** Pada BLE, laptoplah yang biasanya menyambung. Ini hanya mencoba menyambung ke laptop yang sudah dipasangkan. */
  fun connect(address: String): Boolean {
    val srv = server ?: return false
    val bt = adapter ?: return false
    if (host != null) return false

    val target = try {
      bt.getRemoteDevice(address)
    } catch (e: IllegalArgumentException) {
      return false
    }
    return try {
      srv.connect(target, false)
    } catch (e: Exception) {
      false
    }
  }

  fun disconnect() {
    val current = host ?: return
    runCatching { server?.cancelConnection(current) }
  }

  /**
   * Menerima pasangan flat [modifier, usage, modifier, usage, ...].
   * Setiap pasangan dikirim sebagai tekan lalu lepas, berurutan di satu thread.
   */
  fun sendKeys(keys: IntArray) {
    if (host == null || !subscribed) return
    if (sendExecutor.isShutdown) return

    sendExecutor.execute {
      var i = 0
      while (i + 1 < keys.size) {
        val press = ByteArray(8)
        press[0] = keys[i].toByte()
        press[2] = keys[i + 1].toByte()

        if (!notifyReport(press)) return@execute
        if (!notifyReport(ByteArray(8))) return@execute
        i += 2
      }
    }
  }

  /** Kirim satu report lalu tunggu sistem selesai mengirimnya, karena notifikasi tidak boleh bertumpuk. */
  private fun notifyReport(report: ByteArray): Boolean {
    repeat(NOTIFY_RETRIES) {
      val srv = server ?: return false
      val characteristic = inputReport ?: return false
      val target = host ?: return false

      val latch = CountDownLatch(1)
      sentLatch = latch
      if (notifyCompat(srv, target, characteristic, report)) {
        latch.await(NOTIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return true
      }
      Thread.sleep(RETRY_DELAY_MS)
    }
    return false
  }

  @Suppress("DEPRECATION")
  private fun notifyCompat(
    srv: BluetoothGattServer,
    device: BluetoothDevice,
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,
  ): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      srv.notifyCharacteristicChanged(device, characteristic, false, value) ==
        BluetoothStatusCodes.SUCCESS
    } else {
      characteristic.value = value
      srv.notifyCharacteristicChanged(device, characteristic, false)
    }
  }

  private fun respondToRead(device: BluetoothDevice, requestId: Int, offset: Int, value: ByteArray?) {
    val srv = server ?: return
    when {
      value == null ->
        srv.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
      offset > value.size ->
        srv.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
      else ->
        srv.sendResponse(
          device,
          requestId,
          BluetoothGatt.GATT_SUCCESS,
          offset,
          value.copyOfRange(offset, value.size),
        )
    }
  }

  private fun characteristicValue(characteristic: BluetoothGattCharacteristic): ByteArray? = when {
    characteristic === inputReport -> ByteArray(8)
    characteristic === outputReport -> ByteArray(1)
    characteristic.uuid == CHAR_REPORT_MAP -> HidDescriptor.KEYBOARD
    characteristic.uuid == CHAR_HID_INFORMATION -> byteArrayOf(0x11, 0x01, 0x00, 0x02)
    characteristic.uuid == CHAR_PROTOCOL_MODE -> byteArrayOf(0x01)
    characteristic.uuid == CHAR_BATTERY_LEVEL -> byteArrayOf(100)
    characteristic.uuid == CHAR_PNP_ID -> byteArrayOf(0x02, 0x09, 0x12, 0x01, 0x00, 0x00, 0x01)
    characteristic.uuid == CHAR_MANUFACTURER_NAME -> "Android".toByteArray()
    else -> null
  }

  /** Service harus ditambahkan satu per satu: service berikutnya menunggu onServiceAdded. */
  private fun addNextService(): Boolean {
    val srv = server ?: return false
    val next = synchronized(pending) { if (pending.isEmpty()) null else pending.removeAt(0) }
    if (next == null) {
      startAdvertising()
      return true
    }
    return srv.addService(next)
  }

  private fun buildServices(): List<BluetoothGattService> {
    val read = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
    val write = BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
    val descRead = BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED
    val descWrite = BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED

    val battery = BluetoothGattService(SERVICE_BATTERY, BluetoothGattService.SERVICE_TYPE_PRIMARY)
    battery.addCharacteristic(
      BluetoothGattCharacteristic(
        CHAR_BATTERY_LEVEL,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ,
      ),
    )

    val info = BluetoothGattService(SERVICE_DEVICE_INFO, BluetoothGattService.SERVICE_TYPE_PRIMARY)
    info.addCharacteristic(
      BluetoothGattCharacteristic(
        CHAR_MANUFACTURER_NAME,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ,
      ),
    )
    info.addCharacteristic(
      BluetoothGattCharacteristic(
        CHAR_PNP_ID,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ,
      ),
    )

    val input = BluetoothGattCharacteristic(
      CHAR_REPORT,
      BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
      read,
    )
    input.addDescriptor(BluetoothGattDescriptor(DESC_CCCD, descRead or descWrite))
    input.addDescriptor(BluetoothGattDescriptor(DESC_REPORT_REFERENCE, descRead))

    val output = BluetoothGattCharacteristic(
      CHAR_REPORT,
      BluetoothGattCharacteristic.PROPERTY_READ or
        BluetoothGattCharacteristic.PROPERTY_WRITE or
        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
      read or write,
    )
    output.addDescriptor(BluetoothGattDescriptor(DESC_REPORT_REFERENCE, descRead))

    inputReport = input
    outputReport = output

    val hid = BluetoothGattService(SERVICE_HID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
    hid.addCharacteristic(
      BluetoothGattCharacteristic(
        CHAR_HID_INFORMATION,
        BluetoothGattCharacteristic.PROPERTY_READ,
        read,
      ),
    )
    hid.addCharacteristic(
      BluetoothGattCharacteristic(
        CHAR_REPORT_MAP,
        BluetoothGattCharacteristic.PROPERTY_READ,
        read,
      ),
    )
    hid.addCharacteristic(
      BluetoothGattCharacteristic(
        CHAR_CONTROL_POINT,
        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        write,
      ),
    )
    hid.addCharacteristic(
      BluetoothGattCharacteristic(
        CHAR_PROTOCOL_MODE,
        BluetoothGattCharacteristic.PROPERTY_READ or
          BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        read or write,
      ),
    )
    hid.addCharacteristic(input)
    hid.addCharacteristic(output)

    return listOf(battery, info, hid)
  }

  private fun startAdvertising() {
    val adv = advertiser ?: return
    if (advertising) return

    val settings = AdvertiseSettings.Builder()
      .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
      .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
      .setConnectable(true)
      .setTimeout(0)
      .build()
    // Paket iklan hanya 31 byte: UUID service HID di iklan, nama HP di scan response.
    val data = AdvertiseData.Builder()
      .setIncludeDeviceName(false)
      .addServiceUuid(ParcelUuid(SERVICE_HID))
      .build()
    val scanResponse = AdvertiseData.Builder()
      .setIncludeDeviceName(true)
      .build()

    advertising = true
    try {
      adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
    } catch (e: IllegalStateException) {
      advertising = false
      update("failed", null)
    }
  }

  private fun stopAdvertising() {
    if (!advertising) return
    advertising = false
    runCatching { advertiser?.stopAdvertising(advertiseCallback) }
  }

  companion object {
    private const val NOTIFY_TIMEOUT_MS = 100L
    private const val NOTIFY_RETRIES = 5
    private const val RETRY_DELAY_MS = 5L

    private fun uuid16(value: Int): UUID =
      UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", value))

    private val SERVICE_HID = uuid16(0x1812)
    private val SERVICE_BATTERY = uuid16(0x180F)
    private val SERVICE_DEVICE_INFO = uuid16(0x180A)

    private val CHAR_HID_INFORMATION = uuid16(0x2A4A)
    private val CHAR_REPORT_MAP = uuid16(0x2A4B)
    private val CHAR_CONTROL_POINT = uuid16(0x2A4C)
    private val CHAR_REPORT = uuid16(0x2A4D)
    private val CHAR_PROTOCOL_MODE = uuid16(0x2A4E)
    private val CHAR_BATTERY_LEVEL = uuid16(0x2A19)
    private val CHAR_MANUFACTURER_NAME = uuid16(0x2A29)
    private val CHAR_PNP_ID = uuid16(0x2A50)

    private val DESC_CCCD = uuid16(0x2902)
    private val DESC_REPORT_REFERENCE = uuid16(0x2908)
  }
}
