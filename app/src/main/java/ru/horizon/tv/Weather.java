package ru.horizon.tv;

import java.time.Instant;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

final class Weather {
  final double temperature, wind;
  final int humidity;
  final String description, symbol, city, provider;
  final long observation, fetched;

  Weather(
      double temperature,
      double wind,
      int humidity,
      String description,
      String symbol,
      String city,
      String provider,
      long observation,
      long fetched) {
    if (!Double.isFinite(temperature) || !Double.isFinite(wind))
      throw new IllegalArgumentException("Invalid weather");
    this.temperature = temperature;
    this.wind = wind;
    this.humidity = humidity;
    this.description = description;
    this.symbol = symbol;
    this.city = city;
    this.provider = provider;
    this.observation = observation;
    this.fetched = fetched;
  }

  String temperature(boolean fahrenheit) {
    return Math.round(fahrenheit ? temperature * 1.8 + 32 : temperature) + "°";
  }

  static String[] wmo(int code, boolean day) {
    if (code == 0) return new String[] {"Ясно", day ? "sun" : "moon"};
    if (code == 1 || code == 2) return new String[] {"Переменная облачность", "partly"};
    if (code == 3) return new String[] {"Облачно", "cloud"};
    if (code == 45 || code == 48) return new String[] {"Туман", "fog"};
    if (code >= 95) return new String[] {"Гроза", "storm"};
    if (code == 56 || code == 57 || code == 66 || code == 67)
      return new String[] {"Ледяной дождь", "rain"};
    if (code >= 71 && code <= 77 || code == 85 || code == 86) return new String[] {"Снег", "snow"};
    if (code >= 51 && code <= 65 || code >= 80 && code <= 82) return new String[] {"Дождь", "rain"};
    return new String[] {"Нет описания", "cloud"};
  }

  static String[] metSymbol(String value) {
    if (value.contains("thunder")) return new String[] {"Гроза", "storm"};
    if (value.contains("snow") || value.contains("sleet"))
      return new String[] {"Снег / мокрый снег", "snow"};
    if (value.contains("rain")) return new String[] {"Дождь", "rain"};
    if (value.contains("fog")) return new String[] {"Туман", "fog"};
    if (value.startsWith("clearsky"))
      return new String[] {"Ясно", value.contains("night") ? "moon" : "sun"};
    if (value.startsWith("fair") || value.startsWith("partlycloudy"))
      return new String[] {"Переменная облачность", "partly"};
    return new String[] {"Облачно", "cloud"};
  }

  static Weather openMeteo(JSONObject root, String city, long now) throws Exception {
    JSONObject c = root.getJSONObject("current");
    String[] condition = wmo(c.getInt("weather_code"), c.optInt("is_day", 1) == 1);
    return new Weather(
        c.getDouble("temperature_2m"),
        c.getDouble("wind_speed_10m"),
        c.getInt("relative_humidity_2m"),
        condition[0],
        condition[1],
        city,
        "Open-Meteo",
        c.getLong("time") * 1000,
        now);
  }

  static Weather metNorway(JSONObject root, String city, long now) throws Exception {
    JSONArray series = root.getJSONObject("properties").getJSONArray("timeseries");
    if (series.length() == 0) throw new IllegalArgumentException("Empty forecast");
    JSONObject selected = series.getJSONObject(0);
    long nearest = Long.MAX_VALUE;
    for (int i = 0; i < series.length(); i++) {
      JSONObject entry = series.getJSONObject(i);
      long distance = Math.abs(Instant.parse(entry.getString("time")).toEpochMilli() - now);
      if (distance < nearest) {
        nearest = distance;
        selected = entry;
      }
    }
    JSONObject data = selected.getJSONObject("data");
    JSONObject d = data.getJSONObject("instant").getJSONObject("details");
    JSONObject next = data.optJSONObject("next_1_hours");
    if (next == null) next = data.optJSONObject("next_6_hours");
    String[] condition =
        next == null
            ? new String[] {"Нет описания", "cloud"}
            : metSymbol(next.getJSONObject("summary").getString("symbol_code"));
    return new Weather(
        d.getDouble("air_temperature"),
        d.getDouble("wind_speed"),
        (int) Math.round(d.getDouble("relative_humidity")),
        condition[0],
        condition[1],
        city,
        "MET Norway",
        Instant.parse(selected.getString("time")).toEpochMilli(),
        now);
  }

  String json() {
    try {
      return new JSONObject()
          .put("temperature", temperature)
          .put("wind", wind)
          .put("humidity", humidity)
          .put("description", description)
          .put("symbol", symbol)
          .put("city", city)
          .put("provider", provider)
          .put("observation", observation)
          .put("fetched", fetched)
          .toString();
    } catch (Exception e) {
      return "";
    }
  }

  static Weather cached(String json) {
    try {
      JSONObject d = new JSONObject(json);
      return new Weather(
          d.getDouble("temperature"),
          d.getDouble("wind"),
          d.getInt("humidity"),
          d.getString("description"),
          d.getString("symbol"),
          d.getString("city"),
          d.getString("provider"),
          d.getLong("observation"),
          d.getLong("fetched"));
    } catch (Exception e) {
      return null;
    }
  }

  static String coordinate(double value) {
    return String.format(Locale.US, "%.4f", value);
  }

  static String[] condition(String raw, boolean day) {
    String code = raw.toUpperCase(Locale.US);
    if (code.contains("THUNDER") || code.contains("STORM")) return new String[] {"Гроза", "storm"};
    if (code.contains("SNOW") || code.contains("SLEET") || code.contains("HAIL"))
      return new String[] {"Снег / мокрый снег", "snow"};
    if (code.contains("RAIN") || code.contains("DRIZZLE") || code.contains("SHOWER"))
      return new String[] {"Дождь", "rain"};
    if (code.contains("FOG") || code.contains("MIST") || code.contains("HAZE"))
      return new String[] {"Туман", "fog"};
    if (code.equals("CLEAR") || code.equals("MOSTLY_CLEAR"))
      return new String[] {"Ясно", day ? "sun" : "moon"};
    if (code.contains("PARTLY") || code.contains("MOSTLY_CLOUDY"))
      return new String[] {"Переменная облачность", "partly"};
    return new String[] {"Облачно", "cloud"};
  }

  static Weather google(JSONObject root, String city, long now) throws Exception {
    JSONObject temperature = root.getJSONObject("temperature");
    double temp = temperature.getDouble("degrees");
    if (temperature.getString("unit").equals("FAHRENHEIT")) temp = (temp - 32) / 1.8;
    JSONObject speed = root.getJSONObject("wind").getJSONObject("speed");
    double wind = speed.getDouble("value");
    String unit = speed.getString("unit");
    if (unit.equals("KILOMETERS_PER_HOUR")) wind /= 3.6;
    else if (unit.equals("MILES_PER_HOUR")) wind *= .44704;
    JSONObject c = root.getJSONObject("weatherCondition");
    String[] condition = condition(c.getString("type"), root.optBoolean("isDaytime", true));
    return new Weather(
        temp,
        wind,
        root.getInt("relativeHumidity"),
        c.getJSONObject("description").getString("text"),
        condition[1],
        city,
        "Google Maps",
        Instant.parse(root.getString("currentTime")).toEpochMilli(),
        now);
  }

  static Weather yandex(JSONObject root, String city, long now) throws Exception {
    if (root.has("errors"))
      throw new IllegalArgumentException("Яндекс: проверьте ключ API и доступ к тарифу");
    JSONObject c = root.getJSONObject("data").getJSONObject("weatherByPoint").getJSONObject("now");
    String[] condition =
        condition(c.getString("condition"), !c.optString("icon", "").endsWith("_n"));
    return new Weather(
        c.getDouble("temperature"),
        c.getDouble("windSpeed"),
        c.getInt("humidity"),
        condition[0],
        condition[1],
        city,
        "Яндекс Погода",
        java.time.OffsetDateTime.parse(c.getString("time")).toInstant().toEpochMilli(),
        now);
  }

  static Weather weatherApi(JSONObject root, String city, long now) throws Exception {
    JSONObject c = root.getJSONObject("current"), condition = c.getJSONObject("condition");
    int code = condition.getInt("code");
    String symbol;
    if (code >= 1273 || code == 1087) symbol = "storm";
    else if (code == 1000) symbol = c.optInt("is_day", 1) == 1 ? "sun" : "moon";
    else if (code == 1003) symbol = "partly";
    else if (code == 1006 || code == 1009) symbol = "cloud";
    else if (code == 1030 || code == 1135 || code == 1147) symbol = "fog";
    else if (code == 1066
        || code == 1069
        || code == 1114
        || code == 1117
        || code >= 1204 && code <= 1237
        || code >= 1255 && code <= 1264) symbol = "snow";
    else symbol = "rain";
    return new Weather(
        c.getDouble("temp_c"),
        c.getDouble("wind_kph") / 3.6,
        c.getInt("humidity"),
        condition.getString("text"),
        symbol,
        city,
        "WeatherAPI.com",
        c.getLong("last_updated_epoch") * 1000,
        now);
  }
}
