package expo.modules.bluetoothhid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import org.json.JSONArray
import org.json.JSONObject

/** Dipakai bersama oleh mode HID klasik (HidKeyboard) dan mode BLE (BleHidKeyboard). */
internal object HidDescriptor {
  const val REPORT_ID = 1

  private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

  /** HID report descriptor keyboard standar (report ID 1, 8 byte input, 1 byte LED output). */
  val KEYBOARD: ByteArray = bytes(
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x06,       // Usage (Keyboard)
    0xA1, 0x01,       // Collection (Application)
    0x85, REPORT_ID,  //   Report ID
    0x05, 0x07,       //   Usage Page (Key Codes)
    0x19, 0xE0,       //   Usage Minimum (Left Control)
    0x29, 0xE7,       //   Usage Maximum (Right GUI)
    0x15, 0x00,       //   Logical Minimum (0)
    0x25, 0x01,       //   Logical Maximum (1)
    0x75, 0x01,       //   Report Size (1)
    0x95, 0x08,       //   Report Count (8)
    0x81, 0x02,       //   Input (Data, Variable, Absolute): modifier byte
    0x95, 0x01,       //   Report Count (1)
    0x75, 0x08,       //   Report Size (8)
    0x81, 0x01,       //   Input (Constant): reserved byte
    0x95, 0x05,       //   Report Count (5)
    0x75, 0x01,       //   Report Size (1)
    0x05, 0x08,       //   Usage Page (LEDs)
    0x19, 0x01,       //   Usage Minimum (Num Lock)
    0x29, 0x05,       //   Usage Maximum (Kana)
    0x91, 0x02,       //   Output (Data, Variable, Absolute): LED report
    0x95, 0x01,       //   Report Count (1)
    0x75, 0x03,       //   Report Size (3)
    0x91, 0x01,       //   Output (Constant): LED padding
    0x95, 0x06,       //   Report Count (6)
    0x75, 0x08,       //   Report Size (8)
    0x15, 0x00,       //   Logical Minimum (0)
    0x25, 0x65,       //   Logical Maximum (101)
    0x05, 0x07,       //   Usage Page (Key Codes)
    0x19, 0x00,       //   Usage Minimum (0)
    0x29, 0x65,       //   Usage Maximum (101)
    0x81, 0x00,       //   Input (Data, Array): 6 key slots
    0xC0,             // End Collection
  )
}

/** Daftar perangkat yang sudah dipasangkan, dalam bentuk JSON array. */
@SuppressLint("MissingPermission")
internal fun bondedDevicesJson(adapter: BluetoothAdapter?): String {
  val result = JSONArray()
  val bonded: Set<BluetoothDevice> = try {
    adapter?.bondedDevices ?: emptySet()
  } catch (e: SecurityException) {
    emptySet()
  }

  for (device in bonded) {
    val isComputer =
      device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER
    result.put(
      JSONObject()
        .put("name", device.name ?: device.address)
        .put("address", device.address)
        .put("isComputer", isComputer),
    )
  }
  return result.toString()
}
