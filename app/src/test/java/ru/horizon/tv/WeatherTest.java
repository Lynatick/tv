package ru.horizon.tv;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class WeatherTest {
  @Test
  public void googleConvertsMetricWindAndRetainsAttribution() throws Exception {
    JSONObject sample =
        new JSONObject(
            "{\"temperature\":{\"degrees\":10,\"unit\":\"CELSIUS\"},\"wind\":{\"speed\":{\"value\":18,\"unit\":\"KILOMETERS_PER_HOUR\"}},\"weatherCondition\":{\"type\":\"CLEAR\",\"description\":{\"text\":\"Ясно\"}},\"relativeHumidity\":50,\"isDaytime\":false,\"currentTime\":\"2026-10-19T12:00:00Z\"}");
    Weather w = Weather.google(sample, "Москва", 1);
    assertEquals(5, w.wind, .001);
    assertEquals("moon", w.symbol);
    assertEquals("Google Maps", w.provider);
  }

  @Test
  public void yandexReadsGraphQlAndRejectsErrorResponses() throws Exception {
    JSONObject sample =
        new JSONObject(
            "{\"data\":{\"weatherByPoint\":{\"now\":{\"temperature\":-2,\"humidity\":81,\"windSpeed\":4.5,\"condition\":\"LIGHT_SNOW\",\"time\":\"2026-10-19T15:00:00+03:00\"}}}}");
    Weather w = Weather.yandex(sample, "Москва", 1);
    assertEquals(-2, w.temperature, .001);
    assertEquals("snow", w.symbol);
    assertEquals(1792411200000L, w.observation);
    try {
      Weather.yandex(new JSONObject("{\"errors\":[{\"message\":\"not authorized\"}]}"), "", 0);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void weatherApiConvertsWindAndUsesOwnConditionCodes() throws Exception {
    JSONObject sample =
        new JSONObject(
            "{\"current\":{\"temp_c\":-1,\"wind_kph\":36,\"humidity\":90,\"is_day\":1,\"last_updated_epoch\":1700000000,\"condition\":{\"code\":1213,\"text\":\"Небольшой"
                + " снег\"}}}");
    Weather w = Weather.weatherApi(sample, "Москва", 1);
    assertEquals(10, w.wind, .001);
    assertEquals("snow", w.symbol);
    assertEquals("WeatherAPI.com", w.provider);
  }

  @Test
  public void paidProvidersRequireAKeyBeforeOpeningNetwork() throws Exception {
    WeatherClient.City city = new WeatherClient.City("Москва", "", 55, 37);
    for (String provider : new String[] {"google", "yandex", "weatherapi"}) {
      assertTrue(WeatherClient.requiresKey(provider));
      try {
        WeatherClient.fetch(provider, city, "");
        fail();
      } catch (IllegalArgumentException expected) {
      }
    }
    assertFalse(WeatherClient.requiresKey("met"));
    assertFalse(WeatherClient.requiresKey("openmeteo"));
  }

  @Test
  public void weatherCodesDistinguishFreezingRainSnowAndThunder() {
    assertEquals("rain", Weather.wmo(67, true)[1]);
    assertEquals("snow", Weather.wmo(85, true)[1]);
    assertEquals("storm", Weather.wmo(96, true)[1]);
    assertEquals("moon", Weather.wmo(0, false)[1]);
    assertEquals("fog", Weather.wmo(48, true)[1]);
  }

  @Test
  public void parsesCurrentWeatherAndConvertsUnits() throws Exception {
    JSONObject json =
        new JSONObject(
            "{\"current\":{\"temperature_2m\":0,\"wind_speed_10m\":4.2,\"relative_humidity_2m\":72,\"weather_code\":3,\"is_day\":1,\"time\":1700000000}}");
    Weather weather = Weather.openMeteo(json, "Москва", 1700000001000L);
    assertEquals("0°", weather.temperature(false));
    assertEquals("32°", weather.temperature(true));
    assertEquals(4.2, weather.wind, .001);
    assertEquals(1700000000000L, weather.observation);
    assertEquals("Москва", weather.city);
    assertEquals("cloud", weather.symbol);
    Weather restored = Weather.cached(weather.json());
    assertNotNull(restored);
    assertEquals(weather.observation, restored.observation);
    assertEquals(weather.provider, restored.provider);
  }

  @Test
  public void missingTemperatureIsAnErrorInsteadOfZero() throws Exception {
    try {
      Weather.openMeteo(new JSONObject("{\"current\":{\"weather_code\":0}}"), "", 0);
      fail("Missing temperature must be rejected");
    } catch (org.json.JSONException expected) {
    }
  }

  @Test
  public void metSelectsClosestForecastAndKeepsWindInMetersPerSecond() throws Exception {
    String details =
        "\"instant\":{\"details\":{\"air_temperature\":%s,\"wind_speed\":3.7,\"relative_humidity\":80}},\"next_1_hours\":{\"summary\":{\"symbol_code\":\"heavyrainandthunder\"}}";
    String first = String.format(java.util.Locale.US, details, "2");
    String second = String.format(java.util.Locale.US, details, "5");
    JSONObject json =
        new JSONObject(
            "{\"properties\":{\"timeseries\":[{\"time\":\"2026-10-19T10:00:00Z\",\"data\":{"
                + first
                + "}},{\"time\":\"2026-10-19T11:00:00Z\",\"data\":{"
                + second
                + "}}]}}");
    Weather w =
        Weather.metNorway(
            json, "Осло", java.time.Instant.parse("2026-10-19T10:50:00Z").toEpochMilli());
    assertEquals(5, w.temperature, 0);
    assertEquals(3.7, w.wind, .001);
    assertEquals("storm", w.symbol);
  }

  @Test
  public void invalidCacheAndInvalidCoordinatesAreRejected() {
    assertNull(Weather.cached("broken"));
    assertEquals("55.7558", Weather.coordinate(55.755826));
    try {
      new WeatherClient.City("", "", 91, 0);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      new WeatherClient.City("", "", Double.NaN, 0);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }
}
