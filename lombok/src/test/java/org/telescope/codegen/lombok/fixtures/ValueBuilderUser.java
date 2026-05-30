package org.telescope.codegen.lombok.fixtures;

import lombok.Builder;
import lombok.Value;

/**
 * Fixture: {@code @Value} + {@code @Builder} POJO. {@code @Value} alone has no builder and no
 * no-arg ctor — adding {@code @Builder} gives LombokFocusProcessor a rebuild path.
 */
@Value
@Builder
public class ValueBuilderUser {

  String id;
  String email;
}
