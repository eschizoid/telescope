package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link DeepMap}'s TypePair cycle cache against direct scalar cycles, container cycles, and
 * mutual-recursion cycles. The org-chart example covers the Optional+List self-reference shape;
 * these tests pin the harder shapes the cache also has to handle so a future refactor of the
 * placeholder-then-fill-in mechanism doesn't silently regress to StackOverflow.
 */
class CycleHandlingTest {

  @Nested
  @DisplayName("Self-reference through container")
  class OptionalSelfRef {

    record Node(String id, Optional<Node> parent) {}

    record NodeDto(String id, Optional<NodeDto> parent) {}

    @Test
    @DisplayName("Optional<Self> auto-mapper builds without StackOverflow")
    void optionalSelfRefBuilds() {
      final var mapper = Telescope.mapper(Node.class, NodeDto.class);
      final var root = new Node("root", Optional.empty());
      final var child = new Node("c", Optional.of(root));
      final var dto = mapper.forward(child);
      assertEquals("c", dto.id());
      assertEquals("root", dto.parent().orElseThrow().id());
    }
  }

  @Nested
  @DisplayName("Self-reference through List")
  class ListSelfRef {

    record TreeNode(String name, List<TreeNode> children) {}

    record TreeNodeDto(String name, List<TreeNodeDto> children) {}

    @Test
    @DisplayName("List<Self> auto-mapper builds + maps a 3-level tree without StackOverflow")
    void listSelfRefBuilds() {
      final var mapper = Telescope.mapper(TreeNode.class, TreeNodeDto.class);
      final var leaf = new TreeNode("leaf", List.of());
      final var mid = new TreeNode("mid", List.of(leaf));
      final var root = new TreeNode("root", List.of(mid));
      final var dto = mapper.forward(root);
      assertEquals("root", dto.name());
      assertEquals("mid", dto.children().get(0).name());
      assertEquals("leaf", dto.children().get(0).children().get(0).name());
    }
  }

  @Nested
  @DisplayName("Mutual recursion A↔B")
  class MutualRecursion {

    record A(String label, Optional<B> b) {}

    record B(String tag, Optional<A> a) {}

    record ADto(String label, Optional<BDto> b) {}

    record BDto(String tag, Optional<ADto> a) {}

    @Test
    @DisplayName("A→B→A mutual cycle builds via Optional containers without StackOverflow")
    void mutualCycleBuilds() {
      final var mapper = Telescope.mapper(A.class, ADto.class);
      final var leaf = new A("leaf", Optional.empty());
      final var mid = new B("mid", Optional.of(leaf));
      final var root = new A("root", Optional.of(mid));
      final var dto = mapper.forward(root);
      assertNotNull(dto);
      assertEquals("root", dto.label());
      assertEquals("mid", dto.b().orElseThrow().tag());
      assertEquals("leaf", dto.b().orElseThrow().a().orElseThrow().label());
    }
  }
}
