package ru.horizon.tv;

import java.net.URI;
import java.util.Locale;

/** Navigation only: forecasts stay on the original Yandex page, with its branding intact. */
final class YandexWeather {
  static String url(double latitude, double longitude, String period) {
    if (!Double.isFinite(latitude)
        || !Double.isFinite(longitude)
        || Math.abs(latitude) > 90
        || Math.abs(longitude) > 180) throw new IllegalArgumentException("Invalid location");
    if (!period.equals("day") && !period.equals("week"))
      throw new IllegalArgumentException("Invalid forecast period");
    return String.format(
        Locale.ROOT,
        "https://yandex.ru/pogoda/ru/details/%s?lat=%.6f&lon=%.6f",
        period.equals("week") ? "7-day-weather" : "today",
        latitude,
        longitude);
  }

  static boolean allowedNavigation(String value) {
    try {
      URI uri = new URI(value);
      String host = uri.getHost();
      String path =
          uri.getPath() == null
              ? null
              : new URI(null, null, uri.getPath(), null).normalize().getPath();
      return "https".equalsIgnoreCase(uri.getScheme())
          && uri.getUserInfo() == null
          && (uri.getPort() == -1 || uri.getPort() == 443)
          && ("yandex.ru".equalsIgnoreCase(host) || "www.yandex.ru".equalsIgnoreCase(host))
          && path != null
          && !path.contains("\\")
          && (path.equals("/pogoda")
              || path.startsWith("/pogoda/")
              || path.equals("/showcaptcha")
              || path.startsWith("/showcaptcha/")
              || path.equals("/checkcaptcha"));
    } catch (Exception ignored) {
      return false;
    }
  }
}
