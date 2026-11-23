import { NativeModule, requireOptionalNativeModule } from 'expo';

import { BluetoothHidModuleEvents, StartResult } from './BluetoothHid.types';

declare class BluetoothHidModule extends NativeModule<BluetoothHidModuleEvents> {
  isSupported(): boolean;
  start(): StartResult;
  stop(): void;
  /** JSON array of PairedDevice. */
  getPairedDevices(): string;
  connect(address: string): boolean;
  disconnect(): void;
  /** Flat pairs: [modifier, usage, modifier, usage, ...]. */
  sendKeys(keys: number[]): void;
  requestDiscoverable(): boolean;
}

// null on iOS and web, where the native module does not exist.
export default requireOptionalNativeModule<BluetoothHidModule>('BluetoothHid');
