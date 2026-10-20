package ru.horizon.tv;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class UpdateClientTest {
  private static final String SHA =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

  private JSONObject release(String version) throws Exception {
    return new JSONObject()
        .put("tag_name", "v" + version)
        .put("draft", false)
        .put("prerelease", false)
        .put(
            "assets",
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("name", "horizon-tv-" + version + ".apk")
                        .put("state", "uploaded")
                        .put("size", 3)
                        .put("digest", "sha256:" + SHA)
                        .put(
                            "browser_download_url",
                            "https://github.com/Lynatick/tv/releases/download/v"
                                + version
                                + "/horizon-tv-"
                                + version
                                + ".apk")));
  }

  @Test
  public void versionComparisonIsNumericAndRejectsUnstableTags() {
    assertTrue(UpdateClient.compareVersions("1.10.0", "1.9.9") > 0);
    assertTrue(UpdateClient.compareVersions("2.0.0", "1.99.99") > 0);
    assertTrue(UpdateClient.compareVersions("1.0.1", "1.0.2") < 0);
    assertEquals(0, UpdateClient.compareVersions("1.0.1", "1.0.1"));
    for (String version :
        new String[] {"1.0.2-beta", "latest", "1.0", "-1.0.0", "9999999999.0.0"}) {
      assertThrows(
          IllegalArgumentException.class, () -> UpdateClient.compareVersions(version, "1.0.1"));
    }
  }

  @Test
  public void onlyNewerStableReleaseCreatesAnUpdate() throws Exception {
    JSONObject json = release("1.0.2");
    UpdateClient.Release update = UpdateClient.parse(json, "1.0.1");
    assertEquals("1.0.2", update.version);
    assertEquals(SHA, update.sha256);
    assertNull(UpdateClient.parse(json, "1.0.2"));
    assertNull(UpdateClient.parse(json, "2.0.0"));
    assertNull(UpdateClient.parse(json.put("draft", true), "1.0.1"));
    json.put("draft", false).put("prerelease", true);
    assertNull(UpdateClient.parse(json, "1.0.1"));
  }

  @Test
  public void foreignOrUnverifiedAssetsAreRejected() throws Exception {
    for (String url :
        new String[] {
          "http://github.com/Lynatick/tv/releases/download/v1.0.2/horizon-tv-1.0.2.apk",
          "https://github.com/other/tv/releases/download/v1.0.2/horizon-tv-1.0.2.apk",
          "https://github.com.evil.example/Lynatick/tv/releases/download/v1.0.2/horizon-tv-1.0.2.apk",
          "https://github.com/Lynatick/tv/releases/download/v1.0.2/horizon-tv-1.0.2.apk?redirect=other"
        }) {
      JSONObject json = release("1.0.2");
      json.getJSONArray("assets").getJSONObject(0).put("browser_download_url", url);
      assertThrows(IOException.class, () -> UpdateClient.parse(json, "1.0.1"));
    }
    for (String sha : new String[] {"", "null", "sha256:abc", "md5:" + SHA}) {
      JSONObject json = release("1.0.2");
      json.getJSONArray("assets").getJSONObject(0).put("digest", sha);
      assertThrows(IOException.class, () -> UpdateClient.parse(json, "1.0.1"));
    }
  }

  @Test
  public void missingIncompleteAndOversizedApksAreRejected() throws Exception {
    JSONObject missing = release("1.0.2").put("assets", new JSONArray());
    assertThrows(IOException.class, () -> UpdateClient.parse(missing, "1.0.1"));
    JSONObject incomplete = release("1.0.2");
    incomplete.getJSONArray("assets").getJSONObject(0).put("state", "new");
    assertThrows(IOException.class, () -> UpdateClient.parse(incomplete, "1.0.1"));
    for (long size : new long[] {0, -1, UpdateClient.MAX_APK_BYTES + 1}) {
      JSONObject json = release("1.0.2");
      json.getJSONArray("assets").getJSONObject(0).put("size", size);
      assertThrows(IOException.class, () -> UpdateClient.parse(json, "1.0.1"));
    }
  }

  @Test
  public void checksumRejectsTamperingAndTruncation() throws Exception {
    UpdateClient.Release update = UpdateClient.parse(release("1.0.2"), "1.0.1");
    UpdateClient.verify(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)), update);
    for (String body : new String[] {"abd", "ab", "abcd", ""}) {
      assertThrows(
          IOException.class,
          () ->
              UpdateClient.verify(
                  new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), update));
    }
  }
}
