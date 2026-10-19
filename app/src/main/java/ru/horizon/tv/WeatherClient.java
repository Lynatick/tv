package ru.horizon.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class WeatherClient {
  static final class City {
    final String name, detail;
    final double latitude, longitude;

    City(String name, String detail, double latitude, double longitude) {
      if (!Double.isFinite(latitude)
          || !Double.isFinite(longitude)
          || Math.abs(latitude) > 90
          || Math.abs(longitude) > 180) throw new IllegalArgumentException("Invalid location");
      this.name = name;
      this.detail = detail;
      this.latitude = latitude;
      this.longitude = longitude;
    }
  }

  static JSONObject get(String url) throws Exception {
    return request(url, null, null);
  }

  static JSONObject request(String url, String yandexKey, String body) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    if (url.contains("key=") || yandexKey != null) {
      connection.setUseCaches(false);
      connection.setRequestProperty("Cache-Control", "no-store");
    }
    connection.setConnectTimeout(12000);
    connection.setReadTimeout(15000);
    connection.setRequestProperty("User-Agent", "HorizonTV/1.0 (https://github.com/Lynatick/tv)");
    connection.setRequestProperty("Accept", "application/json");
    if (yandexKey != null) connection.setRequestProperty("X-Yandex-Weather-Key", yandexKey);
    try {
      if (body != null) {
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (java.io.OutputStream out = connection.getOutputStream()) {
          out.write(body.getBytes(StandardCharsets.UTF_8));
        }
      }
      int code = connection.getResponseCode();
      if (code != 200)
        throw new IOException(
            code == 429
                ? "Сервис ограничил частоту запросов. Попробуйте позже."
                : code == 401 || code == 403
                    ? "Проверьте ключ API, доступ к сервису и тариф"
                    : "Сервис недоступен (HTTP " + code + ")");
      try (InputStream stream = connection.getInputStream();
          ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
          output.write(buffer, 0, count);
          if (output.size() > 3000000) throw new IOException("Слишком большой ответ сервиса");
        }
        return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
      }
    } finally {
      connection.disconnect();
    }
  }

  static List<City> search(String query) throws Exception {
    JSONObject root =
        get(
            "https://geocoding-api.open-meteo.com/v1/search?count=8&language=ru&format=json&name="
                + URLEncoder.encode(query, "UTF-8"));
    List<City> result = new ArrayList<>();
    JSONArray items = root.optJSONArray("results");
    if (items != null)
      for (int i = 0; i < items.length(); i++) {
        JSONObject item = items.getJSONObject(i);
        result.add(
            new City(
                item.getString("name"),
                item.optString("admin1") + ", " + item.optString("country"),
                item.getDouble("latitude"),
                item.getDouble("longitude")));
      }
    return result;
  }

  static City automatic() throws Exception {
    JSONObject root = get("https://ipwho.is/?lang=ru");
    if (!root.optBoolean("success", false))
      throw new IOException("Не удалось определить город. Выберите город вручную.");
    return new City(
        root.getString("city"),
        root.optString("country"),
        root.getDouble("latitude"),
        root.getDouble("longitude"));
  }

  static boolean requiresKey(String provider) {
    return provider.equals("google") || provider.equals("yandex") || provider.equals("weatherapi");
  }

  static String label(String provider) {
    switch (provider) {
      case "google":
        return "Google Weather";
      case "yandex":
        return "Яндекс Погода";
      case "weatherapi":
        return "WeatherAPI.com";
      case "met":
        return "MET Norway / Yr";
      default:
        return "Open-Meteo";
    }
  }

  static Weather fetch(String provider, City city, String apiKey) throws Exception {
    if (requiresKey(provider) && apiKey.isEmpty())
      throw new IllegalArgumentException("Введите ключ API выбранного источника");
    String lat = Weather.coordinate(city.latitude), lon = Weather.coordinate(city.longitude);
    String key = URLEncoder.encode(apiKey, "UTF-8");
    if (provider.equals("google")) {
      return Weather.google(
          get(
              "https://weather.googleapis.com/v1/currentConditions:lookup?location.latitude="
                  + lat
                  + "&location.longitude="
                  + lon
                  + "&unitsSystem=METRIC&languageCode=ru&key="
                  + key),
          city.name,
          System.currentTimeMillis());
    }
    if (provider.equals("yandex")) {
      String query =
          "{ weatherByPoint(request: { lat: "
              + lat
              + ", lon: "
              + lon
              + " }) { now { temperature humidity windSpeed condition icon time } } }";
      return Weather.yandex(
          request(
              "https://api.weather.yandex.ru/graphql/query",
              apiKey,
              new JSONObject().put("query", query).toString()),
          city.name,
          System.currentTimeMillis());
    }
    if (provider.equals("weatherapi")) {
      return Weather.weatherApi(
          get(
              "https://api.weatherapi.com/v1/current.json?key="
                  + key
                  + "&q="
                  + lat
                  + ","
                  + lon
                  + "&aqi=no&lang=ru"),
          city.name,
          System.currentTimeMillis());
    }
    String coordinates =
        "latitude="
            + Weather.coordinate(city.latitude)
            + "&longitude="
            + Weather.coordinate(city.longitude);
    if (provider.equals("met")) {
      JSONObject root =
          get(
              "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat="
                  + Weather.coordinate(city.latitude)
                  + "&lon="
                  + Weather.coordinate(city.longitude));
      return Weather.metNorway(root, city.name, System.currentTimeMillis());
    }
    JSONObject root =
        get(
            "https://api.open-meteo.com/v1/forecast?"
                + coordinates
                + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,is_day&wind_speed_unit=ms&timeformat=unixtime");
    return Weather.openMeteo(root, city.name, System.currentTimeMillis());
  }
}
