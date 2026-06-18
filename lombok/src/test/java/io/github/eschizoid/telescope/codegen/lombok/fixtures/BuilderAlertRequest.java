package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import lombok.Builder;
import lombok.Getter;

/**
 * Lombok {@code @Builder} POJO pairing a primitive {@code int retries} with a reference {@code
 * String label}. Exercises the builder-strategy rebuild path's primitive null-guard end- to-end
 * through the Lombok processor.
 */
@Builder
@Getter
public class BuilderAlertRequest {

  private int retries;
  private String label;
}
