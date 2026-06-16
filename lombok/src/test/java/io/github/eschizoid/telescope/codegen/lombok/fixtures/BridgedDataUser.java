package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import io.github.eschizoid.telescope.annotations.Bridge;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fixture: {@code @Data} POJO carrying {@code @Bridge(BridgedDataUserDto.class)}. Exercises the
 * end-to-end shape that broke before the {@code BridgeProcessor} round-deferral fix — in round 1,
 * {@code getAllMembers(BridgedDataUser.class)} returned the un-patched member list (no Lombok
 * getters/setters), so {@code BridgeProcessor} emitted a {@code BridgedDataUserBridge.BRIDGE} with
 * no field rows. The deferral pushes emission to {@code processingOver()}, by which point Lombok's
 * AST patches have fired and the synthesized accessors are visible.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Bridge(BridgedDataUserDto.class)
public class BridgedDataUser {

  private String id;
  private String email;
}
