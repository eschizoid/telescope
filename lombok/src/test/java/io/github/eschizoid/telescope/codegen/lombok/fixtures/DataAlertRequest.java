package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lombok {@code @Data} POJO with both a primitive {@code int} and a reference {@code String} field.
 * The {@code attemptCount} primitive exercises the codegen {@code construct()} template's
 * null-guard fallback; the {@code label} reference field is the sibling control, asserting that a
 * reference-typed setter still takes the plain cast form alongside the guarded primitive on the
 * same construct invocation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataAlertRequest {

  private int attemptCount;
  private String label;
}
