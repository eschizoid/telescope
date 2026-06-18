package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lombok {@code @Data} POJO pairing a primitive {@code int attemptCount} with a reference {@code
 * String label}. The primitive field exercises null-to-primitive coercion; the reference field is
 * the sibling control so a reference-typed setter is verified in the same rebuild.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataAlertRequest {

  private int attemptCount;
  private String label;
}
