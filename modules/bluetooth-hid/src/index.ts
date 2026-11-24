import type { EventSubscription } from 'expo';

import type { PairedDevice, StartMode, StartResult, StateChangeEvent } from './BluetoothHid.types';
import NativeBluetoothHid from './BluetoothHidModule';

export type * from './BluetoothHid.types';

/** False when the native module is not in the build, for example when running in Expo Go. */
export const isModuleLoaded: boolean = NativeBluetoothHid != null;

/** True on devices that have Bluetooth and support classic HID (Android 9+) or BLE peripheral. */
export const isAvailable: boolean = NativeBluetoothHid?.isSupported() ?? false;

export function start(mode: StartMode = 'auto'): StartResult {
  return NativeBluetoothHid?.start(mode) ?? 'unsupported';
}

export function stop(): void {
  NativeBluetoothHid?.stop();
}

export function addStateListener(listener: (event: StateChangeEvent) => void): EventSubscription {
  return (
    NativeBluetoothHid?.addListener('onStateChange', listener) ?? {
      remove() {},
    }
  );
}

export function getPairedDevices(): PairedDevice[] {
  try {
    return JSON.parse(NativeBluetoothHid?.getPairedDevices() ?? '[]') as PairedDevice[];
  } catch {
    return [];
  }
}

export function connect(address: string): boolean {
  return NativeBluetoothHid?.connect(address) ?? false;
}

export function disconnect(): void {
  NativeBluetoothHid?.disconnect();
}

export function sendKeys(keys: number[]): void {
  if (keys.length > 0) NativeBluetoothHid?.sendKeys(keys);
}

/** Opens the system dialog that makes the phone visible to the laptop for pairing. */
export function requestDiscoverable(): boolean {
  return NativeBluetoothHid?.requestDiscoverable() ?? false;
}
