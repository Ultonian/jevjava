package net.codefinch.jev.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** The SDK version and the runtime description sent in identification headers. */
public final class Version {

  /** SDK name used in {@code User-Agent} and {@code X-TypeSafe-SDK}. */
  public static final String SDK_NAME = "jev-java";

  /** The SDK version from the build, or {@code dev} when running from sources. */
  public static final String VERSION = load();

  /** {@code java/<version> (<os>; <arch>)}, mirroring upstream's {@code X-TypeSafe-Runtime}. */
  public static final String RUNTIME =
      "java/"
          + Runtime.version()
          + " ("
          + System.getProperty("os.name", "unknown")
          + "; "
          + System.getProperty("os.arch", "unknown")
          + ")";

  /** {@code jev-java/<version>}. */
  public static final String SDK = SDK_NAME + "/" + VERSION;

  private Version() {}

  private static String load() {
    try (InputStream in =
        Version.class.getResourceAsStream("/net/codefinch/jev/version.properties")) {
      if (in != null) {
        Properties p = new Properties();
        p.load(in);
        String v = p.getProperty("version", "").trim();
        if (!v.isEmpty() && !v.startsWith("${")) {
          return v;
        }
      }
    } catch (IOException e) {
      // fall through
    }
    return "dev";
  }
}
