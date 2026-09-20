package net.codefinch.jev;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A successful {@code GET /v1/models} response.
 *
 * @param models the models and aliases, in response order
 * @param metadata request id, headers and raw body
 */
public record ModelList(List<ModelMetadata> models, ResponseMetadata metadata) {

  /** Validates and snapshots the components. */
  public ModelList {
    Objects.requireNonNull(models, "models");
    Objects.requireNonNull(metadata, "metadata");
    models = List.copyOf(models);
  }

  /** The {@code x-typesafe-request-id} header, if present. */
  public Optional<String> requestId() {
    return metadata.requestId();
  }
}
