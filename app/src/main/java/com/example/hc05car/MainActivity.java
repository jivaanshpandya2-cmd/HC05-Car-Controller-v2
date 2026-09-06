package com.example.hc05car;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Commands expected by the Arduino:
    // F forward, B back, L left, R right, S stop
    // A obstacle mode, M manual mode

    private BluetoothAdapter adapter;
    private BluetoothSocket socket;
    private OutputStream out;

    private Spinner deviceSpinner;
    private TextView status;
    private TextView modeText;
    private TextView lastCommand;
    private Button connectBtn;

    private final ArrayList<BluetoothDevice> pairedDevices = new ArrayList<>();
    private final ArrayList<String> pairedNames = new ArrayList<>();

    private volatile boolean connected = false;
    private volatile boolean connecting = false;

    private Drawable statusOff;
    private Drawable statusOn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        adapter = BluetoothAdapter.getDefaultAdapter();

        status = findViewById(R.id.status);
        modeText = findViewById(R.id.modeText);
        lastCommand = findViewById(R.id.lastCommand);
        connectBtn = findViewById(R.id.connectBtn);
        deviceSpinner = findViewById(R.id.deviceSpinner);

        statusOff = getDrawable(R.drawable.status_off);
        statusOn = getDrawable(R.drawable.status_on);

        requestBluetoothPermissionIfNeeded();

        connectBtn.setOnClickListener(v -> {
            if (connected || connecting) {
                disconnect();
            } else {
                connectSelectedDevice();
            }
        });

        findViewById(R.id.manualBtn).setOnClickListener(v -> {
            send('M');
            modeText.setText("Mode: MANUAL");
        });

        findViewById(R.id.obstacleBtn).setOnClickListener(v -> {
            send('A');
            modeText.setText("Mode: OBSTACLE");
        });

        setupHold(findViewById(R.id.forwardBtn), 'F');
        setupHold(findViewById(R.id.backBtn), 'B');
        setupHold(findViewById(R.id.leftBtn), 'L');
        setupHold(findViewById(R.id.rightBtn), 'R');

        findViewById(R.id.stopBtn).setOnClickListener(v -> send('S'));

        if (adapter == null) {
            status.setText("Bluetooth not supported");
            connectBtn.setEnabled(false);
        } else {
            refreshPairedDevices();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            refreshPairedDevices();
        }
    }

    private void requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 100);
        }
    }

    private boolean hasBtPermission() {
        return Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshPairedDevices() {
        if (adapter == null || !hasBtPermission()) return;

        pairedDevices.clear();
        pairedNames.clear();

        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();

            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    pairedDevices.add(d);
                    String name = d.getName();
                    if (name == null || name.trim().isEmpty()) name = "Unknown device";
                    pairedNames.add(name + "  •  " + d.getAddress());
                }
            }

            if (pairedNames.isEmpty()) {
                pairedNames.add("No paired devices — pair HC-05 in Settings");
            }

            ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_spinner_dropdown_item,
                    pairedNames
            );
            deviceSpinner.setAdapter(spinnerAdapter);

        } catch (SecurityException e) {
            setStatus("Bluetooth permission needed", false);
        }
    }

    private void connectSelectedDevice() {
        if (adapter == null) return;

        if (!hasBtPermission()) {
            requestBluetoothPermissionIfNeeded();
            return;
        }

        if (!adapter.isEnabled()) {
            Toast.makeText(this, "Turn Bluetooth ON first", Toast.LENGTH_SHORT).show();
            return;
        }

        int position = deviceSpinner.getSelectedItemPosition();
        if (pairedDevices.isEmpty() || position < 0 || position >= pairedDevices.size()) {
            Toast.makeText(this, "Pair HC-05 in phone Bluetooth settings first", Toast.LENGTH_LONG).show();
            refreshPairedDevices();
            return;
        }

        BluetoothDevice device = pairedDevices.get(position);

        connecting = true;
        connectBtn.setText("CANCEL");
        setStatus("Connecting…", false);

        new Thread(() -> {
            BluetoothSocket connectedSocket = null;
            String failReason = "Unknown error";

            try {
                adapter.cancelDiscovery();

                // Attempt 1: standard secure SPP
                try {
                    connectedSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                    connectedSocket.connect();
                } catch (Exception first) {
                    closeQuietly(connectedSocket);
                    connectedSocket = null;
                    failReason = first.getClass().getSimpleName();

                    // Attempt 2: insecure SPP — often works better with HC-05 clones
                    try {
                        connectedSocket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                        connectedSocket.connect();
                    } catch (Exception second) {
                        closeQuietly(connectedSocket);
                        connectedSocket = null;
                        failReason = second.getClass().getSimpleName();

                        // Attempt 3: legacy RFCOMM channel 1 fallback
                        try {
                            Method method = device.getClass().getMethod(
                                    "createRfcommSocket", int.class);
                            connectedSocket = (BluetoothSocket) method.invoke(device, 1);
                            connectedSocket.connect();
                        } catch (Exception third) {
                            closeQuietly(connectedSocket);
                            connectedSocket = null;
                            failReason = third.getClass().getSimpleName();
                        }
                    }
                }

                if (connectedSocket == null || !connectedSocket.isConnected()) {
                    throw new IOException("Could not open RFCOMM socket: " + failReason);
                }

                socket = connectedSocket;
                out = socket.getOutputStream();
                connected = true;
                connecting = false;

                runOnUiThread(() -> {
                    setStatus("●  CONNECTED", true);
                    connectBtn.setText("DISCONNECT");
                    Toast.makeText(this, "HC-05 connected", Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                closeQuietly(connectedSocket);
                socket = null;
                out = null;
                connected = false;
                connecting = false;

                String msg = e.getMessage();
                if (msg == null || msg.trim().isEmpty()) {
                    msg = e.getClass().getSimpleName();
                }
                final String finalMsg = msg;

                runOnUiThread(() -> {
                    setStatus("Connection failed", false);
                    connectBtn.setText("CONNECT");
                    Toast.makeText(
                            this,
                            "Connection failed: " + finalMsg +
                                    "\nPair HC-05 in Settings, then try again.",
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        }).start();
    }

    private void setupHold(Button button, char command) {
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    send(command);
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_OUTSIDE:
                    send('S');
                    v.performClick();
                    return true;
            }
            return true;
        });
    }

    private synchronized void send(char command) {
        lastCommand.setText("Last command: " + command);

        if (!connected || out == null) {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            out.write((byte) command);
            out.flush();
        } catch (IOException e) {
            disconnect();
            Toast.makeText(this, "Bluetooth connection lost", Toast.LENGTH_SHORT).show();
        }
    }

    private void setStatus(String text, boolean isConnected) {
        status.setText(text);
        status.setTextColor(isConnected ? 0xFF86EFAC : 0xFFFCA5A5);
        status.setBackground(isConnected ? statusOn : statusOff);
    }

    private synchronized void disconnect() {
        connecting = false;
        connected = false;

        try { if (out != null) out.close(); } catch (Exception ignored) {}
        closeQuietly(socket);

        out = null;
        socket = null;

        runOnUiThread(() -> {
            setStatus("●  DISCONNECTED", false);
            connectBtn.setText("CONNECT");
            lastCommand.setText("Last command: —");
        });
    }

    private void closeQuietly(BluetoothSocket s) {
        if (s == null) return;
        try { s.close(); } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        disconnect();
        super.onDestroy();
    }
}
