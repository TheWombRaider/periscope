package com.maksz42.periscope.overlay;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.RequiresApi;

import com.maksz42.periscope.R;
import com.maksz42.periscope.frigate.Config;
import com.maksz42.periscope.helper.Settings;

import java.util.Collections;
import java.util.List;

/**
 * Watch a camera over TV: hold a chosen remote button (Info by default) to pick a camera, which
 * then floats in a corner over whatever is on the TV (an app or an HDMI input). Hold it again to
 * change or close the camera.
 *
 * The service only filters keys (see {@link KeyGate}); it does not read the screen or any app's
 * content. Its windows are accessibility overlays, which need no other permission and never take
 * focus, so the show keeps its focus and its controls. Turning the service on in the system's
 * accessibility settings is the opt-in.
 */
public final class OverlayKeyService extends AccessibilityService {
  private static final String TAG = "OverlayKeyService";
  private static final long CHOOSER_TIMEOUT_MS = 15_000;
  /** Rows shown at once at most; fewer on a short screen. A longer list scrolls with the highlight. */
  private static final int MAX_ROWS = 8;

  private static OverlayKeyService running;

  private final Handler main = new Handler(Looper.getMainLooper());
  private final KeyGate gate = new KeyGate();
  private CameraOverlay camera;
  private ChooserOverlay chooser;
  private ChooserModel model;
  private List<String> cameras;
  private int loadToken;
  private boolean receiving;

  /** Token for this service's pending hold timer, so cleanup can cancel exactly that. */
  private final Object holdTimerToken = new Object();
  private boolean destroyed;

  private final android.content.SharedPreferences.OnSharedPreferenceChangeListener triggerChanged =
      (prefs, key) -> {
        // A new button or gesture applies at once, even to a press already under way.
        Settings settings = Settings.getInstance();
        if (settings.OverlayTriggerKeyKey.equals(key) || settings.OverlayGestureKey.equals(key)) {
          main.removeCallbacksAndMessages(holdTimerToken);
          gate.setHoldKey(settings.getOverlayTriggerKey());
          gate.setGesture(settings.getOverlayGesture());
          gate.resetTrigger();
        }
      };

  private final Runnable chooserTimeout = () -> {
    if (Build.VERSION.SDK_INT >= 22) closeChooser();
  };

  private final BroadcastReceiver screenOff = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (Build.VERSION.SDK_INT >= 22) closeAll();
    }
  };

  /** Whether the user has turned this service on in the system's accessibility settings. */
  public static boolean isEnabled(Context context) {
    String enabled = android.provider.Settings.Secure.getString(
        context.getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
    if (enabled == null) return false;
    String me = new android.content.ComponentName(context, OverlayKeyService.class).flattenToString();
    String shortMe = new android.content.ComponentName(context, OverlayKeyService.class).flattenToShortString();
    for (String s : enabled.split(":")) {
      if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(shortMe)) return true;
    }
    return false;
  }

  /** The connected service, or null; lets debug builds drive it from tests. */
  static OverlayKeyService running() {
    return running;
  }

  @Override
  protected void onServiceConnected() {
    if (Build.VERSION.SDK_INT < 22) return; // accessibility overlays need Android 5.1
    camera = new CameraOverlay(this);
    chooser = new ChooserOverlay(this);
    IntentFilter off = new IntentFilter(Intent.ACTION_SCREEN_OFF);
    off.addAction(Intent.ACTION_DREAMING_STARTED);
    registerReceiver(screenOff, off);
    receiving = true;
    getSharedPreferences(Settings.getInstance().PREF_FILE_NAME, MODE_PRIVATE)
        .registerOnSharedPreferenceChangeListener(triggerChanged);
    running = this;
  }

  @Override
  protected boolean onKeyEvent(KeyEvent e) {
    if (Build.VERSION.SDK_INT < 22 || camera == null || destroyed) return false;
    Settings settings = Settings.getInstance();
    gate.setHoldKey(settings.getOverlayTriggerKey());
    gate.setGesture(settings.getOverlayGesture());
    KeyGate.Result r = gate.onKey(e.getKeyCode(), e.getAction(), e.getRepeatCount(),
        e.isLongPress(), e.getDownTime(), e.getEventTime(), chooser.isOpen());
    act(r.action);
    if (r.action == KeyGate.Action.START_HOLD_TIMER) {
      // Timed from the press itself (uptime, like the event), not from when it reached us.
      long downTime = e.getDownTime();
      main.removeCallbacksAndMessages(holdTimerToken);
      main.postAtTime(() -> {
        if (!destroyed) act(gate.onHoldTimer(downTime, chooser.isOpen()).action);
      }, holdTimerToken, downTime + KeyGate.HOLD_MS);
    }
    return r.consume;
  }

  @RequiresApi(22)
  private void act(KeyGate.Action action) {
    switch (action) {
      case OPEN_CHOOSER:
        openChooser();
        break;
      case UP:
        move(-1);
        break;
      case DOWN:
        move(1);
        break;
      case SELECT:
        select();
        break;
      case CANCEL:
        closeChooser();
        break;
      default:
        break;
    }
  }

  @RequiresApi(22)
  private void restartTimeout() {
    main.removeCallbacks(chooserTimeout);
    main.postDelayed(chooserTimeout, CHOOSER_TIMEOUT_MS);
  }

  @RequiresApi(22)
  private void openChooser() {
    restartTimeout();
    // The grid's own camera selection and order, when the user has made one; it is read fresh
    // each time, so changes made in the grid show up at once.
    // An empty selection is a choice too: the grid then shows "no cameras selected", and so does
    // the list (still offering to close a camera that is showing).
    List<String> selected = Settings.getInstance().getSelectedCameras();
    if (selected != null) {
      showModel(new ChooserModel(selected, camera.camera(), getString(R.string.overlay_close)));
      return;
    }
    if (cameras != null) {
      showModel(new ChooserModel(cameras, camera.camera(), getString(R.string.overlay_close)));
      return;
    }
    model = null;
    chooser.show(getString(R.string.overlay_loading), Collections.emptyList(), -1, false, false);
    int token = ++loadToken;
    new Thread(() -> {
      List<String> found = loadCameras();
      main.post(() -> {
        if (token != loadToken || !chooser.isOpen()) return;
        if (found == null) {
          chooser.show(getString(R.string.overlay_server_unreachable), Collections.emptyList(), -1,
              false, false);
        } else {
          cameras = found;
          showModel(new ChooserModel(cameras, camera.camera(), getString(R.string.overlay_close)));
        }
      });
    }, "overlay-camera-list").start();
  }

  /** Every camera on the server, as the grid shows them before a selection is made. */
  private static List<String> loadCameras() {
    try {
      return new Config().getCameras();
    } catch (Exception e) {
      Log.w(TAG, "Camera list failed: " + e.getClass().getSimpleName());
      return null;
    }
  }

  @RequiresApi(22)
  private void showModel(ChooserModel m) {
    model = m;
    String title = m.hasCameras()
        ? getString(R.string.overlay_title)
        : getString(R.string.no_cameras_selected);
    int[] w = m.window(chooser.maxRows(MAX_ROWS));
    chooser.show(title, m.rows().subList(w[0], w[1]), m.rows().isEmpty() ? -1 : m.index() - w[0],
        w[0] > 0, w[1] < m.rows().size());
  }

  @RequiresApi(22)
  private void move(int by) {
    if (model == null || model.rows().isEmpty()) return;
    restartTimeout();
    model.move(by);
    showModel(model);
  }

  @RequiresApi(22)
  private void select() {
    ChooserModel m = model;
    closeChooser();
    if (m == null) return;
    if (m.closeSelected()) {
      camera.close();
    } else if (m.selectedCamera() != null) {
      camera.open(m.selectedCamera());
    }
  }

  @RequiresApi(22)
  private void closeChooser() {
    main.removeCallbacks(chooserTimeout);
    loadToken++;
    if (chooser != null) chooser.close();
    model = null;
  }

  @RequiresApi(22)
  private void closeAll() {
    main.removeCallbacksAndMessages(holdTimerToken);
    gate.resetTrigger();
    closeChooser();
    if (camera != null) camera.close();
    // Ask the server again next time, in case cameras were added.
    cameras = null;
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    // Not used: the service only filters keys.
  }

  @Override
  public void onInterrupt() {
  }

  @Override
  public void onDestroy() {
    destroyed = true;
    if (running == this) running = null;
    getSharedPreferences(Settings.getInstance().PREF_FILE_NAME, MODE_PRIVATE)
        .unregisterOnSharedPreferenceChangeListener(triggerChanged);
    if (Build.VERSION.SDK_INT >= 22) closeAll();
    if (receiving) {
      unregisterReceiver(screenOff);
      receiving = false;
    }
    super.onDestroy();
  }
}
