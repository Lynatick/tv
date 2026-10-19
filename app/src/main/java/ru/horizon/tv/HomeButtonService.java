package ru.horizon.tv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import java.util.HashSet;
import java.util.Set;

/** Optional HOME fallback for TV firmware that gives its factory launcher system priority. */
public final class HomeButtonService extends AccessibilityService {
  private final Set<String> homeActivities = new HashSet<>();
  private final Set<String> homePackages = new HashSet<>();
  private long launchedAt;

  @Override
  protected void onServiceConnected() {
    Set<String> packages = new HashSet<>();
    for (ResolveInfo info :
        getPackageManager()
            .queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
      String pkg = info.activityInfo.packageName;
      if (pkg.equals(getPackageName())
          || pkg.equals("com.android.tv.settings")
          || pkg.equals("android")) continue;
      homeActivities.add(new ComponentName(pkg, info.activityInfo.name).flattenToString());
      homePackages.add(pkg);
      packages.add(pkg);
    }
    AccessibilityServiceInfo config = new AccessibilityServiceInfo();
    config.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
    config.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
    config.notificationTimeout = 120;
    config.packageNames =
        packages.isEmpty() ? new String[] {getPackageName()} : packages.toArray(new String[0]);
    setServiceInfo(config);
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        || event.getPackageName() == null
        || event.getClassName() == null) return;
    String pkg = event.getPackageName().toString(), activity = event.getClassName().toString();
    // Match only launcher activities; never inspect window content, controls or typed text.
    if (!homePackages.contains(pkg)
        || !homeActivities.contains(new ComponentName(pkg, activity).flattenToString())) return;
    if (SystemClock.elapsedRealtime() - launchedAt < 1200) return;
    launchedAt = SystemClock.elapsedRealtime();
    try {
      startActivity(
          new Intent(this, MainActivity.class)
              .setAction(Intent.ACTION_MAIN)
              .addCategory(Intent.CATEGORY_HOME)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    } catch (RuntimeException ignored) {
    }
  }

  @Override
  public void onInterrupt() {}
}
