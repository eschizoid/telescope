package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fixture: target side of {@link BridgedDataUser}'s {@code @Bridge}. Also a {@code @Data} POJO so
 * BOTH sides of the bridge exercise the round-deferral path (source and target each carry a
 * Lombok-synthesizing annotation).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BridgedDataUserDto {

  private String id;
  private String email;
}
