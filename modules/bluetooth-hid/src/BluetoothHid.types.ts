export type HidStatus = 'stopped' | 'starting' | 'ready' | 'connecting' | 'connected' | 'failed';

export type StartResult = 'ok' | 'unsupported' | 'no_bluetooth' | 'bluetooth_off' | 'failed';

export type StateChangeEvent = {
  status: HidStatus;
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
