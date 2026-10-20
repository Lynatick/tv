package ru.horizon.tv;

import static org.junit.Assert.*;

import java.util.Locale;
import org.junit.Test;

public class YandexWeatherTest {
  @Test
  public void todayAndWeekUseOriginalYandexPagesWithChosenCoordinates() {
    assertEquals(
        "https://yandex.ru/pogoda/ru/details/today?lat=55.753960&lon=37.620393",
        YandexWeather.url(55.75396, 37.620393, "day"));
    assertEquals(
        "https://yandex.ru/pogoda/ru/details/7-day-weather?lat=59.938600&lon=30.314100",
        YandexWeather.url(59.9386, 30.3141, "week"));
    assertTrue(YandexWeather.allowedNavigation(YandexWeather.url(-33.86, 151.21, "week")));
  }

  @Test
  public void coordinatesDoNotDependOnDeviceLocale() {
    Locale before = Locale.getDefault();
    try {
      Locale.setDefault(Locale.GERMANY);
      assertTrue(YandexWeather.url(55.75, 37.62, "day").endsWith("lat=55.750000&lon=37.620000"));
    } finally {
      Locale.setDefault(before);
    }
  }

  @Test
  public void invalidLocationsAndPeriodsAreRejected() {
    for (double[] coordinates :
        new double[][] {{91, 0}, {0, -181}, {Double.NaN, 0}, {0, Double.POSITIVE_INFINITY}})
      assertThrows(
          IllegalArgumentException.class,
          () -> YandexWeather.url(coordinates[0], coordinates[1], "day"));
    assertThrows(IllegalArgumentException.class, () -> YandexWeather.url(0, 0, "../../passport"));
  }

  @Test
  public void originalForecastAndCaptchaCanBeNavigated() {
    for (String url :
        new String[] {
          "https://yandex.ru/pogoda/ru/moscow/details/today",
          "https://www.yandex.ru/pogoda/ru?lat=55.75&lon=37.62",
          "https://yandex.ru:443/pogoda/maps/nowcast",
          "https://yandex.ru/showcaptcha?retpath=test",
          "https://yandex.ru/checkcaptcha?key=test"
        }) assertTrue(url, YandexWeather.allowedNavigation(url));
  }

  @Test
  public void foreignSitesNativeSchemesAndPathEscapesAreBlocked() {
    for (String url :
        new String[] {
          "http://yandex.ru/pogoda/ru",
          "https://yandex.ru.evil.example/pogoda/ru",
          "https://evil.example/pogoda/ru",
          "https://yandex.ru@evil.example/pogoda/ru",
          "https://user@yandex.ru/pogoda/ru",
          "file:///sdcard/private.txt",
          "javascript:alert(1)",
          "intent://weather",
          "https://yandex.ru:444/pogoda/ru",
          "https://yandex.ru/passport",
          "https://yandex.ru/pogoda/../passport",
          "https://yandex.ru/pogoda/%2e%2e/passport",
          "https://yandex.ru/pogoda/%5c..%5cpassport",
          "https://yandex.ru/pogoda-other",
          "not a URL"
        }) assertFalse(url, YandexWeather.allowedNavigation(url));
  }
}
