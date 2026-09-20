package net.codefinch.jev;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A successful System One response.
 *
 * @param model the versioned model that answered (may differ from the alias requested)
 * @param answers the answers by question id
 * @param usage token usage
 * @param metadata request id, headers and raw body
 */
public record SystemOneResponse(
    String model, Answers answers, Usage usage, ResponseMetadata metadata) {

  /** Validates the components. */
  public SystemOneResponse {
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(answers, "answers");
    Objects.requireNonNull(usage, "usage");
    Objects.requireNonNull(metadata, "metadata");
  }

  /** The {@code x-typesafe-request-id} header, if present. */
  public Optional<String> requestId() {
    return metadata.requestId();
  }

  /** Response headers, case-insensitive keys. */
  public Map<String, List<String>> headers() {
    return metadata.headers();
  }

  /** The body exactly as received. */
  public String rawBody() {
    return metadata.rawBody();
  }
}
