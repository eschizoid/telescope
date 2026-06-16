package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import io.github.eschizoid.telescope.annotations.Bridge;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Fixture: bean carrying class-level {@code @Getter} + {@code @Setter} (NOT {@code @Data}) with a
 * {@code @Bridge} pointing at a plain-record target. Pins the broader Lombok trigger detection —
 * {@code BridgeProcessor} must defer this pair because {@code @Getter}/{@code @Setter} synthesize
 * accessors that {@code Elements.getAllMembers} can't see until Lombok's AST patches fire, but the
 * narrower {@code LOMBOK_BEAN_ANNOTATIONS} set (only {@code @Data}/{@code @Value}/{@code @Builder})
 * would have missed this case.
 */
@Getter
@Setter
@NoArgsConstructor
@Bridge(BridgedRecordTarget.class)
public class BridgedGetterSetterUser {

  private String id;
  private String email;
}
