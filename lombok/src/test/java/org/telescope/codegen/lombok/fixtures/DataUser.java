package org.telescope.codegen.lombok.fixtures;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fixture: plain {@code @Data} POJO with no-arg constructor. LombokFocusProcessor should pick this
 * up and emit {@code DataUserPath} with no-arg-ctor + setX rebuild.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataUser {

  private String id;
  private String email;
}
