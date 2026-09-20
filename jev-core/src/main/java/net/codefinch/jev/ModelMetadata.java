package net.codefinch.jev;

import java.util.Objects;

/**
 * One entry of {@code GET /v1/models}: a model name or alias the account may send.
 *
 * @param name the id or alias, as accepted by the request {@code model} field
 * @param description human-readable description
 * @param releaseDate release date as sent by the server; the docs say {@code YYYY-MM-DD} but the
 *     live API returns an ISO-8601 timestamp with offset, so this is kept verbatim
 */
public record ModelMetadata(String name, String description, String releaseDate) {

  /** Validates the components. */
  public ModelMetadata {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(releaseDate, "releaseDate");
  }
}
