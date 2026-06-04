package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import lombok.Builder;
import lombok.Getter;

/**
 * Fixture: {@code @Builder} + {@code @Getter} POJO. LombokFocusProcessor should pick this up and
 * emit {@code BuilderUserPath} with builder() rebuild.
 */
@Builder
@Getter
public class BuilderUser {

  private String id;
  private String email;
}
