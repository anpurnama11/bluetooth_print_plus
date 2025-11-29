package com.example.bluetooth_print_plus.bluetooth_print_plus;

import static android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.example.bluetooth_print_plus.bluetooth_print_plus.payload.BPPState;
import com.example.bluetooth_print_plus.bluetooth_print_plus.payload.BluetoothParameter;
import com.example.bluetooth_print_plus.bluetooth_print_plus.payload.Printer;
import com.example.bluetooth_print_plus.bluetooth_print_plus.payload.ThreadPoolManager;
import com.gprinter.bean.PrinterDevices;
import com.gprinter.io.PortManager;
import com.gprinter.utils.CallbackListener;
import com.gprinter.utils.Command;
import com.gprinter.utils.ConnMethod;
import com.gprinter.utils.LogUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.EventChannel.StreamHandler;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener;
import pub.devrel.easypermissions.EasyPermissions;

/**
 * BluetoothPrintPlusPlugin
 */
public class BluetoothPrintPlusPlugin
        implements FlutterPlugin, ActivityAware, MethodCallHandler, RequestPermissionsResultListener {

  private static final String TAG = "BluetoothPrintPlusPlugin";
  private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1452;

  private final Object initializationLock = new Object();

  private Context context;
  private Activity activity;

  // Result for the current "startScan" method call (waiting on permission).
  private Result pendingResult;

  public PortManager portManager = null;
  private BluetoothAdapter mBluetoothAdapter;

  private FlutterPluginBinding pluginBinding;
  private ActivityPluginBinding activityBinding;
  private MethodChannel channel;
  private EventSink sink;
  private MethodChannel tscChannel;
  private MethodChannel cpclChannel;
  private MethodChannel escChannel;
  private EventChannel stateChannel;
  private final TscCommandPlugin tscCommandPlugin = new TscCommandPlugin();
  private final CpclCommandPlugin cpclCommandPlugin = new CpclCommandPlugin();
  private final EscCommandPlugin escCommandPlugin = new EscCommandPlugin();

  public BluetoothPrintPlusPlugin() {}

  @Override
  public void onAttachedToEngine(FlutterPluginBinding binding) {
    pluginBinding = binding;
  }

  @Override
  public void onDetachedFromEngine(FlutterPluginBinding binding) {
    pluginBinding = null;
  }

  @Override
  public void onAttachedToActivity(ActivityPluginBinding binding) {
    activityBinding = binding;
    setup(
        pluginBinding.getBinaryMessenger(),
        (Application) pluginBinding.getApplicationContext(),
        activityBinding.getActivity(),
        activityBinding
    );
  }

  @Override
  public void onDetachedFromActivity() {
    tearDown();
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {
    onDetachedFromActivity();
  }

  @Override
  public void onReattachedToActivityForConfigChanges(ActivityPluginBinding binding) {
    onAttachedToActivity(binding);
  }

  private void setup(
      final BinaryMessenger messenger,
      final Application application,
      final Activity activity,
      final ActivityPluginBinding activityBinding
  ) {
    synchronized (initializationLock) {
      LogUtils.i(TAG, "setup");
      this.activity = activity;
      this.context = application;
      channel = new MethodChannel(messenger, "bluetooth_print_plus/methods");
      channel.setMethodCallHandler(this);
      // tsc Channel
      tscChannel = new MethodChannel(messenger, "bluetooth_print_plus_tsc");
      tscCommandPlugin.setUpChannel(tscChannel);
      // cpcl Channel
      cpclChannel = new MethodChannel(messenger, "bluetooth_print_plus_cpcl");
      cpclCommandPlugin.setUpChannel(cpclChannel);
      // esc Channel
      escChannel = new MethodChannel(messenger, "bluetooth_print_plus_esc");
      escCommandPlugin.setUpChannel(escChannel);
      // state Channel
      stateChannel = new EventChannel(messenger, "bluetooth_print_plus/state");
      stateChannel.setStreamHandler(stateHandler);
      mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

      activityBinding.addRequestPermissionsResultListener(this);
      initBroadcast();
    }
  }

  private void tearDown() {
    LogUtils.i(TAG, "teardown");
    try {
      if (context != null) {
        context.unregisterReceiver(mFindBlueToothReceiver);
      }
    } catch (Exception ignored) {
    }
    context = null;

    if (activityBinding != null) {
      activityBinding.removeRequestPermissionsResultListener(this);
      activityBinding = null;
    }

    if (channel != null) {
      channel.setMethodCallHandler(null);
      channel = null;
    }

    if (stateChannel != null) {
      stateChannel.setStreamHandler(null);
      stateChannel = null;
    }

    mBluetoothAdapter = null;
    pendingResult = null;
  }

  @Override
  public void onMethodCall(MethodCall call, Result result) {
    if (mBluetoothAdapter == null && !"isAvailable".equals(call.method)) {
      result.error("bluetooth_unavailable", "the device does not have bluetooth", null);
      return;
    }
    switch (call.method) {
      case "state":
        state(result);
        break;
      case "startScan":
        startScan(result);
        break;
      case "stopScan":
        stopScan();
        result.success(null);
        break;
      case "connect":
        Map<String, Object> args = call.arguments();
        assert args != null;
        final String address = (String) args.get("address");
        stopScan();
        connect(address);
        result.success(null);
        break;
      case "disconnect":
        Printer.close();
        result.success(null);
        break;
      case "write":
        byte[] bytes = call.argument("data");
        try {
          result.success(write(bytes));
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        break;
      default:
        result.notImplemented();
        break;
    }
  }

  private void initBroadcast() {
    try {
      IntentFilter filter = new IntentFilter();
      filter.addAction(BluetoothDevice.ACTION_FOUND);
      filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
      context.registerReceiver(mFindBlueToothReceiver, filter);
    } catch (Exception e) {
      LogUtils.e(TAG, "initBroadcast error: " + e.getMessage());
    }
  }

  private final BroadcastReceiver mFindBlueToothReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      String action = intent.getAction();
      if (BluetoothDevice.ACTION_FOUND.equals(action)) {
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null || device.getName() == null) return;
        if (device.getType() == DEVICE_TYPE_LE) return;
        BluetoothParameter parameter = new BluetoothParameter();
        int rssi = Objects.requireNonNull(intent.getExtras()).getShort(BluetoothDevice.EXTRA_RSSI);
        parameter.setBluetoothName(device.getName());
        parameter.setBluetoothMac(device.getAddress());
        parameter.setBluetoothStrength(rssi + "");
        LogUtils.i(TAG, "\nBlueToothName: " + device.getName()
            + "\nMacAddress: " + device.getAddress()
            + "\nrssi: " + rssi);
        invokeMethodUIThread(device);
      }
    }
  };

  private void state(Result result) {
    try {
      switch (mBluetoothAdapter.getState()) {
        case BluetoothAdapter.STATE_OFF:
          result.success(BPPState.BlueOff.getValue());
          break;
        case BluetoothAdapter.STATE_ON:
          result.success(BPPState.BlueOn.getValue());
          break;
        default:
          // Do nothing
          break;
      }
    } catch (SecurityException e) {
      result.error("invalid_argument", "argument 'address' not found", null);
    }
  }

  private void startScan(Result result) {
    LogUtils.i(TAG, "start scan...");
    try {
      String[] perms;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Android 12+ uses new Bluetooth permissions (no location needed with neverForLocation flag)
        perms = new String[] {
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        };
      } else {
        // Older Android requires location permission for BT scanning
        perms = new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION,
        };
      }

      boolean hasPerms = EasyPermissions.hasPermissions(this.context, perms);
      LogUtils.i(TAG, "hasPermissions: " + hasPerms);

      // If already granted, start scan immediately and return success.
      if (hasPerms) {
        LogUtils.i(TAG, "Permissions granted, calling startScanInternal");
        startScanInternal();
        result.success(null);
        return;
      }
      
      LogUtils.i(TAG, "Requesting permissions...");

      // Need to ask for permissions; store this result for later completion.
      // If another permission flow is already pending, fail it first.
      if (pendingResult != null) {
        pendingResult.error(
            "PERMISSION_IN_PROGRESS",
            "Another permission request is already in progress",
            null
        );
        pendingResult = null;
      }

      pendingResult = result;

      EasyPermissions.requestPermissions(
          this.activity,
          "Bluetooth requires Bluetooth permissions to scan for devices.",
          REQUEST_BLUETOOTH_PERMISSIONS,
          perms
      );

      // Do NOT call result.success here; wait for onRequestPermissionsResult.
    } catch (Exception e) {
      result.error("startScan", e.getMessage(), e);
    }
  }

  private void invokeMethodUIThread(BluetoothDevice device) {
    final Map<String, Object> ret = new HashMap<>();
    ret.put("address", device.getAddress());
    ret.put("name", device.getName());
    ret.put("type", device.getType());
    new Handler(Looper.getMainLooper()).post(() -> {
      if (!ret.isEmpty() && channel != null) {
        channel.invokeMethod("ScanResult", ret);
      } else {
        LogUtils.w(TAG, "invokeMethodUIThread: tried to call method on closed channel: ScanResult");
      }
    });
  }

  private void startScanInternal() throws IllegalStateException {
    if (mBluetoothAdapter != null) {
      boolean started = mBluetoothAdapter.startDiscovery();
      LogUtils.i(TAG, "startDiscovery() returned: " + started);
      if (!started) {
        LogUtils.w(TAG, "startDiscovery failed - check if location is enabled and permissions granted");
      }
    } else {
      throw new IllegalStateException("BluetoothAdapter is null");
    }
  }

  private void stopScan() {
    if (mBluetoothAdapter != null) {
      mBluetoothAdapter.cancelDiscovery();
    }
  }

  public void connect(final String mac) {
    ThreadPoolManager.getInstance().addTask(new Runnable() {
      @Override
      public void run() {
        if (portManager != null) {
          portManager.closePort();
          try {
            Thread.sleep(500);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }
        if (mac != null) {
          PrinterDevices blueTooth = new PrinterDevices.Build()
              .setContext(context)
              .setConnMethod(ConnMethod.BLUETOOTH)
              .setMacAddress(mac)
              .setCommand(Command.ESC)
              .setCallbackListener(new CallbackListener() {
                @Override
                public void onConnecting() { }

                @Override
                public void onCheckCommand() { }

                @Override
                public void onSuccess(PrinterDevices printerDevices) {
                  if (sink != null) {
                    sink.success(BPPState.DeviceConnected.getValue());
                  }
                }

                @Override
                public void onReceive(byte[] data) {
                  if (data == null || channel == null) return;
                  new Handler(Looper.getMainLooper()).post(() -> {
                    channel.invokeMethod("ReceivedData", data);
                  });
                }

                @Override
                public void onFailure() { }

                @Override
                public void onDisconnect() {
                  if (sink != null) {
                    sink.success(BPPState.DeviceDisconnected.getValue());
                  }
                }
              })
              .build();
          Printer.connect(blueTooth);
        }
      }
    });
  }

  @SuppressWarnings("unchecked")
  private boolean write(byte[] data) throws IOException {
    boolean result = Printer.getPortManager().writeDataImmediately(data);
    LogUtils.d(TAG, result ? "发送成功" : "发送失败");
    return result;
  }

  @Override
public boolean onRequestPermissionsResult(
    int requestCode,
    @NonNull String[] permissions,
    @NonNull int[] grantResults
) {
  LogUtils.d(TAG, "onRequestPermissionsResult");

  if (requestCode != REQUEST_BLUETOOTH_PERMISSIONS) {
    return false;
  }

  if (pendingResult == null) {
    LogUtils.w(TAG, "onRequestPermissionsResult: no pendingResult");
    return true;
  }

  if (grantResults.length == 0) {
    pendingResult.error(
        "no_permissions",
        "Permission dialog was cancelled or returned no results",
        null
    );
    pendingResult = null;
    return true;
  }

  boolean allGranted = true;
  for (int r : grantResults) {
    if (r != PackageManager.PERMISSION_GRANTED) {
      allGranted = false;
      break;
    }
  }

  if (allGranted) {
    try {
      startScanInternal();
      pendingResult.success(null);
    } catch (Exception e) {
      pendingResult.error("startScan", e.getMessage(), e);
    } finally {
      pendingResult = null;
    }
  } else {
    pendingResult.error(
        "no_permissions",
        "this plugin requires Bluetooth permissions to scan for devices",
        null
    );
    pendingResult = null;
  }

  return true;
}

  private final StreamHandler stateHandler = new StreamHandler() {
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
          int blueState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0);
          if (sink == null) return;
          switch (blueState) {
            case BluetoothAdapter.STATE_ON:
              sink.success(BPPState.BlueOn.getValue());
              break;
            case BluetoothAdapter.STATE_OFF:
              sink.success(BPPState.BlueOff.getValue());
              break;
          }
        }
      }
    };

    @Override
    public void onListen(Object o, EventSink eventSink) {
      sink = eventSink;
      IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
      context.registerReceiver(mReceiver, filter);
    }

    @Override
    public void onCancel(Object o) {
      sink = null;
      context.unregisterReceiver(mReceiver);
    }
  };
}
