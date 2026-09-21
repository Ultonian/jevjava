package net.codefinch.jev.internal;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.function.Supplier;

/**
 * A client-scoped diagnostic sink: every SDK message for one client passes through its configured
 * minimum level, whichever class emits it. Never mutates process-wide logger state.
 */
public interface Diagnostics {

  /** Logs a message if {@code level} passes this sink's filter. */
  void log(Level level, Supplier<String> message);

  /** Logs a message with an exception if {@code level} passes this sink's filter. */
  void log(Level level, Supplier<String> message, Throwable thrown);

  /** Drops everything. */
  Diagnostics NONE =
      new Diagnostics() {
        @Override
        public void log(Level level, Supplier<String> message) {}

        @Override
        public void log(Level level, Supplier<String> message, Throwable thrown) {}
      };

  /** A sink writing to {@code logger} for messages at or above {@code minimum}. */
  static Diagnostics of(Logger logger, Level minimum) {
    return new Diagnostics() {
      private boolean enabled(Level level) {
        return minimum != Level.OFF
            && level.getSeverity() >= minimum.getSeverity()
            && logger.isLoggable(level);
      }

      @Override
      public void log(Level level, Supplier<String> message) {
        if (enabled(level)) {
          logger.log(level, message);
        }
      }

      @Override
      public void log(Level level, Supplier<String> message, Throwable thrown) {
        if (enabled(level)) {
          logger.log(level, message, thrown);
        }
      }
    };
  }
}
