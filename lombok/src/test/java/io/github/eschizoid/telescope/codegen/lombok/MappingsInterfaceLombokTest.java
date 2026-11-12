package io.github.eschizoid.telescope.codegen.lombok;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.codegen.lombok.fixtures.MappedLombokUser;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.MappedLombokView;
import io.github.eschizoid.telescope.introspection.OpticNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A Lombok-annotated source and its target implement the {@code Mappings} interfaces their
 * {@code @Bridge} field generates, in the compilation that generates them. The interface needs only
 * the field's declared type, so it is written in the round the field is first seen, before javac
 * resolves the {@code implements} clauses, while the bridge waits for Lombok's getters. Compiled by
 * Gradle's own pipeline, where Lombok's hooks install.
 */
class MappingsInterfaceLombokTest {

  @Test
  @DisplayName("a @Data source converts itself through its generated interface, as its field converts it")
  void aLombokSourceConvertsItself() {
    final var user = new MappedLombokUser("ada", "ada@example.com");
    final var view = user.toMappedLombokView();
    assertEquals(OpticNode.Runs.GENERATED, MappedLombokUser.VIEW.explain().declared().orElseThrow().runs());
    assertEquals(MappedLombokUser.VIEW.forward(user), view);
    assertEquals(new MappedLombokView("ada", "ada@example.com"), view);
    assertEquals(MappedLombokUser.VIEW.backward(view), view.toMappedLombokUser());
    assertEquals(user, view.toMappedLombokUser());
  }
}
