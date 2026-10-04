package net.codefinch.jev.benchmarks.config;

import java.util.List;

/** Fixed JVM arguments shared by standalone runners and JMH fork annotations. */
public final class JvmSettings {
  public static final String MIN_HEAP = "-Xms512m";
  public static final String MAX_HEAP = "-Xmx512m";
  public static final String GC = "-XX:+UseG1GC";
  public static final List<String> OPTIONS = List.of(MIN_HEAP, MAX_HEAP, GC);

  private JvmSettings() {}
}
