package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lombok {@code @Data} POJO with a primitive {@code int} field — exercises the codegen {@code
 * construct()} template's primitive null-guard path through the Lombok processor.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataAlertRequest {

  private int attemptCount;
  private String label;
}
