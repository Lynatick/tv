package ru.horizon.tv;

import java.net.URI;
import java.util.Locale;

/** Official widgets; the view applies a compact presentation without changing forecast values. */
final class MeteoblueWeather {
  static String url(double latitude, double longitude, String period) {
    if (!Double.isFinite(latitude)
        || !Double.isFinite(longitude)
        || Math.abs(latitude) > 90
        || Math.abs(longitude) > 180) throw new IllegalArgumentException("Invalid location");
    if (!"day".equals(period) && !"week".equals(period))
      throw new IllegalArgumentException("Invalid forecast period");
    return String.format(
        Locale.ROOT,
        "https://www.meteoblue.com/ru/weather/widget/%s/%.6fN%.6fE"
            + "?geoloc=fixed&tempunit=CELSIUS&windunit=METER_PER_SECOND&precipunit=MILLIMETER"
            + "&days=%d&coloured=monochrome&pictoicon=1&maxtemperature=1&mintemperature=1"
            + "&windspeed=0&windgust=0&winddirection=0&uv=0&humidity=0&precipitation=0"
            + "&precipitationprobability=0&spot=0&pressure=0&layout=dark",
        period.equals("week") ? "daily" : "three",
        latitude,
        longitude,
        period.equals("week") ? 7 : 4);
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
          && ("meteoblue.com".equalsIgnoreCase(host) || "www.meteoblue.com".equalsIgnoreCase(host))
          && path != null
          && !path.contains("\\")
          && path.matches("/[a-z]{2}/weather/.*");
    } catch (Exception ignored) {
      return false;
    }
  }
}
