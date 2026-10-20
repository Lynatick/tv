package ru.horizon.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Stable releases and APKs from this application's repository only. */
final class UpdateClient {
  static final String LATEST = "https://api.github.com/repos/Lynatick/tv/releases/latest";
  static final long MAX_APK_BYTES = 50 * 1024 * 1024;

  static final class Release {
    final String version, url, sha256;
    final long size;

    Release(String version, String url, String sha256, long size) {
      this.version = version;
      this.url = url;
      this.sha256 = sha256;
      this.size = size;
    }
  }

  static int compareVersions(String left, String right) {
    String pattern = "[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}";
    if (!left.matches(pattern) || !right.matches(pattern))
      throw new IllegalArgumentException("Unsupported version");
    String[] a = left.split("\\."), b = right.split("\\.");
    for (int i = 0; i < 3; i++) {
      int comparison = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
      if (comparison != 0) return comparison;
    }
    return 0;
  }

  static Release parse(JSONObject json, String installedVersion) throws Exception {
    if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null;
    String tag = json.getString("tag_name");
    String version = tag.startsWith("v") ? tag.substring(1) : tag;
    if (compareVersions(version, installedVersion) <= 0) return null;
    String name = "horizon-tv-" + version + ".apk";
    String expectedUrl = "https://github.com/Lynatick/tv/releases/download/" + tag + "/" + name;
    JSONArray assets = json.getJSONArray("assets");
    for (int i = 0; i < assets.length(); i++) {
      JSONObject asset = assets.getJSONObject(i);
      if (!name.equals(asset.optString("name"))) continue;
      String digest = asset.optString("digest");
      long size = asset.optLong("size");
      if (!expectedUrl.equals(asset.optString("browser_download_url"))
          || !"uploaded".equals(asset.optString("state"))
          || !digest.matches("sha256:[0-9a-fA-F]{64}")
          || size <= 0
          || size > MAX_APK_BYTES) throw new IOException("Invalid update asset");
      return new Release(version, expectedUrl, digest.substring(7).toLowerCase(Locale.ROOT), size);
    }
    throw new IOException("Release APK unavailable");
  }

  static JSONObject fetch() throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(LATEST).openConnection();
    connection.setConnectTimeout(12000);
    connection.setReadTimeout(15000);
    connection.setUseCaches(false);
    connection.setRequestProperty("Accept", "application/vnd.github+json");
    connection.setRequestProperty("User-Agent", "HorizonTV (https://github.com/Lynatick/tv)");
    connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
    try {
      if (connection.getResponseCode() != 200) throw new IOException("Release check unavailable");
      try (InputStream input = connection.getInputStream();
          ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
          output.write(buffer, 0, count);
          if (output.size() > 512 * 1024) throw new IOException("Release response too large");
        }
        return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
      }
    } finally {
      connection.disconnect();
    }
  }

  static void verify(InputStream input, Release release) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] buffer = new byte[8192];
    long size = 0;
    int count;
    while ((count = input.read(buffer)) != -1) {
      size += count;
      if (size > release.size || size > MAX_APK_BYTES) throw new IOException("APK too large");
      digest.update(buffer, 0, count);
    }
    StringBuilder hex = new StringBuilder();
    for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
    if (size != release.size || !release.sha256.equals(hex.toString()))
      throw new IOException("APK checksum mismatch");
  }
}
