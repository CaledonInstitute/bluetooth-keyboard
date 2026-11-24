package expo.modules.bluetoothhid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Membuat HP berperan sebagai keyboard Bluetooth (profil HID Device, Android 9+).
 *
 * Izin BLUETOOTH_CONNECT harus sudah diberikan sebelum class ini dipakai.
 * Pengecekannya dilakukan di sisi JS.
 */
@RequiresApi(Build.VERSION_CODES.P)
@SuppressLint("MissingPermission")
class HidKeyboard(
  private val context: Context,
  private val onState: (status: String, device: BluetoothDevice?) -> Unit,
) {
  private val adapter: BluetoothAdapter? =
    context.getSystemService(BluetoothManager::class.java)?.adapter

  private val sendExecutor: ExecutorService = Executors.newSingleThreadExecutor()

  @Volatile private var hid: BluetoothHidDevice? = null
  @Volatile private var host: BluetoothDevice? = null
  @Volatile private var isRegistered = false
  @Volatile private var everRegistered = false
  @Volatile private var status = "stopped"

  private val callback = object : BluetoothHidDevice.Callback() {
    override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
      isRegistered = registered
      if (registered) {
        everRegistered = true
        update(if (host != null) "connected" else "ready", host)
      } else {
        host = null
        // Ditolak sebelum sempat terdaftar berarti HP memblokir profil ini, bukan sekadar berhenti.
        update(if (everRegistered) "stopped" else "failed", null)
      }
    }

    override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
      if (device == null) return
      when (state) {
        BluetoothProfile.STATE_CONNECTING -> update("connecting", device)
        BluetoothProfile.STATE_CONNECTED -> {
          host = device
          update("connected", device)
        }
        BluetoothProfile.STATE_DISCONNECTED -> {
          val current = host
          if (current == null || current.address == device.address) {
            host = null
            update(if (isRegistered) "ready" else "stopped", null)
          }
        }
      }
    }
  }

  private val serviceListener = object : BluetoothProfile.ServiceListener {
    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
      if (profile != BluetoothProfile.HID_DEVICE) return
      val device = proxy as BluetoothHidDevice
      hid = device

      val sdp = BluetoothHidDeviceAppSdpSettings(
        "Phone Keyboard",
        "Bluetooth keyboard",
        "Android",
        BluetoothHidDevice.SUBCLASS1_KEYBOARD,
        HidDescriptor.KEYBOARD,
      )
      val ok = device.registerApp(sdp, null, null, callbackExecutor, callback)
      if (!ok) update("failed", null)
    }

    override fun onServiceDisconnected(profile: Int) {
      if (profile != BluetoothProfile.HID_DEVICE) return
      hid = null
      host = null
      isRegistered = false
      update("stopped", null)
    }
  }

  private fun update(newStatus: String, device: BluetoothDevice?) {
    status = newStatus
    onState(newStatus, device)
  }

  /** Mengembalikan "ok", "no_bluetooth", "bluetooth_off", atau "failed". */
  fun start(): String {
    val bt = adapter ?: return "no_bluetooth"
    if (!bt.isEnabled) return "bluetooth_off"

    if (hid != null) {
      // Sudah berjalan, kirim ulang status terakhir ke JS.
      update(status, host)
      return "ok"
    }

    update("starting", null)
    val ok = bt.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
    if (!ok) {
      update("failed", null)
      return "failed"
    }
    return "ok"
  }

  fun stop() {
    val current = hid
    runCatching { host?.let { current?.disconnect(it) } }
    runCatching { current?.unregisterApp() }
    runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, current) }
    hid = null
    host = null
    isRegistered = false
    // callbackExecutor sengaja tidak di-shutdown: sistem masih bisa mengirim callback
    // setelah unregisterApp(), dan executor yang sudah mati akan membuat app crash.
    sendExecutor.shutdown()
  }

  /** Daftar perangkat yang sudah dipasangkan, dalam bentuk JSON array. */
  fun pairedDevicesJson(): String = bondedDevicesJson(adapter)

  fun connect(address: String): Boolean {
    val h = hid ?: return false
    val bt = adapter ?: return false
    if (!isRegistered) return false

    val target = try {
      bt.getRemoteDevice(address)
    } catch (e: IllegalArgumentException) {
      return false
    }

    host?.let { if (it.address != target.address) h.disconnect(it) }
    return h.connect(target)
  }

  fun disconnect() {
    val current = host ?: return
    hid?.disconnect(current)
  }

  /**
   * Menerima pasangan flat [modifier, usage, modifier, usage, ...].
   * Setiap pasangan dikirim sebagai tekan lalu lepas, berurutan di satu thread.
   */
  fun sendKeys(keys: IntArray) {
    val h = hid ?: return
    val target = host ?: return
    if (sendExecutor.isShutdown) return

    sendExecutor.execute {
      var i = 0
      while (i + 1 < keys.size) {
        val press = ByteArray(8)
        press[0] = keys[i].toByte()
        press[2] = keys[i + 1].toByte()

        h.sendReport(target, REPORT_ID, press)
        Thread.sleep(KEY_DELAY_MS)
        h.sendReport(target, REPORT_ID, ByteArray(8))
        Thread.sleep(KEY_DELAY_MS)
        i += 2
      }
    }
  }

  companion object {
    private const val REPORT_ID = HidDescriptor.REPORT_ID
    private const val KEY_DELAY_MS = 4L

    /** Dipakai bersama oleh semua instance dan tidak pernah dimatikan. Thread daemon, jadi tidak menahan proses. */
    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "bluetooth-hid-callback").apply { isDaemon = true }
    }
  }
}
