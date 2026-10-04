package net.codefinch.jev.internal;

/** Access to lifecycle assertions and resolved settings, compiled only into the test classes. */
public final class ClientTestAccess {
  private ClientTestAccess() {}

  public static ClientConfig config(HttpJevClient client) {
    return client.config();
  }

  public static int trackedCalls(HttpJevClient client) {
    return client.trackedCalls();
  }

  public static boolean isClosed(HttpJevClient client) {
    return client.isClosed();
  }

  public static boolean isShutdownComplete(HttpJevClient client) {
    return client.isShutdownComplete();
  }
}
