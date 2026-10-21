package ru.horizon.tv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** A single reusable WebView with native, remote-friendly forecast controls. */
final class MeteoblueWeatherView extends LinearLayout {
  private final Preferences prefs;
  private final TextView title, status, day, week, expand, placeholder;
  private WebView web;
  private FrameLayout page;
  private boolean fullscreen;
  private WeatherClient.City city;
  private boolean active, dirty = true, loading;
  private long lastAttempt, lastSuccess;
  private String loadingUrl;
  private final String presentation;
  private int loadGeneration;

  MeteoblueWeatherView(
      Activity activity, Preferences prefs, Runnable chooseCity, Runnable toggleSize) {
    super(activity);
    this.prefs = prefs;
    try (InputStream stream = activity.getAssets().open("meteoblue-presentation.js");
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int count;
      while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
      presentation = new String(output.toByteArray(), StandardCharsets.UTF_8);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Weather presentation asset unavailable", error);
    }
    setOrientation(VERTICAL);
    setPadding(dp(12), dp(10), dp(12), dp(8));
    setBackgroundColor(Color.TRANSPARENT);
    title = label("Meteoblue", 14, 0xFFB5E4CD);
    title.setSingleLine(true);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    addView(title, new LayoutParams(-1, dp(24)));
    LinearLayout controls = new LinearLayout(activity);
    controls.setOrientation(HORIZONTAL);
    day = control("День", () -> selectPeriod("day"));
    week = control("Неделя", () -> selectPeriod("week"));
    expand = control("Развернуть", toggleSize);
    TextView[] buttons = {
      day, week, control("Город", chooseCity), expand, control("↻ Обновить", () -> refresh(true))
    };
    for (TextView button : buttons) {
      LayoutParams params = new LayoutParams(0, dp(32), button == buttons[4] ? 1.25f : 1);
      params.setMargins(dp(2), 0, dp(2), 0);
      controls.addView(button, params);
    }
    buttons[4].setContentDescription("Обновить прогноз Meteoblue");
    addView(controls, new LayoutParams(-1, dp(36)));
    page =
        new FrameLayout(activity) {
          @Override
          protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, heightSpec);
            if (web != null) {
              // Scale the native viewport without changing forecast values.
              float scale = fullscreen ? 1f : .7f;
              web.setPivotX(0);
              web.setPivotY(0);
              web.setScaleX(scale);
              web.setScaleY(scale);
              web.measure(
                  MeasureSpec.makeMeasureSpec(
                      Math.round(getMeasuredWidth() / scale), MeasureSpec.EXACTLY),
                  MeasureSpec.makeMeasureSpec(
                      Math.round(getMeasuredHeight() / scale), MeasureSpec.EXACTLY));
            }
          }
        };
    addView(page, new LayoutParams(-1, 0, 1));
    placeholder = label("Выберите город кнопкой «Город»", 15, 0xFFF0F5F1);
    placeholder.setGravity(Gravity.CENTER);
    placeholder.setPadding(dp(12), dp(12), dp(12), dp(12));
    try {
      web = new WebView(activity);
      web.setBackgroundColor(Color.TRANSPARENT);
      WebSettings settings = web.getSettings();
      settings.setJavaScriptEnabled(true);
      settings.setDomStorageEnabled(true);
      settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
      settings.setAllowFileAccess(false);
      settings.setAllowContentAccess(false);
      settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
      settings.setJavaScriptCanOpenWindowsAutomatically(false);
      settings.setSupportMultipleWindows(false);
      settings.setGeolocationEnabled(false);
      settings.setMediaPlaybackRequiresUserGesture(true);
      settings.setUseWideViewPort(true);
      settings.setLoadWithOverviewMode(true);
      settings.setBlockNetworkLoads(true);
      CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
      web.setContentDescription(
          "Прогноз Meteoblue. Стрелки — навигация; Назад — выйти из страницы.");
      web.setWebChromeClient(
          new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
              request.deny();
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(
                String origin, GeolocationPermissions.Callback callback) {
              callback.invoke(origin, false, false);
            }
          });
      web.setWebViewClient(
          new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
              if (MeteoblueWeather.allowedNavigation(request.getUrl().toString())) return false;
              Toast.makeText(
                      activity,
                      "В блоке открываются только страницы Meteoblue.",
                      Toast.LENGTH_SHORT)
                  .show();
              return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
              if (!active || !loading || !url.equals(loadingUrl) || !url.equals(view.getUrl()))
                return;
              int generation = loadGeneration;
              view.evaluateJavascript(
                  "(" + presentation + ")('" + period() + "')",
                  result -> {
                    if (!active
                        || !loading
                        || generation != loadGeneration
                        || !url.equals(loadingUrl)
                        || !url.equals(view.getUrl())) return;
                    if (!"true".equals(result)) {
                      loading = false;
                      web.setVisibility(INVISIBLE);
                      placeholder.setText(
                          "Не удалось отобразить прогноз. Нажмите «↻ Обновить» или обновите Horizon"
                              + " TV.");
                      placeholder.setVisibility(VISIBLE);
                      showStatus("Формат информера изменился или страница недоступна.");
                      return;
                    }
                    loading = false;
                    lastSuccess = System.currentTimeMillis();
                    placeholder.setVisibility(GONE);
                    web.setVisibility(VISIBLE);
                    showStatus("Стрелки — прокрутка · Назад — к кнопкам");
                  });
            }

            @Override
            public void onReceivedError(
                WebView view, WebResourceRequest request, WebResourceError error) {
              if (request.isForMainFrame()) pageError();
            }

            @Override
            public void onReceivedHttpError(
                WebView view, WebResourceRequest request, WebResourceResponse error) {
              if (request.isForMainFrame()) pageError();
            }
          });
      web.setOnKeyListener(
          (v, key, event) -> {
            if (key == KeyEvent.KEYCODE_DPAD_UP && web.getScrollY() == 0) {
              if (event.getAction() == KeyEvent.ACTION_DOWN) initialFocus().requestFocus();
              return true;
            }
            return false;
          });
      page.addView(web, new FrameLayout.LayoutParams(-1, -1));
    } catch (RuntimeException unavailable) {
      if (web != null) web.destroy();
      web = null;
      placeholder.setText(
          "Для встроенного прогноза нужен Android System WebView. Обновите или включите его в"
              + " настройках ТВ.");
    }
    page.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
    placeholder.setBackgroundColor(Color.TRANSPARENT);
    status = label("Город можно изменить в любой момент", 10, 0xFF9DB2B4);
    status.setMaxLines(2);
    addView(status, new LayoutParams(-1, dp(28)));
    updatePeriod();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private TextView label(String value, int size, int color) {
    TextView text = new TextView(getContext());
    text.setText(value);
    text.setTextSize(size);
    text.setTextColor(color);
    text.setIncludeFontPadding(false);
    return text;
  }

  private GradientDrawable shape(int color, int stroke) {
    GradientDrawable result = new GradientDrawable();
    result.setColor(color);
    result.setCornerRadius(dp(10));
    if (stroke != 0) result.setStroke(dp(2), stroke);
    return result;
  }

  private TextView control(String value, Runnable action) {
    TextView button = label(value, 12, 0xFFF0F5F1);
    button.setGravity(Gravity.CENTER);
    button.setFocusable(true);
    button.setFocusableInTouchMode(true);
    button.setClickable(true);
    StateListDrawable background = new StateListDrawable();
    background.addState(new int[] {android.R.attr.state_focused}, shape(0xFF34564E, 0xFFB5E4CD));
    background.addState(new int[] {android.R.attr.state_pressed}, shape(0xFF34564E, 0xFFB5E4CD));
    background.addState(new int[] {android.R.attr.state_selected}, shape(0xFF24443F, 0xFF7EA896));
    background.addState(new int[] {}, shape(0xFF12252D, 0));
    button.setBackground(background);
    button.setOnClickListener(v -> action.run());
    return button;
  }

  private String period() {
    return prefs.get("weather_period", prefs.get("yandex_period", "week")).equals("week")
        ? "week"
        : "day";
  }

  private void updatePeriod() {
    day.setSelected(period().equals("day"));
    week.setSelected(period().equals("week"));
  }

  private void selectPeriod(String value) {
    if (period().equals(value)) return;
    prefs.set("weather_period", value);
    updatePeriod();
    dirty = true;
    load();
  }

  View initialFocus() {
    return period().equals("week") ? week : day;
  }

  void setFullscreen(boolean value) {
    fullscreen = value;
    page.requestLayout();
    expand.setText(value ? "Свернуть" : "Развернуть");
  }

  void setCity(WeatherClient.City value) {
    boolean changed =
        city == null
            ? value != null
            : value == null || city.latitude != value.latitude || city.longitude != value.longitude;
    city = value;
    title.setText(value == null ? "Meteoblue" : "Meteoblue · " + value.name);
    if (changed) {
      if (web != null) web.stopLoading();
      loading = false;
      dirty = true;
      lastAttempt = lastSuccess = 0;
      if (web != null) web.setVisibility(value == null ? GONE : VISIBLE);
      if (web != null && value == null) web.loadUrl("about:blank");
    }
    placeholder.setVisibility(web == null || value == null ? VISIBLE : GONE);
    if (value == null) status.setText("Выберите город или автоматическое определение");
    else if (changed) showStatus("Выбран город: " + value.name);
    if (dirty) load();
  }

  void setActive(boolean value) {
    if (active == value) return;
    active = value;
    if (web == null) return;
    web.getSettings().setBlockNetworkLoads(!value);
    if (value) {
      web.resumeTimers();
      web.onResume();
      if (dirty) load();
    } else {
      if (loading) {
        dirty = true;
        loading = false;
        web.stopLoading();
      }
      web.onPause();
      web.pauseTimers();
    }
  }

  private void load() {
    if (!active || web == null || city == null) return;
    loading = true;
    loadGeneration++;
    dirty = false;
    lastAttempt = System.currentTimeMillis();
    showStatus("Загрузка прогноза Meteoblue…");
    placeholder.setText("Загрузка прогноза…");
    placeholder.setVisibility(VISIBLE);
    web.setVisibility(INVISIBLE);
    loadingUrl = MeteoblueWeather.url(city.latitude, city.longitude, period());
    web.loadUrl(loadingUrl);
  }

  void refresh(boolean manual) {
    if (web == null || city == null) return;
    long now = System.currentTimeMillis();
    if (manual && now - lastAttempt < 30000L) {
      Toast.makeText(
              getContext(), "Повторное обновление доступно через 30 секунд.", Toast.LENGTH_SHORT)
          .show();
      return;
    }
    dirty = true;
    load();
  }

  void tick(int interval) {
    if (!active || city == null || web == null) return;
    long now = System.currentTimeMillis();
    if (loading && now - lastAttempt > 45000L) {
      web.stopLoading();
      pageError();
    }
    if (interval > 0
        && !loading
        && now - lastAttempt >= 300000L
        && (lastSuccess == 0 || now - lastSuccess >= interval * 60000L)) refresh(false);
  }

  private void pageError() {
    if (!active) return;
    loading = false;
    web.setVisibility(INVISIBLE);
    placeholder.setText("Прогноз недоступен. Проверьте интернет и нажмите «↻ Обновить».");
    placeholder.setVisibility(VISIBLE);
    showStatus("Не удалось обновить. Проверьте сеть и нажмите «↻ Обновить».");
  }

  private void showStatus(String message) {
    String updated =
        lastSuccess == 0
            ? "Ещё не обновлялось"
            : "Обновлено "
                + new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
                    .format(new Date(lastSuccess));
    status.setText(updated + " · " + message);
  }

  boolean releaseWebFocus() {
    if (!active || web == null || !web.hasFocus()) return false;
    initialFocus().requestFocus();
    return true;
  }

  void destroy() {
    if (web != null) {
      web.stopLoading();
      if (web.getParent() instanceof android.view.ViewGroup)
        ((android.view.ViewGroup) web.getParent()).removeView(web);
      web.destroy();
      web = null;
    }
  }
}
