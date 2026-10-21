package ru.horizon.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.http.HttpResponseCache;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class MainActivity extends Activity {
  private static final int WHITE = 0xFFF0F5F1, MUTED = 0xFF9DB2B4, ACCENT = 0xFFB5E4CD;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newFixedThreadPool(2);
  private Preferences prefs;
  private FrameLayout root;
  private LinearLayout content;
  private TextView clock, date, network;
  private MeteoblueWeatherView weatherView;
  private WeatherClient.City weatherCity;
  private final List<AppEntry> apps = new ArrayList<>();
  private String screen = "home", weatherError = "";
  private boolean resumed, loading;
  private int weatherGeneration;
  private long lastAttempt;
  private AlertDialog activeDialog;
  private View firstAppFocus, preferredAppFocus, emptyAppFocus;
  private String focusedAppId = "", installedVersion = "";
  private TextView updateButton;
  private UpdateClient.Release availableUpdate;
  private JSONObject latestUpdateJson;
  private boolean checkingUpdates, verifyingUpdate, waitingForInstallPermission;
  private long lastUpdateAttempt;
  private final BroadcastReceiver packages =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          loadApps();
          render();
        }
      };
  private final Runnable tick =
      new Runnable() {
        @Override
        public void run() {
          if (!resumed) return;
          updateClock();
          updateNetwork();
          checkForUpdates(false);
          updateDownloadStatus();
          boolean weatherVisible =
              screen.equals("weather")
                  || screen.equals("home") && prefs.flag("weather_enabled", true);
          if (weatherView != null && weatherVisible) weatherView.tick(prefs.number("interval", 30));
          if (weatherVisible
              && prefs.get("location_mode", "unset").equals("auto")
              && !loading
              && System.currentTimeMillis() - prefs.data.getLong("auto_time", 0) >= 3600000L
              && System.currentTimeMillis() - lastAttempt >= 300000L) refreshWeather(false);
          handler.postDelayed(this, 1000);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    UiModeManager ui = (UiModeManager) getSystemService(UI_MODE_SERVICE);
    if (ui == null || ui.getCurrentModeType() != Configuration.UI_MODE_TYPE_TELEVISION) {
      new AlertDialog.Builder(this)
          .setTitle("Horizon TV")
          .setMessage("Это приложение предназначено только для Android TV и Google TV.")
          .setPositiveButton("Закрыть", (d, w) -> finish())
          .setCancelable(false)
          .show();
      return;
    }
    prefs = new Preferences(this);
    focusedAppId = prefs.get("last_app", "");
    try {
      installedVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
      latestUpdateJson = new JSONObject(prefs.get("update_release", "{}"));
      availableUpdate = UpdateClient.parse(latestUpdateJson, installedVersion);
    } catch (Exception ignored) {
      latestUpdateJson = null;
    }
    if (pendingUpdate() == null) clearUpdateDownload();
    try {
      HttpResponseCache.install(new File(getCacheDir(), "http"), 8 * 1024 * 1024);
    } catch (Exception ignored) {
    }
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    loadApps();
    loadWeather();
    render();
    IntentFilter filter = new IntentFilter();
    filter.addAction(Intent.ACTION_PACKAGE_ADDED);
    filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
    filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
    filter.addDataScheme("package");
    registerReceiver(packages, filter);
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (prefs == null) return;
    resumed = true;
    loadApps();
    render();
    handler.removeCallbacks(tick);
    handler.post(tick);
    if (waitingForInstallPermission) {
      waitingForInstallPermission = false;
      if (getPackageManager().canRequestPackageInstalls()) installDownloadedUpdate();
      else toast("Установка не разрешена. Разрешение можно включить при следующей попытке.");
    }
    if ((screen.equals("weather") || screen.equals("home") && prefs.flag("weather_enabled", true))
        && !prefs.get("location_mode", "unset").equals("unset")
        && (weatherCity == null || prefs.get("location_mode", "unset").equals("auto")))
      refreshWeather(false);
  }

  @Override
  protected void onPause() {
    super.onPause();
    resumed = false;
    if (weatherView != null) weatherView.setActive(false);
    handler.removeCallbacks(tick);
  }

  @Override
  protected void onDestroy() {
    if (prefs != null) unregisterReceiver(packages);
    weatherGeneration++;
    if (weatherView != null) weatherView.destroy();
    worker.shutdownNow();
    handler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (prefs != null) {
      if (activeDialog != null) activeDialog.dismiss();
      screen = "home";
      render();
    }
  }

  @Override
  public void onBackPressed() {
    if (prefs == null) {
      super.onBackPressed();
      return;
    }
    if (weatherView != null && weatherView.releaseWebFocus()) return;
    screen = "home";
    render();
  }

  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_MENU && prefs != null) {
      showSettings();
      return true;
    }
    return super.onKeyUp(keyCode, event);
  }

  private static final class AppEntry {
    final String id, name;
    final ResolveInfo info;

    AppEntry(ResolveInfo info, Context context) {
      this.info = info;
      id =
          new ComponentName(info.activityInfo.packageName, info.activityInfo.name)
              .flattenToString();
      name = info.loadLabel(context.getPackageManager()).toString();
    }
  }

  private void loadApps() {
    Map<String, AppEntry> found = new LinkedHashMap<>();
    for (String category :
        new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
      List<ResolveInfo> entries =
          getPackageManager()
              .queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0);
      for (ResolveInfo info : entries) {
        if (info.activityInfo.packageName.equals(getPackageName())
            || !info.activityInfo.enabled
            || !info.activityInfo.exported) continue;
        // Prefer a TV entry when a package exposes both TV and mobile activities.
        if (!found.containsKey(info.activityInfo.packageName))
          found.put(info.activityInfo.packageName, new AppEntry(info, this));
      }
    }
    apps.clear();
    apps.addAll(found.values());
    apps.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
    if (!prefs.flag("favorites_initialized", false) && !apps.isEmpty()) {
      List<String> ids = new ArrayList<>();
      for (AppEntry app : apps) if (ids.size() < 5) ids.add(app.id);
      prefs.favorites(ids);
    }
  }

  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private LinearLayout column() {
    LinearLayout view = new LinearLayout(this);
    view.setOrientation(LinearLayout.VERTICAL);
    return view;
  }

  private LinearLayout row() {
    LinearLayout view = new LinearLayout(this);
    view.setOrientation(LinearLayout.HORIZONTAL);
    view.setGravity(Gravity.CENTER_VERTICAL);
    return view;
  }

  private TextView text(String value, float size, int color) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setIncludeFontPadding(false);
    return view;
  }

  private void add(LinearLayout parent, View child, int width, int height) {
    parent.addView(
        child,
        new LinearLayout.LayoutParams(
            width < 0 ? width : dp(width), height < 0 ? height : dp(height)));
  }

  private void gap(LinearLayout parent, int size) {
    View spacer = new View(this);
    add(parent, spacer, size, size);
  }

  private GradientDrawable shape(int color, int stroke, int radius) {
    GradientDrawable background = new GradientDrawable();
    background.setColor(color);
    background.setCornerRadius(dp(radius));
    if (stroke != 0) background.setStroke(dp(2), stroke);
    return background;
  }

  private void interactive(View view, Runnable action) {
    view.setFocusable(true);
    view.setClickable(true);
    StateListDrawable background = new StateListDrawable();
    background.addState(new int[] {android.R.attr.state_focused}, shape(0xFF24443F, ACCENT, 14));
    background.addState(new int[] {android.R.attr.state_pressed}, shape(0xFF34564E, ACCENT, 14));
    background.addState(new int[] {}, shape(0xB31B3038, 0, 14));
    view.setBackground(background);
    view.setOnClickListener(v -> action.run());
    view.setOnFocusChangeListener(
        (v, focused) -> {
          v.setElevation(focused ? dp(8) : 0);
        });
  }

  private TextView button(String title, Runnable action) {
    TextView view = text(title, 14, WHITE);
    view.setGravity(Gravity.CENTER);
    view.setPadding(dp(16), dp(12), dp(16), dp(12));
    interactive(view, action);
    return view;
  }

  private void render() {
    if (prefs == null || isFinishing()) return;
    firstAppFocus = preferredAppFocus = emptyAppFocus = null;
    if (weatherView != null) {
      weatherView.setActive(false);
      if (weatherView.getParent() instanceof ViewGroup)
        ((ViewGroup) weatherView.getParent()).removeView(weatherView);
    }
    root = new FrameLayout(this);
    root.addView(
        new SceneView(this, prefs.get("theme", "forest")), new FrameLayout.LayoutParams(-1, -1));
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setVerticalScrollBarEnabled(false);
    content = column();
    content.setPadding(dp(44), dp(24), dp(44), dp(20));
    scroll.addView(content);
    root.addView(scroll);
    setContentView(root);
    LinearLayout header = row();
    TextView home =
        button(
            "Главная",
            () -> {
              screen = "home";
              render();
            });
    add(header, home, 108, 40);
    gap(header, 8);
    add(
        header,
        button(
            "Приложения",
            () -> {
              screen = "apps";
              render();
            }),
        138,
        40);
    View space = new View(this);
    header.addView(space, new LinearLayout.LayoutParams(0, 1, 1));
    network = text("", 12, MUTED);
    add(header, network, -2, -2);
    gap(header, 12);
    updateButton = button("↓", this::showUpdate);
    updateButton.setTextSize(22);
    updateButton.setPadding(0, 0, 0, 0);
    add(header, updateButton, 44, 40);
    updateUpdateButton();
    gap(header, 18);
    add(header, button("⚙  Настройки", this::showSettings), 138, 40);
    add(content, header, -1, 44);
    gap(content, screen.equals("home") && prefs.flag("weather_enabled", true) ? 8 : 24);
    if (screen.equals("apps")) renderApps();
    else if (screen.equals("weather")) renderWeatherPage();
    else renderHome();
    updateClock();
    updateNetwork();
    updateWeatherCard();
    View focus =
        screen.equals("weather") && weatherView != null
            ? weatherView.initialFocus()
            : preferredAppFocus != null
                ? preferredAppFocus
                : firstAppFocus != null
                    ? firstAppFocus
                    : emptyAppFocus != null ? emptyAppFocus : home;
    FrameLayout renderedRoot = root;
    focus.requestFocus();
    focus.post(
        () -> {
          if (root == renderedRoot
              && resumed
              && (activeDialog == null || !activeDialog.isShowing())) focus.requestFocus();
        });
  }

  private void renderHome() {
    LinearLayout hero = row();
    hero.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout timeBlock = column();
    clock = text("", 88, WHITE);
    clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
    add(timeBlock, clock, -1, -2);
    date = text("", 17, MUTED);
    add(timeBlock, date, -1, 30);
    boolean showWeather = prefs.flag("weather_enabled", true);
    int height =
        showWeather
            ? Math.max(
                248,
                Math.min(
                    288,
                    (int)
                            (getResources().getDisplayMetrics().heightPixels
                                / getResources().getDisplayMetrics().density)
                        - 252))
            : 168;
    timeBlock.setGravity(Gravity.CENTER_VERTICAL);
    hero.addView(timeBlock, new LinearLayout.LayoutParams(0, dp(height), showWeather ? .32f : 1));
    if (showWeather) {
      ensureWeatherView();
      weatherView.setFullscreen(false);
      hero.addView(weatherView, new LinearLayout.LayoutParams(0, dp(height), .68f));
      weatherView.setCity(weatherCity);
      weatherView.setActive(resumed);
    }
    add(content, hero, -1, height);
    gap(content, showWeather ? 6 : 18);
    LinearLayout label = row();
    add(label, text("Избранные приложения", 19, WHITE), -2, -2);
    View fill = new View(this);
    label.addView(fill, new LinearLayout.LayoutParams(0, 1, 1));
    add(label, text("Удерживайте OK для изменения", 11, MUTED), -2, -2);
    gap(label, 12);
    TextView selectApps = button("✎  Выбрать", this::showAppPicker);
    selectApps.setTextSize(12);
    selectApps.setPadding(dp(12), dp(6), dp(12), dp(6));
    add(label, selectApps, 110, 32);
    add(content, label, -1, 36);
    gap(content, 10);
    List<String> favorites = prefs.favorites();
    List<AppEntry> selected = new ArrayList<>();
    for (String id : favorites) for (AppEntry app : apps) if (app.id.equals(id)) selected.add(app);
    renderGrid(selected, true);
    gap(content, 20);
    LinearLayout quick = row();
    String[] names = {
      "✎  Главный экран", "☷  Все приложения", "◉  Сеть", "♪  Звук", "▣  Экран", "⚙  Настройки ТВ"
    };
    Runnable[] actions = {
      this::showAppearance,
      () -> {
        screen = "apps";
        render();
      },
      () -> openSetting(Settings.ACTION_WIFI_SETTINGS),
      () -> openSetting(Settings.ACTION_SOUND_SETTINGS),
      () -> openSetting(Settings.ACTION_DISPLAY_SETTINGS),
      () -> openSetting(Settings.ACTION_SETTINGS)
    };
    for (int i = 0; i < names.length; i++) {
      TextView b = button(names[i], actions[i]);
      b.setTextSize(12);
      b.setPadding(dp(8), dp(12), dp(8), dp(12));
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1);
      if (i > 0) p.leftMargin = dp(8);
      quick.addView(b, p);
    }
    add(content, quick, -1, -2);
  }

  private void renderApps() {
    clock = null;
    date = null;
    TextView title = text("Все приложения", 32, WHITE);
    add(content, title, -1, 46);
    add(
        content,
        text(
            "OK — открыть  ·  Удерживать OK — добавить на главный экран или открыть сведения",
            13,
            MUTED),
        -1,
        36);
    renderGrid(apps, false);
  }

  private void renderGrid(List<AppEntry> entries, boolean favorites) {
    int columns = prefs.number("columns", 5);
    GridLayout grid = new GridLayout(this);
    grid.setColumnCount(columns);
    grid.setClipChildren(false);
    grid.setClipToPadding(false);
    for (AppEntry app : entries) addTile(grid, app, columns);
    if (favorites && entries.isEmpty()) {
      LinearLayout tile = column();
      tile.setGravity(Gravity.CENTER);
      add(tile, text("＋", 27, ACCENT), -2, 36);
      add(tile, text("Выбрать", 12, WHITE), -2, 24);
      interactive(tile, this::showAppPicker);
      tile.setFocusableInTouchMode(true);
      emptyAppFocus = tile;
      tile.setContentDescription("Выбрать приложения для главного экрана");
      grid.addView(tile, tileParams(columns));
    }
    add(content, grid, -1, -2);
  }

  private GridLayout.LayoutParams tileParams(int columns) {
    GridLayout.LayoutParams p = new GridLayout.LayoutParams();
    p.width = 0;
    p.height = dp(prefs.flag("app_labels", true) ? 94 : 78);
    p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
    p.setMargins(dp(3), dp(3), dp(7), dp(7));
    return p;
  }

  private void addTile(GridLayout grid, AppEntry app, int columns) {
    LinearLayout tile = column();
    tile.setGravity(Gravity.CENTER);
    tile.setPadding(dp(10), dp(8), dp(10), dp(7));
    ImageView icon = new ImageView(this);
    icon.setImageDrawable(app.info.loadIcon(getPackageManager()));
    add(tile, icon, 44, 44);
    gap(tile, 7);
    if (prefs.flag("app_labels", true)) {
      TextView label = text(app.name, 12, WHITE);
      label.setGravity(Gravity.CENTER);
      label.setMaxLines(1);
      label.setEllipsize(TextUtils.TruncateAt.END);
      add(tile, label, -1, 19);
    }
    tile.setContentDescription(app.name);
    interactive(tile, () -> launch(app));
    tile.setFocusableInTouchMode(true);
    tile.setOnFocusChangeListener(
        (v, focused) -> {
          v.setElevation(focused ? dp(8) : 0);
          if (focused) focusedAppId = app.id;
        });
    if (firstAppFocus == null) firstAppFocus = tile;
    if (app.id.equals(focusedAppId)) preferredAppFocus = tile;
    tile.setOnLongClickListener(
        v -> {
          showAppActions(app);
          return true;
        });
    grid.addView(tile, tileParams(columns));
  }

  private void launch(AppEntry app) {
    try {
      prefs.set("last_app", app.id);
      startActivity(
          new Intent(Intent.ACTION_MAIN)
              .addCategory(Intent.CATEGORY_LAUNCHER)
              .setComponent(ComponentName.unflattenFromString(app.id))
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    } catch (Exception e) {
      toast("Не удалось открыть приложение. Возможно, оно отключено или удалено.");
      loadApps();
      render();
    }
  }

  private void updateClock() {
    if (clock == null) return;
    String pattern = prefs.flag("clock24", true) ? "HH:mm" : "hh:mm a";
    if (prefs.flag("seconds", false))
      pattern = prefs.flag("clock24", true) ? "HH:mm:ss" : "hh:mm:ss a";
    clock.setText(new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date()));
    if (prefs.flag("seconds", false)) clock.setTextSize(prefs.flag("clock24", true) ? 72 : 56);
    else clock.setTextSize(prefs.flag("clock24", true) ? 88 : 68);
    date.setText(new SimpleDateFormat("EEEE, d MMMM", new Locale("ru")).format(new Date()));
  }

  private void updateNetwork() {
    if (network == null) return;
    ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
    NetworkCapabilities info =
        manager == null ? null : manager.getNetworkCapabilities(manager.getActiveNetwork());
    String title =
        info == null
            ? "Нет сети"
            : info.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ? "●  Ethernet"
                : info.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "●  Wi-Fi" : "●  Сеть";
    network.setText(title);
    network.setTextColor(info == null ? 0xFFEAB998 : ACCENT);
  }

  private LinearLayout panel(String title, String subtitle) {
    LinearLayout layout = column();
    layout.setPadding(dp(24), dp(20), dp(24), dp(20));
    layout.setBackground(shape(0xFF101F28, 0, 20));
    add(layout, text(title, 25, WHITE), -1, -2);
    gap(layout, 9);
    if (!subtitle.isEmpty()) {
      TextView explanation = text(subtitle, 13, MUTED);
      explanation.setLineSpacing(dp(3), 1);
      add(layout, explanation, -1, -2);
      gap(layout, 16);
    }
    return layout;
  }

  private AlertDialog dialog(LinearLayout layout) {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.addView(layout);
    scroll.setVerticalScrollBarEnabled(false);
    AlertDialog result = new AlertDialog.Builder(this).setView(scroll).create();
    result.show();
    result.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
    int available = (int) (getResources().getDisplayMetrics().widthPixels * .8f);
    result
        .getWindow()
        .setLayout(
            Math.min(dp(620), available),
            Math.min(dp(450), getResources().getDisplayMetrics().heightPixels - dp(48)));
    activeDialog = result;
    return result;
  }

  private void item(LinearLayout layout, String title, String subtitle, Runnable action) {
    LinearLayout item = column();
    item.setPadding(dp(16), dp(12), dp(16), dp(12));
    add(item, text(title, 16, WHITE), -1, -2);
    if (!subtitle.isEmpty()) {
      gap(item, 5);
      add(item, text(subtitle, 12, MUTED), -1, -2);
    }
    interactive(item, action);
    add(layout, item, -1, -2);
    gap(layout, 8);
  }

  private void showSettings() {
    LinearLayout layout =
        panel("Настройки", "Horizon TV " + installedVersion + " · Android TV / Google TV");
    final AlertDialog[] ref = new AlertDialog[1];
    item(
        layout,
        "Главный экран",
        "Тема, размер сетки, часы и подписи",
        () -> {
          ref[0].dismiss();
          showAppearance();
        });
    item(
        layout,
        "Выбрать приложения",
        "Закрепить нужные приложения и изменить порядок",
        () -> {
          ref[0].dismiss();
          showAppPicker();
        });
    item(
        layout,
        "Погода",
        "Meteoblue: город, день, неделя и частота обновления",
        () -> {
          ref[0].dismiss();
          showWeather();
        });
    item(
        layout,
        "Быстрые настройки ТВ",
        "Сеть, Bluetooth, изображение, звук и приложения",
        () -> {
          ref[0].dismiss();
          showTvSettings();
        });
    item(
        layout,
        isDefaultHome() ? "Основной лаунчер: Horizon TV" : "Сделать главным экраном",
        "Mi TV Stick 4K и другие Android TV · настройка с пульта",
        () -> {
          ref[0].dismiss();
          showHomeSetup();
        });
    item(
        layout,
        "Обновление Horizon TV",
        updateRelease() == null
            ? "Проверить наличие новой версии"
            : "Доступна версия " + updateRelease().version,
        () -> {
          ref[0].dismiss();
          if (updateRelease() == null) checkForUpdates(true);
          else showUpdate();
        });
    item(
        layout,
        "О приложении и данных",
        "Источники погоды, приватность и управление пультом",
        () -> {
          ref[0].dismiss();
          showAbout();
        });
    item(layout, "Закрыть", "Вернуться на главный экран", () -> ref[0].dismiss());
    ref[0] = dialog(layout);
  }

  private void showAppearance() {
    LinearLayout layout =
        panel("Главный экран", "Изменения сохраняются сразу. MENU на пульте открывает настройки.");
    final AlertDialog[] ref = new AlertDialog[1];
    item(
        layout,
        "Тема оформления",
        themeName(),
        () -> {
          ref[0].dismiss();
          choose(
              "Тема оформления",
              new String[] {"Лесной горизонт", "Вечерние горы", "Тёмный однотонный"},
              new String[] {"forest", "dusk", "plain"},
              "theme",
              this::showAppearance);
        });
    item(
        layout,
        "Приложений в строке: " + prefs.number("columns", 5),
        "4, 5 или 6 карточек",
        () -> {
          ref[0].dismiss();
          chooseNumber(
              "Приложений в строке",
              new String[] {"4", "5", "6"},
              new int[] {4, 5, 6},
              "columns",
              5,
              this::showAppearance);
        });
    item(
        layout,
        "Подписи приложений: " + yes(prefs.flag("app_labels", true)),
        "Показывать названия под значками",
        () -> {
          prefs.putFlag("app_labels", !prefs.flag("app_labels", true));
          ref[0].dismiss();
          render();
          showAppearance();
        });
    item(
        layout,
        "Формат часов: " + (prefs.flag("clock24", true) ? "24 часа" : "12 часов"),
        "Время и часовой пояс берутся из настроек телевизора",
        () -> {
          prefs.putFlag("clock24", !prefs.flag("clock24", true));
          ref[0].dismiss();
          render();
          showAppearance();
        });
    item(
        layout,
        "Секунды: " + yes(prefs.flag("seconds", false)),
        "Показывать секунды в часах",
        () -> {
          prefs.putFlag("seconds", !prefs.flag("seconds", false));
          ref[0].dismiss();
          render();
          showAppearance();
        });
    item(
        layout,
        "Выбрать приложения",
        "Выбор избранного и порядок карточек",
        () -> {
          ref[0].dismiss();
          showAppPicker();
        });
    item(layout, "Готово", "Вернуться на главный экран", () -> ref[0].dismiss());
    ref[0] = dialog(layout);
  }

  private String themeName() {
    String theme = prefs.get("theme", "forest");
    return theme.equals("dusk")
        ? "Вечерние горы"
        : theme.equals("plain") ? "Тёмный однотонный" : "Лесной горизонт";
  }

  private String yes(boolean value) {
    return value ? "включены" : "выключены";
  }

  private void choose(String title, String[] labels, String[] values, String key, Runnable back) {
    int selected = 0;
    for (int i = 0; i < values.length; i++)
      if (values[i].equals(prefs.get(key, values[0]))) selected = i;
    activeDialog =
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(
                labels,
                selected,
                (d, which) -> {
                  prefs.set(key, values[which]);
                  d.dismiss();
                  render();
                  back.run();
                })
            .setNegativeButton("Назад", (d, w) -> back.run())
            .show();
  }

  private void chooseNumber(
      String title, String[] labels, int[] values, String key, int fallback, Runnable back) {
    int selected = 0;
    for (int i = 0; i < values.length; i++)
      if (values[i] == prefs.number(key, fallback)) selected = i;
    activeDialog =
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(
                labels,
                selected,
                (d, which) -> {
                  prefs.putNumber(key, values[which]);
                  d.dismiss();
                  render();
                  back.run();
                })
            .setNegativeButton("Назад", (d, w) -> back.run())
            .show();
  }

  private void showAppPicker() {
    if (apps.isEmpty()) {
      toast("Нет доступных приложений");
      return;
    }
    List<String> selected = prefs.favorites();
    String[] labels = new String[apps.size()];
    boolean[] checks = new boolean[apps.size()];
    for (int i = 0; i < apps.size(); i++) {
      labels[i] = apps.get(i).name;
      checks[i] = selected.contains(apps.get(i).id);
    }
    activeDialog =
        new AlertDialog.Builder(this)
            .setTitle("Приложения на главном экране")
            .setMultiChoiceItems(
                labels,
                checks,
                (d, i, enabled) -> {
                  String id = apps.get(i).id;
                  if (enabled && !selected.contains(id)) selected.add(id);
                  else if (!enabled) selected.remove(id);
                })
            .setPositiveButton(
                "Сохранить",
                (d, w) -> {
                  prefs.favorites(selected);
                  render();
                })
            .setNeutralButton(
                "Порядок",
                (d, w) -> {
                  prefs.favorites(selected);
                  render();
                  showOrder();
                })
            .setNegativeButton("Отмена", null)
            .show();
  }

  private void showOrder() {
    LinearLayout layout =
        panel("Порядок приложений", "Выберите приложение, затем направление перемещения.");
    final AlertDialog[] ref = new AlertDialog[1];
    for (String id : prefs.favorites())
      for (AppEntry app : apps)
        if (app.id.equals(id))
          item(
              layout,
              app.name,
              "Переместить или убрать",
              () -> {
                ref[0].dismiss();
                showAppActions(app);
              });
    item(layout, "Готово", "Сохранено", () -> ref[0].dismiss());
    ref[0] = dialog(layout);
  }

  private void showAppActions(AppEntry app) {
    List<String> selected = prefs.favorites();
    boolean favorite = selected.contains(app.id);
    LinearLayout layout = panel(app.name, "Настройки карточки приложения");
    final AlertDialog[] ref = new AlertDialog[1];
    item(
        layout,
        favorite ? "Убрать с главного экрана" : "Добавить на главный экран",
        "Приложение останется в общем списке",
        () -> {
          if (favorite) selected.remove(app.id);
          else selected.add(app.id);
          prefs.favorites(selected);
          ref[0].dismiss();
          render();
        });
    if (favorite) {
      item(
          layout,
          "Переместить левее",
          "На одну позицию",
          () -> {
            int index = selected.indexOf(app.id);
            if (index > 0) Collections.swap(selected, index, index - 1);
            prefs.favorites(selected);
            ref[0].dismiss();
            render();
            showOrder();
          });
      item(
          layout,
          "Переместить правее",
          "На одну позицию",
          () -> {
            int index = selected.indexOf(app.id);
            if (index < selected.size() - 1) Collections.swap(selected, index, index + 1);
            prefs.favorites(selected);
            ref[0].dismiss();
            render();
            showOrder();
          });
    }
    item(
        layout,
        "Открыть приложение",
        "Запустить",
        () -> {
          ref[0].dismiss();
          launch(app);
        });
    item(
        layout,
        "Сведения о приложении",
        "Системные настройки приложения",
        () -> {
          ref[0].dismiss();
          try {
            startActivity(
                new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + app.info.activityInfo.packageName)));
          } catch (Exception e) {
            openSetting(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
          }
        });
    item(layout, "Закрыть", "Назад", () -> ref[0].dismiss());
    ref[0] = dialog(layout);
  }

  private boolean isDefaultHome() {
    ResolveInfo home =
        getPackageManager()
            .resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
    return home != null
        && home.activityInfo != null
        && getPackageName().equals(home.activityInfo.packageName);
  }

  private void showHomeSetup() {
    LinearLayout layout = panel("Основной лаунчер", "Mi TV Stick 4K / Android TV / Google TV");
    final AlertDialog[] ref = new AlertDialog[1];
    item(
        layout,
        homeFallbackEnabled()
            ? "Возврат по кнопке Домой: включён"
            : "Кнопка Домой на Mi TV Stick 4K",
        "Запасной режим без ADB через Специальные возможности",
        () -> {
          try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
          } catch (Exception e) {
            openSetting(Settings.ACTION_SETTINGS);
          }
        });
    TextView fallbackHelp =
        text(
            "Если штатный экран продолжает открываться, включите «Horizon TV: кнопка Домой» в"
                + " системном разделе «Специальные возможности». При установке APK Android может"
                + " сначала попросить разрешить ограниченные настройки в сведениях о"
                + " приложении.\n\n"
                + "Этот режим возвращает в Horizon TV при появлении заводского лаунчера, в том"
                + " числе после загрузки системы. Служба получает только события смены окон"
                + " штатного лаунчера; содержимое экрана и ввод не запрашивает. Отключается в том"
                + " же системном разделе.\n",
            13,
            MUTED);
    add(layout, fallbackHelp, -1, -2);
    gap(layout, 8);
    item(
        layout,
        isDefaultHome() ? "Horizon TV уже выбран основным" : "Сделать главным экраном",
        "Открыть системный выбор · без компьютера и ADB",
        () -> {
          ref[0].dismiss();
          chooseHome();
        });
    TextView help =
        text(
            "В системном окне выберите Horizon TV и подтвердите выбор. Если появится вариант"
                + " «Всегда», выберите его. Затем нажмите кнопку Домой на пульте.\n\n"
                + "Для Mi TV Stick 4K используется стандартный механизм Android TV. Назначение"
                + " сохраняется системой; при включении и нажатии Домой открывается выбранный"
                + " лаунчер.\n\n"
                + "Если прошивка Xiaomi или Google TV не разрешает сменить домашнее приложение,"
                + " обычный APK не может обойти это ограничение. В таком случае Horizon TV остаётся"
                + " доступен в списке приложений. Заводской лаунчер остаётся включённым.",
            14,
            MUTED);
    help.setLineSpacing(dp(3), 1);
    add(layout, help, -1, -2);
    gap(layout, 14);
    item(
        layout,
        "Системные настройки домашнего экрана",
        "Альтернативный системный выбор, если доступен",
        () -> {
          try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
          } catch (Exception e) {
            toast("В этой прошивке нет системного экрана выбора лаунчера.");
          }
        });
    item(layout, "Закрыть", "Вернуться на главный экран", () -> ref[0].dismiss());
    ref[0] = dialog(layout);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent result) {
    super.onActivityResult(requestCode, resultCode, result);
    if (requestCode == 41 && prefs != null) {
      toast(
          isDefaultHome()
              ? "Horizon TV выбран основным. Нажмите Домой на пульте."
              : "Главный экран не изменён. Выбор зависит от разрешений прошивки ТВ.");
      render();
    }
  }

  private void chooseHome() {
    if (isDefaultHome()) {
      toast("Horizon TV уже выбран главным экраном");
      return;
    }
    if (android.os.Build.VERSION.SDK_INT >= 29) {
      android.app.role.RoleManager roles = getSystemService(android.app.role.RoleManager.class);
      if (roles != null && roles.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
        if (roles.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
          toast(
              "Системная роль получена, но прошивка открывает штатный лаунчер. Включите режим"
                  + " кнопки Домой в Специальных возможностях.");
          showHomeSetup();
          return;
        }
        try {
          startActivityForResult(
              roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 41);
          return;
        } catch (Exception ignored) {
        }
      }
    }
    try {
      startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
    } catch (Exception e) {
      toast(
          "Откройте: Настройки ТВ → Приложения → Приложения по умолчанию → Главный экран. На"
              + " некоторых ТВ производитель ограничивает этот выбор.");
      openSetting(Settings.ACTION_SETTINGS);
    }
  }

  private boolean homeFallbackEnabled() {
    String enabled =
        Settings.Secure.getString(
            getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
    if (enabled == null) return false;
    ComponentName expected = new ComponentName(this, HomeButtonService.class);
    for (String value : enabled.split(":"))
      if (expected.equals(ComponentName.unflattenFromString(value))) return true;
    return false;
  }

  private void showTvSettings() {
    LinearLayout layout =
        panel(
            "Быстрые настройки ТВ",
            "Открываются системные панели телевизора. Доступность зависит от прошивки.");
    item(
        layout,
        "Wi-Fi и сеть",
        "Подключение к интернету",
        () -> openSetting(Settings.ACTION_WIFI_SETTINGS));
    item(
        layout,
        "Bluetooth и пульты",
        "Подключённые устройства",
        () -> openSetting(Settings.ACTION_BLUETOOTH_SETTINGS));
    item(
        layout,
        "Изображение",
        "Экран и параметры изображения",
        () -> openSetting(Settings.ACTION_DISPLAY_SETTINGS));
    item(
        layout,
        "Звук",
        "Аудиовыход и параметры звука",
        () -> openSetting(Settings.ACTION_SOUND_SETTINGS));
    item(
        layout,
        "Приложения",
        "Управление установленными приложениями",
        () -> openSetting(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
    item(
        layout,
        "Дата и время",
        "Время и часовой пояс",
        () -> openSetting(Settings.ACTION_DATE_SETTINGS));
    item(
        layout,
        "Все настройки ТВ",
        "Открыть системные настройки",
        () -> openSetting(Settings.ACTION_SETTINGS));
    dialog(layout);
  }

  private void openSetting(String action) {
    try {
      startActivity(new Intent(action));
    } catch (Exception e) {
      try {
        startActivity(new Intent(Settings.ACTION_SETTINGS));
        toast("На этом ТВ отдельная панель недоступна. Открыты общие настройки.");
      } catch (Exception unavailable) {
        toast("Прошивка телевизора не предоставляет эту панель настроек.");
      }
    }
  }

  private void showAbout() {
    LinearLayout layout =
        panel(
            "Horizon TV",
            "Версия " + installedVersion + " · Android 8.0 и новее · Только телевизоры");
    TextView details =
        text(
            "Пульт: стрелки — навигация, OK — выбор, удерживать OK — действия с приложением, MENU —"
                + " настройки, Назад — главный экран.\n\n"
                + "Погода: официальный информер Meteoblue рядом с часами на общем фоне. Кнопки"
                + " «День», «Неделя» и «Город» управляются с пульта. Кнопка «↻ Обновить» загружает"
                + " прогноз заново. Режим «День» показывает только сегодняшний прогноз по 3 часа;"
                + " координаты внутри информера скрыты. Показано время последней успешной загрузки,"
                + " а не выпуска прогноза. Meteoblue сохраняет ссылки и атрибуцию своей страницы;"
                + " она может содержать рекламу, cookies и проверку подключения.\n\n"
                + "Поиск городов: Open-Meteo / GeoNames. Автоматический город: ipwho.is по внешнему"
                + " IP. VPN может изменить результат. Название города передаётся сервису поиска,"
                + " координаты выбранного города — Meteoblue. Настройки и избранное хранятся на"
                + " ТВ.\n\n"
                + "Загрузка веб-прогноза приостанавливается, когда блок не виден. Частота"
                + " обновления управляет повторной загрузкой страницы; сам Meteoblue также"
                + " управляет актуальностью своих данных. В Horizon TV нет собственной рекламы и"
                + " аналитики.\n\n"
                + "Скачать и обновить: lynatick.github.io/tv/\n"
                + "Исходники: github.com/Lynatick/tv",
            14,
            WHITE);
    details.setLineSpacing(dp(4), 1);
    add(layout, details, -1, -2);
    dialog(layout);
  }

  private UpdateClient.Release pendingUpdate() {
    if (prefs.data.getLong("update_download_id", -1) == -1) return null;
    try {
      return UpdateClient.parse(
          new JSONObject(prefs.get("update_download_release", "{}")), installedVersion);
    } catch (Exception ignored) {
      return null;
    }
  }

  private UpdateClient.Release updateRelease() {
    UpdateClient.Release pending = pendingUpdate();
    return pending != null ? pending : availableUpdate;
  }

  private void updateUpdateButton() {
    if (updateButton == null) return;
    UpdateClient.Release release = updateRelease();
    boolean wasFocused = updateButton.hasFocus();
    updateButton.setVisibility(release == null ? View.GONE : View.VISIBLE);
    if (release != null) {
      updateButton.setText(verifyingUpdate ? "…" : "↓");
      updateButton.setContentDescription("Обновить Horizon TV до версии " + release.version);
    } else if (wasFocused && firstAppFocus != null) {
      firstAppFocus.requestFocus();
    }
  }

  private void checkForUpdates(boolean manual) {
    long now = System.currentTimeMillis();
    if (checkingUpdates) {
      if (manual) toast("Проверка обновлений уже выполняется…");
      return;
    }
    if (!manual
        && (now - lastUpdateAttempt < 15 * 60000L
            || now - prefs.data.getLong("update_checked", 0) < 6 * 60 * 60000L)) return;
    if (manual && now - lastUpdateAttempt < 30000L) {
      toast("Повторная проверка будет доступна через 30 секунд.");
      return;
    }
    checkingUpdates = true;
    lastUpdateAttempt = now;
    if (manual) toast("Проверка обновлений…");
    worker.execute(
        () -> {
          try {
            JSONObject json = UpdateClient.fetch();
            UpdateClient.Release release = UpdateClient.parse(json, installedVersion);
            handler.post(
                () -> {
                  if (isDestroyed()) return;
                  checkingUpdates = false;
                  latestUpdateJson = json;
                  availableUpdate = release;
                  prefs
                      .data
                      .edit()
                      .putString("update_release", json.toString())
                      .putLong("update_checked", System.currentTimeMillis())
                      .apply();
                  updateUpdateButton();
                  if (manual && resumed) {
                    if (updateRelease() != null) showUpdate();
                    else toast("Установлена последняя версия Horizon TV.");
                  }
                });
          } catch (Exception e) {
            handler.post(
                () -> {
                  if (isDestroyed()) return;
                  checkingUpdates = false;
                  if (manual && resumed)
                    toast("Не удалось проверить обновления. Проверьте доступ к сайту скачивания.");
                });
          }
        });
  }

  private DownloadManager downloads() {
    return (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
  }

  private int downloadStatus() {
    long id = prefs.data.getLong("update_download_id", -1);
    if (id == -1) return 0;
    DownloadManager manager = downloads();
    if (manager == null) return DownloadManager.STATUS_FAILED;
    try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
      if (cursor == null || !cursor.moveToFirst()) return DownloadManager.STATUS_FAILED;
      return cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
    } catch (Exception e) {
      return DownloadManager.STATUS_FAILED;
    }
  }

  private void updateDownloadStatus() {
    if (prefs.data.getLong("update_download_id", -1) == -1) return;
    int status = downloadStatus();
    if (status == DownloadManager.STATUS_FAILED) {
      clearUpdateDownload();
      updateUpdateButton();
      toast("Не удалось скачать обновление. Нажмите ↓, чтобы повторить.");
    } else if (status == DownloadManager.STATUS_SUCCESSFUL
        && !prefs.flag("update_download_notified", false)) {
      prefs.putFlag("update_download_notified", true);
      toast("Обновление загружено. Нажмите ↓ для установки.");
    }
  }

  private void showUpdate() {
    UpdateClient.Release release = updateRelease();
    if (release == null) {
      checkForUpdates(true);
      return;
    }
    int status = downloadStatus();
    boolean ready = status == DownloadManager.STATUS_SUCCESSFUL;
    boolean downloading =
        status == DownloadManager.STATUS_PENDING
            || status == DownloadManager.STATUS_RUNNING
            || status == DownloadManager.STATUS_PAUSED;
    LinearLayout layout =
        panel(
            "Обновление Horizon TV",
            "Установлена "
                + installedVersion
                + " · Доступна "
                + release.version
                + "\n\n"
                + (ready
                    ? "APK загружен. Перед установкой проверяется SHA-256."
                    : downloading
                        ? "APK скачивается. Если сеть недоступна, загрузка продолжится после"
                            + " подключения."
                        : "APK будет скачан из официального репозитория GitHub. Настройки"
                            + " сохранятся.")
                + "\nУстановка выполняется после подтверждения в системном окне Android.");
    final AlertDialog[] ref = new AlertDialog[1];
    if (!downloading)
      item(
          layout,
          ready ? "Установить обновление" : "Скачать обновление",
          String.format(
              Locale.getDefault(), "Версия %s · %.2f МБ", release.version, release.size / 1048576d),
          () -> {
            ref[0].dismiss();
            if (ready) installDownloadedUpdate();
            else downloadUpdate();
          });
    if (downloading || ready)
      item(
          layout,
          "Отменить загрузку",
          "Удалить скачанный файл обновления",
          () -> {
            ref[0].dismiss();
            clearUpdateDownload();
            updateUpdateButton();
          });
    item(layout, "Позже", "Вернуться на главный экран", () -> ref[0].dismiss());
    ref[0] = dialog(layout);
  }

  private void downloadUpdate() {
    if (availableUpdate == null || latestUpdateJson == null) return;
    clearUpdateDownload();
    try {
      DownloadManager manager = downloads();
      if (manager == null) throw new IllegalStateException("Downloads unavailable");
      DownloadManager.Request request =
          new DownloadManager.Request(Uri.parse(availableUpdate.url))
              .setTitle("Horizon TV " + availableUpdate.version)
              .setDescription("Обновление главного экрана")
              .setMimeType("application/vnd.android.package-archive")
              .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
              .setDestinationInExternalFilesDir(
                  this,
                  Environment.DIRECTORY_DOWNLOADS,
                  "horizon-tv-"
                      + availableUpdate.version
                      + "-"
                      + System.currentTimeMillis()
                      + ".apk");
      long id = manager.enqueue(request);
      prefs
          .data
          .edit()
          .putLong("update_download_id", id)
          .putString("update_download_release", latestUpdateJson.toString())
          .putBoolean("update_download_notified", false)
          .apply();
      toast("Скачивание обновления началось.");
    } catch (Exception e) {
      toast("Загрузка недоступна. APK можно скачать на lynatick.github.io/tv/");
    }
    updateUpdateButton();
  }

  private void clearUpdateDownload() {
    long id = prefs.data.getLong("update_download_id", -1);
    if (id != -1) {
      try {
        DownloadManager manager = downloads();
        if (manager != null) manager.remove(id);
      } catch (Exception ignored) {
      }
    }
    prefs
        .data
        .edit()
        .remove("update_download_id")
        .remove("update_download_release")
        .remove("update_download_notified")
        .apply();
  }

  private void installDownloadedUpdate() {
    if (verifyingUpdate) return;
    UpdateClient.Release release = pendingUpdate();
    if (release == null || downloadStatus() != DownloadManager.STATUS_SUCCESSFUL) {
      toast("Сначала скачайте обновление.");
      return;
    }
    if (!getPackageManager().canRequestPackageInstalls()) {
      try {
        waitingForInstallPermission = true;
        startActivity(
            new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName())));
        toast("Разрешите Horizon TV устанавливать обновления, затем нажмите «Назад».");
      } catch (Exception e) {
        waitingForInstallPermission = false;
        toast("Разрешите установку для Horizon TV в настройках безопасности телевизора.");
      }
      return;
    }
    long id = prefs.data.getLong("update_download_id", -1);
    verifyingUpdate = true;
    updateUpdateButton();
    worker.execute(
        () -> {
          try {
            DownloadManager manager = downloads();
            if (manager == null) throw new IllegalStateException("Downloads unavailable");
            try (InputStream input =
                new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    manager.openDownloadedFile(id))) {
              UpdateClient.verify(input, release);
            }
            Uri uri = manager.getUriForDownloadedFile(id);
            if (uri == null) throw new IllegalStateException("APK unavailable");
            handler.post(
                () -> {
                  if (isDestroyed()) return;
                  verifyingUpdate = false;
                  updateUpdateButton();
                  if (!resumed || id != prefs.data.getLong("update_download_id", -1)) return;
                  try {
                    startActivity(
                        new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                  } catch (Exception e) {
                    toast("Установщик недоступен. APK можно скачать на lynatick.github.io/tv/");
                  }
                });
          } catch (Exception e) {
            handler.post(
                () -> {
                  if (isDestroyed()) return;
                  verifyingUpdate = false;
                  if (id == prefs.data.getLong("update_download_id", -1)) clearUpdateDownload();
                  updateUpdateButton();
                  if (resumed) toast("Не удалось проверить APK. Скачайте обновление ещё раз.");
                });
          }
        });
  }

  private void toast(String message) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
  }

  private void ensureWeatherView() {
    if (weatherView == null)
      weatherView =
          new MeteoblueWeatherView(
              this,
              prefs,
              this::showLocation,
              () -> {
                screen = screen.equals("weather") ? "home" : "weather";
                render();
              });
  }

  private void renderWeatherPage() {
    clock = date = null;
    ensureWeatherView();
    weatherView.setFullscreen(true);
    int height =
        Math.max(
            240,
            (int)
                    (getResources().getDisplayMetrics().heightPixels
                        / getResources().getDisplayMetrics().density)
                - 130);
    add(content, weatherView, -1, height);
    weatherView.setCity(weatherCity);
    weatherView.setActive(resumed);
  }

  private void loadWeather() {
    weatherCity = null;
    try {
      if (prefs.get("location_mode", "unset").equals("manual")) {
        weatherCity =
            new WeatherClient.City(
                prefs.get("city", ""),
                "",
                Double.parseDouble(prefs.get("latitude", "")),
                Double.parseDouble(prefs.get("longitude", "")));
      } else if (prefs.get("location_mode", "unset").equals("auto")) {
        JSONObject city = new JSONObject(prefs.get("auto_city", "{}"));
        weatherCity =
            new WeatherClient.City(
                city.getString("name"), "", city.getDouble("lat"), city.getDouble("lon"));
      }
    } catch (Exception ignored) {
      weatherCity = null;
    }
  }

  private void updateWeatherCard() {
    if (weatherView != null) weatherView.setCity(weatherCity);
  }

  private void invalidateWeather() {
    weatherGeneration++;
    loading = false;
    weatherError = "";
    lastAttempt = 0;
    loadWeather();
    updateWeatherCard();
  }

  private void refreshWeather(boolean manual) {
    String mode = prefs.get("location_mode", "unset");
    if (mode.equals("unset")) {
      if (manual) showLocation();
      return;
    }
    if (mode.equals("manual")) {
      loadWeather();
      updateWeatherCard();
      if (manual && weatherView != null) weatherView.refresh(true);
      return;
    }
    if (loading) {
      if (manual) toast("Определяем город…");
      return;
    }
    if (!manual
        && weatherCity != null
        && System.currentTimeMillis() - prefs.data.getLong("auto_time", 0) < 3600000L) return;
    if (manual && System.currentTimeMillis() - lastAttempt < 30000L) {
      toast("Повторное определение доступно через 30 секунд");
      return;
    }
    int generation = ++weatherGeneration;
    loading = true;
    lastAttempt = System.currentTimeMillis();
    worker.execute(
        () -> {
          try {
            WeatherClient.City resolved = WeatherClient.automatic();
            JSONObject saved =
                new JSONObject()
                    .put("name", resolved.name)
                    .put("lat", resolved.latitude)
                    .put("lon", resolved.longitude);
            handler.post(
                () -> {
                  if (isDestroyed() || generation != weatherGeneration) return;
                  loading = false;
                  weatherError = "";
                  weatherCity = resolved;
                  prefs.set("auto_city", saved.toString());
                  prefs.data.edit().putLong("auto_time", System.currentTimeMillis()).apply();
                  updateWeatherCard();
                });
          } catch (Exception e) {
            handler.post(
                () -> {
                  if (isDestroyed() || generation != weatherGeneration) return;
                  loading = false;
                  weatherError =
                      "Не удалось определить город. Выберите его вручную или проверьте сеть.";
                  if (manual) toast(weatherError);
                });
          }
        });
  }

  private void showWeather() {
    LinearLayout layout = panel("Meteoblue", "Встроенная страница прогноза без API-ключа");
    final AlertDialog[] ref = new AlertDialog[1];
    String mode = prefs.get("location_mode", "unset");
    String city =
        mode.equals("unset")
            ? "Город ещё не выбран"
            : mode.equals("auto") ? "Автоматически по IP" : prefs.get("city", "");
    item(
        layout,
        "Город",
        weatherCity == null ? city : weatherCity.name,
        () -> {
          ref[0].dismiss();
          showLocation();
        });
    item(
        layout,
        "Прогноз на день",
        "Прогноз по 3 часа только на сегодня",
        () -> {
          ref[0].dismiss();
          openWeatherPeriod("day");
        });
    item(
        layout,
        "Прогноз на неделю",
        "Ближайшие 7 дней на странице Meteoblue",
        () -> {
          ref[0].dismiss();
          openWeatherPeriod("week");
        });
    int interval = prefs.number("interval", 30);
    item(
        layout,
        "Частота обновления",
        interval == 0 ? "Только вручную" : "Каждые " + interval + " мин",
        () -> {
          ref[0].dismiss();
          chooseNumber(
              "Обновлять страницу прогноза",
              new String[] {
                "Каждые 15 минут",
                "Каждые 30 минут",
                "Каждый час",
                "Каждые 2 часа",
                "Каждые 6 часов",
                "Только вручную"
              },
              new int[] {15, 30, 60, 120, 360, 0},
              "interval",
              30,
              this::showWeather);
        });
    item(
        layout,
        "Погода на главной: " + yes(prefs.flag("weather_enabled", true)),
        "Показывать встроенный прогноз",
        () -> {
          prefs.putFlag("weather_enabled", !prefs.flag("weather_enabled", true));
          ref[0].dismiss();
          render();
          showWeather();
        });
    item(
        layout,
        "Обновить сейчас",
        "Перезагрузить страницу Meteoblue",
        () -> {
          ref[0].dismiss();
          if (prefs.get("location_mode", "unset").equals("unset")) showLocation();
          else {
            refreshWeather(true);
            if (!prefs.get("location_mode", "unset").equals("manual") && weatherView != null)
              weatherView.refresh(true);
          }
        });
    if (!weatherError.isEmpty()) add(layout, text(weatherError, 13, 0xFFEAB998), -1, -2);
    item(layout, "Готово", "Сохранено", () -> ref[0].dismiss());
    ref[0] = dialog(layout);
  }

  private void openWeatherPeriod(String period) {
    prefs.set("weather_period", period);
    if (weatherView != null) {
      weatherView.destroy();
      weatherView = null;
    }
    screen = "weather";
    render();
  }

  private void showLocation() {
    LinearLayout layout =
        panel(
            "Местоположение",
            "На телевизорах без GPS город можно определить по внешнему IP. При VPN результат может"
                + " отличаться от вашего города.");
    final AlertDialog[] ref = new AlertDialog[1];
    item(
        layout,
        "Определить автоматически",
        "Сервис ipwho.is получит внешний IP подключения",
        () -> {
          prefs.set("location_mode", "auto");
          prefs.set("auto_city", "");
          invalidateWeather();
          ref[0].dismiss();
          render();
          refreshWeather(true);
        });
    item(
        layout,
        "Выбрать город",
        "Поиск по названию города",
        () -> {
          ref[0].dismiss();
          searchCity();
        });
    item(layout, "Отмена", "Назад", () -> ref[0].dismiss());
    ref[0] = dialog(layout);
  }

  private void searchCity() {
    LinearLayout layout =
        panel("Выбрать город", "Введите хотя бы 2 буквы. Поиск: Open-Meteo / GeoNames.");
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setTextColor(WHITE);
    input.setTextSize(20);
    input.setHintTextColor(MUTED);
    input.setHint("Название города");
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
    add(layout, input, -1, 56);
    gap(layout, 12);
    TextView status = text("", 12, MUTED);
    add(layout, status, -1, -2);
    final AlertDialog[] ref = new AlertDialog[1];
    item(
        layout,
        "Найти",
        "Показать подходящие города",
        () -> {
          String query = input.getText().toString().trim();
          if (query.length() < 2) {
            status.setText("Введите хотя бы 2 буквы");
            return;
          }
          status.setText("Поиск…");
          InputMethodManager ime = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
          if (ime != null) ime.hideSoftInputFromWindow(input.getWindowToken(), 0);
          worker.execute(
              () -> {
                try {
                  List<WeatherClient.City> cities = WeatherClient.search(query);
                  handler.post(
                      () -> {
                        if (isDestroyed() || !ref[0].isShowing()) return;
                        if (cities.isEmpty()) {
                          status.setText(
                              "Город не найден. Попробуйте другое название или латиницу.");
                          return;
                        }
                        ref[0].dismiss();
                        showCities(cities);
                      });
                } catch (Exception e) {
                  handler.post(
                      () -> {
                        if (!isDestroyed() && ref[0].isShowing())
                          status.setText(
                              "Поиск недоступен. Проверьте интернет и попробуйте снова.");
                      });
                }
              });
        });
    item(layout, "Отмена", "Назад", () -> ref[0].dismiss());
    ref[0] = dialog(layout);
    ref[0].getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    input.requestFocus();
  }

  private void showCities(List<WeatherClient.City> cities) {
    LinearLayout layout = panel("Результаты поиска", "Выберите город с нужным регионом");
    final AlertDialog[] ref = new AlertDialog[1];
    for (WeatherClient.City city : cities)
      item(
          layout,
          city.name,
          city.detail,
          () -> {
            prefs.set("location_mode", "manual");
            prefs.set("city", city.name);
            prefs.set("latitude", String.valueOf(city.latitude));
            prefs.set("longitude", String.valueOf(city.longitude));
            invalidateWeather();
            ref[0].dismiss();
            render();
          });
    item(
        layout,
        "Другой поиск",
        "Изменить название",
        () -> {
          ref[0].dismiss();
          searchCity();
        });
    ref[0] = dialog(layout);
  }
}
