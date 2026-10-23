package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fixture: a {@code @Data} source whose getters only Lombok writes, mapped by the {@code @Bridge}
 * field {@link LombokFieldMappers#USER}. Its rows name {@code FieldBridgedLombokUser::getLogin},
 * which resolves only once Lombok has patched the class.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FieldBridgedLombokUser {

  private String login;
  private String email;
}
