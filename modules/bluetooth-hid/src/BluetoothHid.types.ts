export type HidStatus = 'stopped' | 'starting' | 'ready' | 'connecting' | 'connected' | 'failed';

export type StartResult =
  | 'ok'
  | 'unsupported'
  | 'no_bluetooth'
  | 'bluetooth_off'
  | 'permission'
  | 'failed';

/** Cara HP tampil sebagai keyboard: HID klasik (Android 9+) atau BLE (HID over GATT). */
export type HidTransport = 'classic' | 'ble';

/** auto: coba klasik dulu, pindah ke BLE kalau gagal. */
export type StartMode = 'auto' | 'classic' | 'ble';

export type StateChangeEvent = {
  status: HidStatus;
  transport: HidTransport;
  deviceName: string | null;
  deviceAddress: string | null;
};

export type PairedDevice = {
  name: string;
  address: string;
  isComputer: boolean;
};

export type BluetoothHidModuleEvents = {
  onStateChange: (event: StateChangeEvent) => void;
};
