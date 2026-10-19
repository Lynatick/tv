package ru.horizon.tv;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

final class Preferences {
  final SharedPreferences data;

  Preferences(Context context) {
    data = context.getSharedPreferences("horizon", Context.MODE_PRIVATE);
    if (!data.contains("provider")) data.edit().putString("provider", "met").apply();
  }

  String get(String key, String fallback) {
    return data.getString(key, fallback);
  }

  void set(String key, String value) {
    data.edit().putString(key, value).apply();
  }

  boolean flag(String key, boolean fallback) {
    return data.getBoolean(key, fallback);
  }

  void putFlag(String key, boolean value) {
    data.edit().putBoolean(key, value).apply();
  }

  int number(String key, int fallback) {
    return data.getInt(key, fallback);
  }

  void putNumber(String key, int value) {
    data.edit().putInt(key, value).apply();
  }

  List<String> favorites() {
    List<String> result = new ArrayList<>();
    try {
      JSONArray array = new JSONArray(get("favorites", "[]"));
      for (int i = 0; i < array.length(); i++)
        if (!result.contains(array.getString(i))) result.add(array.getString(i));
    } catch (Exception ignored) {
      /* A malformed saved list behaves as an empty list. */
    }
    return result;
  }

  void favorites(List<String> entries) {
    set("favorites", new JSONArray(entries).toString());
    putFlag("favorites_initialized", true);
  }

  String locationKey() {
    return get("location_mode", "unset").equals("auto")
        ? "auto"
        : get("latitude", "") + "," + get("longitude", "");
  }

  String weatherKey() {
    return get("provider", "openmeteo") + ":" + locationKey();
  }
}
