import type { EventSubscription } from 'expo';

import type { PairedDevice, StartResult, StateChangeEvent } from './BluetoothHid.types';
import NativeBluetoothHid from './BluetoothHidModule';

export type * from './BluetoothHid.types';

/** True only on Android 9+ devices that have Bluetooth. */
export const isAvailable: boolean = NativeBluetoothHid?.isSupported() ?? false;

export function start(): StartResult {
  return NativeBluetoothHid?.start() ?? 'unsupported';
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
