package com.wil.strudel37;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bluetooth LE MIDI + USB/virtual MIDI input, exposed to the page as window.AndroidMidi. */
public class MidiBridge {
    NativeSynth synth;
    static final ParcelUuid MIDI_SERVICE = ParcelUuid.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700");
    static final int PERM_REQUEST = 37;

    static class Dev {
        String id, name, type;
        BluetoothDevice bt;
        MidiDeviceInfo info;
        MidiDevice device;
        final List<MidiOutputPort> ports = new ArrayList<MidiOutputPort>();
        boolean connected, connecting;
    }

    final Activity act;
    final WebView web;
    final Handler ui = new Handler(Looper.getMainLooper());
    final Map<String, Dev> devs = new LinkedHashMap<String, Dev>();
    MidiManager mm;
    BluetoothAdapter bt;
    BluetoothLeScanner scanner;
    boolean scanning;
    int pendingScan;
    String pendingConnect, pendingConnectName;

    MidiBridge(Activity a, WebView w) {
        act = a;
        web = w;
        if (Build.VERSION.SDK_INT >= 23 && a.getPackageManager().hasSystemFeature(PackageManager.FEATURE_MIDI)) {
            mm = (MidiManager) a.getSystemService(Context.MIDI_SERVICE);
            if (mm != null) mm.registerDeviceCallback(new MidiManager.DeviceCallback() {
                @Override public void onDeviceAdded(MidiDeviceInfo info) { addInfo(info); }
                @Override public void onDeviceRemoved(MidiDeviceInfo info) {
                    Dev d = devs.get(idFor(info));
                    if (d != null && d.bt == null) { close(d); devs.remove(d.id); emit("{\"t\":\"gone\",\"id\":\"" + d.id + "\"}"); }
                }
            }, ui);
        }
        BluetoothManager bm = (BluetoothManager) a.getSystemService(Context.BLUETOOTH_SERVICE);
        bt = bm != null ? bm.getAdapter() : null;
    }

    /* ---------------- JS API (called on a binder thread) ---------------- */

    @JavascriptInterface public boolean available() { return mm != null; }
    @JavascriptInterface public void scan(final int seconds) { ui.post(new Runnable() { public void run() { doScan(seconds); } }); }
    @JavascriptInterface public void stopScan() { ui.post(new Runnable() { public void run() { doStopScan(); } }); }
    @JavascriptInterface public void list() { ui.post(new Runnable() { public void run() { refreshList(); } }); }
    @JavascriptInterface public void connect(final String id, final String name) { ui.post(new Runnable() { public void run() { doConnect(id, name); } }); }
    @JavascriptInterface public void disconnect(final String id) {
        ui.post(new Runnable() { public void run() { Dev d = devs.get(id); if (d != null) { close(d); emitDev(d); } } });
    }

    /* ---------------- permissions ---------------- */

    String[] neededPermissions() {
        List<String> need = new ArrayList<String>();
        String[] want = Build.VERSION.SDK_INT >= 31
                ? new String[]{"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for (String p : want) if (act.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        return need.toArray(new String[0]);
    }

    void onPermissions(int code, int[] results) {
        if (code != PERM_REQUEST) return;
        boolean ok = results.length > 0;
        for (int r : results) ok &= r == PackageManager.PERMISSION_GRANTED;
        if (!ok) { error("Bluetooth permission denied. Allow it in Settings > Apps > Inconsequent 37."); return; }
        if (pendingScan > 0) { int s = pendingScan; pendingScan = 0; doScan(s); }
        if (pendingConnect != null) { String id = pendingConnect; pendingConnect = null; doConnect(id, pendingConnectName); }
    }

    /* ---------------- scanning ---------------- */

    final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            BluetoothDevice d = r.getDevice();
            String id = "ble:" + d.getAddress();
            Dev dv = devs.get(id);
            if (dv == null) { dv = new Dev(); dv.id = id; dv.type = "Bluetooth"; devs.put(id, dv); }
            dv.bt = d;
            String name = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
            if (name == null) try { name = d.getName(); } catch (SecurityException ignored) { }
            if (name != null) dv.name = name;
            if (dv.name == null) dv.name = "BLE MIDI " + d.getAddress();
            emitDev(dv);
        }
        @Override public void onScanFailed(int code) { scanning = false; emitScan(); error("Bluetooth scan failed (code " + code + ")."); }
    };
    final Runnable stopLater = new Runnable() { public void run() { doStopScan(); } };

    void doScan(int seconds) {
        if (mm == null) { error("This device has no MIDI support (needs Android 6 or newer)."); return; }
        refreshList();
        String[] need = neededPermissions();
        if (need.length > 0) { pendingScan = seconds; act.requestPermissions(need, PERM_REQUEST); return; }
        if (bt == null) { error("This device has no Bluetooth. USB keyboards still show up in the list."); return; }
        try {
            if (!bt.isEnabled()) {
                act.startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                error("Turn Bluetooth on and tap Scan again.");
                return;
            }
            scanner = bt.getBluetoothLeScanner();
            if (scanner == null) { error("Bluetooth is not available right now."); return; }
            if (scanning) scanner.stopScan(scanCb);
            List<ScanFilter> filters = Collections.singletonList(new ScanFilter.Builder().setServiceUuid(MIDI_SERVICE).build());
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(filters, settings, scanCb);
            scanning = true;
            emitScan();
            ui.removeCallbacks(stopLater);
            ui.postDelayed(stopLater, Math.max(3, seconds) * 1000L);
        } catch (SecurityException e) {
            error("No permission to use Bluetooth.");
        }
    }

    void doStopScan() {
        ui.removeCallbacks(stopLater);
        if (scanning && scanner != null) try { scanner.stopScan(scanCb); } catch (Exception ignored) { }
        scanning = false;
        emitScan();
    }

    void refreshList() {
        if (mm != null) for (MidiDeviceInfo info : mm.getDevices()) addInfo(info);
        for (Dev d : devs.values()) emitDev(d);
        emitScan();
    }

    String idFor(MidiDeviceInfo info) {
        if (info.getType() == MidiDeviceInfo.TYPE_BLUETOOTH) {
            Bundle p = info.getProperties();
            BluetoothDevice b = p != null ? (BluetoothDevice) p.getParcelable(MidiDeviceInfo.PROPERTY_BLUETOOTH_DEVICE) : null;
            if (b != null) return "ble:" + b.getAddress();
        }
        return "dev:" + info.getId();
    }

    void addInfo(MidiDeviceInfo info) {
        if (info.getOutputPortCount() == 0) return; // nothing to receive from
        String id = idFor(info);
        Dev d = devs.get(id);
        if (d == null) {
            d = new Dev(); d.id = id;
            d.type = info.getType() == MidiDeviceInfo.TYPE_USB ? "USB" : info.getType() == MidiDeviceInfo.TYPE_BLUETOOTH ? "Bluetooth" : "Virtual";
            devs.put(id, d);
        }
        d.info = info;
        Bundle p = info.getProperties();
        String name = p != null ? p.getString(MidiDeviceInfo.PROPERTY_NAME) : null;
        if (name == null && p != null) name = p.getString(MidiDeviceInfo.PROPERTY_PRODUCT);
        if (name != null) d.name = name;
        if (d.name == null) d.name = "MIDI " + info.getId();
        emitDev(d);
    }

    /* ---------------- connecting ---------------- */

    void doConnect(String id, String name) {
        if (mm == null) { error("MIDI is not supported on this device."); return; }
        Dev d = devs.get(id);
        if (d == null && id.startsWith("ble:") && bt != null) {
            try {
                d = new Dev(); d.id = id; d.type = "Bluetooth"; d.name = name != null ? name : id.substring(4);
                d.bt = bt.getRemoteDevice(id.substring(4));
                devs.put(id, d);
            } catch (IllegalArgumentException e) { error("Invalid Bluetooth address."); return; }
        }
        if (d == null) { error("Device not found. Tap Scan."); return; }
        if (d.connected || d.connecting) { emitDev(d); return; }
        if (d.bt != null) {
            String[] need = neededPermissions();
            if (need.length > 0) { pendingConnect = id; pendingConnectName = name; act.requestPermissions(need, PERM_REQUEST); return; }
            doStopScan();
        }
        final Dev dev = d;
        dev.connecting = true;
        emitDev(dev);
        MidiManager.OnDeviceOpenedListener l = new MidiManager.OnDeviceOpenedListener() {
            @Override public void onDeviceOpened(MidiDevice device) {
                dev.connecting = false;
                if (device == null) { error("Could not connect to " + dev.name + "."); emitDev(dev); return; }
                dev.device = device;
                int n = device.getInfo().getOutputPortCount();
                for (int i = 0; i < n; i++) {
                    MidiOutputPort port = device.openOutputPort(i);
                    if (port != null) { port.connect(new Rx(dev.id)); dev.ports.add(port); }
                }
                dev.connected = !dev.ports.isEmpty();
                if (!dev.connected) error(dev.name + " has no MIDI output ports.");
                emitDev(dev);
            }
        };
        try {
            if (dev.bt != null) mm.openBluetoothDevice(dev.bt, l, ui);
            else mm.openDevice(dev.info, l, ui);
        } catch (Exception e) {
            dev.connecting = false;
            error("Connection error: " + e.getMessage());
            emitDev(dev);
        }
    }

    void close(Dev d) {
        for (MidiOutputPort p : d.ports) try { p.close(); } catch (Exception ignored) { }
        d.ports.clear();
        if (d.device != null) try { d.device.close(); } catch (Exception ignored) { }
        d.device = null;
        d.connected = false;
        d.connecting = false;
    }

    void closeAll() {
        doStopScan();
        for (Dev d : devs.values()) close(d);
    }

    /** Parses the raw MIDI stream (running status, skips sysex and real-time) and forwards channel messages. */
    class Rx extends MidiReceiver {
        final String id;
        int status, n;
        final int[] data = new int[2];
        boolean sysex;
        Rx(String id) { this.id = id; }
        @Override public void onSend(byte[] msg, int offset, int count, long timestamp) {
            final StringBuilder js = new StringBuilder();
            for (int i = offset; i < offset + count; i++) {
                int x = msg[i] & 0xff;
                if (x >= 0xF8) continue;
                if (x == 0xF0) { sysex = true; continue; }
                if (x == 0xF7) { sysex = false; continue; }
                if (sysex) continue;
                if (x >= 0x80) { status = x >= 0xF0 ? 0 : x; n = 0; continue; }
                if (status == 0) continue;
                data[n++] = x;
                int hi = status & 0xF0, need = (hi == 0xC0 || hi == 0xD0) ? 1 : 2;
                if (n == need) {
                    int d2 = need == 2 ? data[1] : 0;
                    // play straight into the native engine; the page only mirrors it on the panel
                    boolean played = synth != null && synth.midi(status, data[0], d2);
                    js.append("window.__midiIn&&__midiIn(").append(status).append(',').append(data[0]).append(',')
                      .append(d2).append(",'").append(id).append("',").append(played ? 1 : 0).append(");");
                    n = 0;
                }
            }
            if (js.length() > 0) ui.post(new Runnable() { public void run() { web.evaluateJavascript(js.toString(), null); } });
        }
    }

    /* ---------------- events to the page ---------------- */

    static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20 || c == 0x2028 || c == 0x2029) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
    void emitDev(Dev d) {
        emit("{\"t\":\"dev\",\"id\":" + q(d.id) + ",\"name\":" + q(d.name) + ",\"type\":" + q(d.type)
                + ",\"connected\":" + d.connected + ",\"connecting\":" + d.connecting + "}");
    }
    void emitScan() { emit("{\"t\":\"scan\",\"on\":" + scanning + "}"); }
    void error(String m) { emit("{\"t\":\"error\",\"msg\":" + q(m) + "}"); }
    void emit(final String json) {
        ui.post(new Runnable() { public void run() { web.evaluateJavascript("window.__nativeMidi&&__nativeMidi(" + json + ")", null); } });
    }
}
