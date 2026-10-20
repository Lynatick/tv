package ru.horizon.tv;

import static org.junit.Assert.*;

import java.util.Locale;
import org.junit.Test;

public class MeteoblueWeatherTest {
  @Test
  public void dayAndWeekUseOfficialWidgetsWithChosenCoordinates() {
    String day = MeteoblueWeather.url(55.75396, 37.620393, "day");
    String week = MeteoblueWeather.url(-33.86, -151.21, "week");
    assertTrue(
        day.startsWith("https://www.meteoblue.com/ru/weather/widget/three/55.753960N37.620393E?"));
    assertTrue(day.contains("&days=4&"));
    assertTrue(
        week.startsWith(
            "https://www.meteoblue.com/ru/weather/widget/daily/-33.860000N-151.210000E?"));
    assertTrue(week.contains("&days=7&"));
    assertTrue(day.contains("&coloured=monochrome&"));
    assertTrue(day.endsWith("&layout=dark"));
    assertTrue(MeteoblueWeather.allowedNavigation(day));
    assertTrue(MeteoblueWeather.allowedNavigation(week));
  }

  @Test
  public void coordinatesDoNotDependOnDeviceLocale() {
    Locale before = Locale.getDefault();
    try {
      Locale.setDefault(Locale.GERMANY);
      assertTrue(MeteoblueWeather.url(55.75, 37.62, "day").contains("/55.750000N37.620000E?"));
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
          () -> MeteoblueWeather.url(coordinates[0], coordinates[1], "day"));
    assertThrows(IllegalArgumentException.class, () -> MeteoblueWeather.url(0, 0, "../../account"));
    assertThrows(IllegalArgumentException.class, () -> MeteoblueWeather.url(0, 0, null));
  }

  @Test
  public void officialWidgetAndForecastLinksCanBeNavigated() {
    for (String url :
        new String[] {
          "https://www.meteoblue.com/ru/weather/widget/daily/55.75N37.62E",
          "https://meteoblue.com/en/weather/widget/three/55.75N37.62E",
          "https://www.meteoblue.com:443/ru/weather/week/moscow_russia_524901"
        }) assertTrue(url, MeteoblueWeather.allowedNavigation(url));
  }

  @Test
  public void foreignSitesNativeSchemesAndPathEscapesAreBlocked() {
    for (String url :
        new String[] {
          "http://www.meteoblue.com/ru/weather/widget/daily/55N37E",
          "https://www.meteoblue.com.evil.example/ru/weather/widget/daily/55N37E",
          "https://evil.example/ru/weather/widget/daily/55N37E",
          "https://www.meteoblue.com@evil.example/ru/weather/widget/daily/55N37E",
          "https://user@www.meteoblue.com/ru/weather/widget/daily/55N37E",
          "file:///sdcard/private.txt",
          "javascript:alert(1)",
          "intent://weather",
          "https://www.meteoblue.com:444/ru/weather/widget/daily/55N37E",
          "https://www.meteoblue.com/ru/account/login",
          "https://www.meteoblue.com/ru/weather/../account/login",
          "https://www.meteoblue.com/ru/weather/%2e%2e/account/login",
          "https://www.meteoblue.com/ru/weather/%5c..%5caccount/login",
          "https://www.meteoblue.com/ru/weather-other",
          "not a URL"
        }) assertFalse(url, MeteoblueWeather.allowedNavigation(url));
  }
}
