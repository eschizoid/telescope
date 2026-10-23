package io.github.eschizoid.telescope.codegen.lombok;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.codegen.lombok.fixtures.FieldBridgedLombokUser;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.FieldBridgedLombokView;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.LombokFieldMappers;
import io.github.eschizoid.telescope.introspection.OpticNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code @Bridge} field over a Lombok-annotated source: its rows name getters only Lombok writes,
 * so the processor reads them once Lombok has patched the class, and the field's mapper runs the
 * bridge generated from them. Compiled by Gradle's own pipeline, where Lombok's hooks install.
 */
class BridgeFieldLombokTest {

  @Test
  @DisplayName("a field over a @Data source runs its generated bridge, renaming and converting both ways")
  void aFieldOverALombokSourceRunsItsBridge() {
    final var mapper = LombokFieldMappers.USER;
    assertEquals(OpticNode.Runs.GENERATED, mapper.explain().declared().orElseThrow().runs());
    final var view = mapper.forward(new FieldBridgedLombokUser("ada", "Ada@Example.COM"));
    assertEquals(new FieldBridgedLombokView("ada", "ada@example.com"), view);
    assertEquals(new FieldBridgedLombokUser("ada", "ADA@EXAMPLE.COM"), mapper.backward(view));
  }
}
