package io.github.eschizoid.telescope.examples.lombok;

import lombok.Builder;
import lombok.Getter;

/**
 * {@code @Builder} + {@code @Getter} POJO. The Lombok processor picks the builder rebuild path: the
 * generated {@code LombokBuilderUserTelescope<R>} reconstructs via {@code
 * builder().id(...).email(...).build()}.
 */
@Builder
@Getter
public class LombokBuilderUser {

  private String id;
  private String email;
}
