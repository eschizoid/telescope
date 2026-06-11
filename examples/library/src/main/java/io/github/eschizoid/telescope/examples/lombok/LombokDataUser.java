package io.github.eschizoid.telescope.examples.lombok;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code @Data} POJO that the {@code LombokFocusProcessor} (from {@code :lombok}) picks up and
 * emits a {@code LombokDataUserTelescope<R>} navigator for, using the no-arg + setter rebuild
 * strategy.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LombokDataUser {

  private String id;
  private String email;
}
