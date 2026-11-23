import { useCallback, useEffect, useRef, useState } from 'react';
import {
  AppState,
  KeyboardAvoidingView,
  PermissionsAndroid,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  TextInput,
  View,
  type TextInputKeyPressEvent,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import * as BluetoothHid from '../../modules/bluetooth-hid';
import { ThemedText } from '@/components/themed-text';
import { ThemedView } from '@/components/themed-view';
import { Spacing } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';
import { diffToKeys, KEY_BACKSPACE, MOD_NONE } from '@/lib/hid-keymap';

const ACCENT = '#208AEF';

/** The text box is only a buffer. It is cleared after Enter and when it grows past this. */
const MAX_BUFFER = 500;

const STATUS_LABEL: Record<BluetoothHid.HidStatus, string> = {
  stopped: 'Belum aktif',
  starting: 'Menyiapkan Bluetooth',
  ready: 'Siap, belum terhubung ke laptop',
  connecting: 'Menghubungkan',
  connected: 'Terhubung',
  failed: 'Mode keyboard gagal aktif',
};

const STATUS_COLOR: Record<BluetoothHid.HidStatus, string> = {
  stopped: '#8A8F98',
  starting: '#E5A100',
  ready: '#E5A100',
  connecting: '#E5A100',
  connected: '#2BA84A',
  failed: '#D93636',
};

const ERRORS = {
  unsupported: 'HP ini tidak mendukung mode keyboard Bluetooth. Dibutuhkan Android 9 atau lebih baru.',
  no_bluetooth: 'Bluetooth tidak tersedia di perangkat ini.',
  bluetooth_off: 'Bluetooth mati. Nyalakan Bluetooth, lalu ketuk Coba lagi.',
  failed:
    'Mode keyboard gagal diaktifkan. Sebagian HP memblokir fitur ini, atau aplikasi lain sedang memakainya.',
  permission:
    'Izin Perangkat sekitar ditolak. Aktifkan lewat Pengaturan > Aplikasi > Izin, lalu ketuk Coba lagi.',
  connect: 'Tidak bisa memulai koneksi. Pastikan Bluetooth laptop menyala dan laptop sudah dipasangkan.',
  discoverable: 'Tidak bisa membuka pengaturan visibilitas Bluetooth.',
} as const;

export default function KeyboardScreen() {
  const theme = useTheme();
  const [status, setStatus] = useState<BluetoothHid.HidStatus>('stopped');
  const [deviceName, setDeviceName] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(
    BluetoothHid.isAvailable ? null : ERRORS.unsupported,
  );
  const [devices, setDevices] = useState<BluetoothHid.PairedDevice[]>([]);
  const [text, setText] = useState('');
  const sentText = useRef('');

  const connected = status === 'connected';

  const refreshDevices = useCallback(() => {
    const list = BluetoothHid.getPairedDevices();
    list.sort(
      (a, b) => Number(b.isComputer) - Number(a.isComputer) || a.name.localeCompare(b.name),
    );
    setDevices(list);
  }, []);

  const setup = useCallback(async () => {
    setError(null);
    if (!BluetoothHid.isAvailable) {
      setError(ERRORS.unsupported);
      return;
    }

    if (Platform.OS === 'android' && Platform.Version >= 31) {
      const { BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE } = PermissionsAndroid.PERMISSIONS;
      const result = await PermissionsAndroid.requestMultiple([
        BLUETOOTH_CONNECT,
        BLUETOOTH_ADVERTISE,
      ]);
      if (result[BLUETOOTH_CONNECT] !== PermissionsAndroid.RESULTS.GRANTED) {
        setError(ERRORS.permission);
        return;
      }
    }

    const result = BluetoothHid.start();
    if (result !== 'ok') setError(ERRORS[result]);
    refreshDevices();
  }, [refreshDevices]);

  useEffect(() => {
    const subscription = BluetoothHid.addStateListener((event) => {
      setStatus(event.status);
      setDeviceName(event.deviceName);

      if (event.status === 'failed') setError(ERRORS.failed);
      else if (event.status !== 'stopped') setError(null);

      if (event.status === 'connected') {
        sentText.current = '';
        setText('');
      }
      if (event.status === 'ready') refreshDevices();
    });

    void setup();

    return () => {
      subscription.remove();
      BluetoothHid.stop();
    };
  }, [setup, refreshDevices]);

  // Pairing happens in system screens, so refresh the list when coming back to the app.
  useEffect(() => {
    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active') refreshDevices();
    });
    return () => subscription.remove();
  }, [refreshDevices]);

  const onChangeText = (next: string) => {
    BluetoothHid.sendKeys(diffToKeys(sentText.current, next));

    if (next.includes('\n') || next.length > MAX_BUFFER) {
      sentText.current = '';
      setText('');
    } else {
      sentText.current = next;
      setText(next);
    }
  };

  // With an empty box there is no text change to react to, so Backspace is sent from the key event.
  const onKeyPress = (event: TextInputKeyPressEvent) => {
    if (event.nativeEvent.key === 'Backspace' && sentText.current.length === 0) {
      BluetoothHid.sendKeys([MOD_NONE, KEY_BACKSPACE]);
    }
  };

  const connectTo = (device: BluetoothHid.PairedDevice) => {
    if (!BluetoothHid.connect(device.address)) setError(ERRORS.connect);
  };

  const showToLaptop = () => {
    if (!BluetoothHid.requestDiscoverable()) setError(ERRORS.discoverable);
  };

  const canRetry = status === 'stopped' || status === 'failed';

  return (
    <ThemedView style={styles.flex}>
      <SafeAreaView style={styles.flex}>
        <KeyboardAvoidingView style={styles.flex} behavior="padding">
          <View style={styles.header}>
            <ThemedText type="subtitle">Bluetooth Keyboard</ThemedText>
            <View style={styles.statusRow}>
              <View style={[styles.dot, { backgroundColor: STATUS_COLOR[status] }]} />
              <ThemedText type="small" themeColor="textSecondary">
                {STATUS_LABEL[status]}
                {deviceName && (status === 'connected' || status === 'connecting')
                  ? `: ${deviceName}`
                  : ''}
              </ThemedText>
            </View>
          </View>

          {error && (
            <ThemedView type="backgroundElement" style={styles.notice}>
              <ThemedText type="small">{error}</ThemedText>
              {canRetry && <Button label="Coba lagi" variant="secondary" onPress={setup} />}
            </ThemedView>
          )}

          {connected ? (
            <View style={[styles.flex, styles.typing]}>
              <TextInput
                autoFocus
                multiline
                value={text}
                onChangeText={onChangeText}
                onKeyPress={onKeyPress}
                autoCorrect={false}
                autoCapitalize="none"
                autoComplete="off"
                spellCheck={false}
                importantForAutofill="no"
                placeholder="Ketik di sini, langsung muncul di laptop"
                placeholderTextColor={theme.textSecondary}
                textAlignVertical="top"
                style={[
                  styles.input,
                  { backgroundColor: theme.backgroundElement, color: theme.text },
                ]}
              />
              <Button label="Putuskan" variant="secondary" onPress={BluetoothHid.disconnect} />
            </View>
          ) : (
            <ScrollView
              style={styles.flex}
              contentContainerStyle={styles.scroll}
              keyboardShouldPersistTaps="handled">
              <ThemedView type="backgroundElement" style={styles.card}>
                <ThemedText type="smallBold">Pairing pertama kali</ThemedText>
                <ThemedText type="small" themeColor="textSecondary">
                  1. Ketuk tombol di bawah supaya HP terlihat oleh laptop.{'\n'}
                  2. Di laptop, buka pengaturan Bluetooth lalu pasangkan dengan HP ini.{'\n'}
                  3. Kalau belum tersambung otomatis, pilih laptop dari daftar di bawah.
                </ThemedText>
                <Button
                  label="Tampilkan HP ke laptop"
                  onPress={showToLaptop}
                  disabled={status !== 'ready' && status !== 'connecting'}
                />
              </ThemedView>

              <View style={styles.listHeader}>
                <ThemedText type="smallBold">Perangkat terpasang</ThemedText>
                <Pressable accessibilityRole="button" onPress={refreshDevices} hitSlop={8}>
                  <ThemedText type="linkPrimary">Segarkan</ThemedText>
                </Pressable>
              </View>

              {devices.length === 0 ? (
                <ThemedText type="small" themeColor="textSecondary">
                  Belum ada perangkat yang dipasangkan.
                </ThemedText>
              ) : (
                devices.map((device) => (
                  <Pressable
                    key={device.address}
                    accessibilityRole="button"
                    onPress={() => connectTo(device)}
                    disabled={status !== 'ready'}
                    style={({ pressed }) => [
                      styles.deviceRow,
                      {
                        backgroundColor: pressed ? theme.backgroundSelected : theme.backgroundElement,
                        opacity: status === 'ready' ? 1 : 0.5,
                      },
                    ]}>
                    <ThemedText>{device.name}</ThemedText>
                    {device.isComputer && (
                      <ThemedText type="small" themeColor="textSecondary">
                        Komputer
                      </ThemedText>
                    )}
                  </Pressable>
                ))
              )}
            </ScrollView>
          )}
        </KeyboardAvoidingView>
      </SafeAreaView>
    </ThemedView>
  );
}

function Button({
  label,
  onPress,
  variant = 'primary',
  disabled = false,
}: {
  label: string;
  onPress: () => void;
  variant?: 'primary' | 'secondary';
  disabled?: boolean;
}) {
  const theme = useTheme();
  const primary = variant === 'primary';

  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      disabled={disabled}
      style={({ pressed }) => [
        styles.button,
        { backgroundColor: primary ? ACCENT : theme.backgroundSelected },
        (pressed || disabled) && { opacity: disabled ? 0.4 : 0.7 },
      ]}>
      <ThemedText style={{ color: primary ? '#ffffff' : theme.text, fontWeight: 600 }}>
        {label}
      </ThemedText>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  flex: {
    flex: 1,
  },
  header: {
    paddingHorizontal: Spacing.four,
    paddingTop: Spacing.three,
    paddingBottom: Spacing.three,
    gap: Spacing.one,
  },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Spacing.two,
  },
  dot: {
    width: 10,
    height: 10,
    borderRadius: 5,
  },
  notice: {
    marginHorizontal: Spacing.four,
    marginBottom: Spacing.three,
    padding: Spacing.three,
    borderRadius: Spacing.three,
    gap: Spacing.three,
  },
  scroll: {
    paddingHorizontal: Spacing.four,
    paddingBottom: Spacing.five,
    gap: Spacing.three,
  },
  card: {
    padding: Spacing.three,
    borderRadius: Spacing.three,
    gap: Spacing.three,
  },
  listHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: Spacing.three,
  },
  deviceRow: {
    paddingHorizontal: Spacing.three,
    paddingVertical: Spacing.three,
    borderRadius: Spacing.three,
    gap: Spacing.half,
  },
  typing: {
    paddingHorizontal: Spacing.four,
    paddingBottom: Spacing.three,
    gap: Spacing.three,
  },
  input: {
    flex: 1,
    padding: Spacing.three,
    borderRadius: Spacing.three,
    fontSize: 18,
    lineHeight: 26,
  },
  button: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: Spacing.three,
    borderRadius: Spacing.three,
  },
});
