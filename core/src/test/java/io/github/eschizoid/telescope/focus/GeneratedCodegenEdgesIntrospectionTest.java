package io.github.eschizoid.telescope.focus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.introspection.OpticNode.Bridge;
import io.github.eschizoid.telescope.introspection.OpticNode.Focus;
import io.github.eschizoid.telescope.introspection.OpticNode.Traverse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the two codegen introspection edges beyond flat field navigation: a generated container
 * step's {@code each()} records a single {@code Traverse} (matching a hand-written {@code
 * .each(...)}), and a generated {@code as<Target>()} bridge hop records a {@code Bridge}. Uses the
 * generated navigators for the {@code @Focus FocusTeam} (with a {@code List<FocusPerson>}) and the
 * {@code @Focus @Bridge(FocusDto) FocusEntity} fixtures.
 */
class GeneratedCodegenEdgesIntrospectionTest {

  @Test
  @DisplayName("a container step's each() records one Traverse, then the element field is a Focus")
  void containerStepTraverse() {
    final var report = FocusTeamTelescope.of().members().each().name().explain();
    assertEquals(List.of(new Traverse("members", "collection"), new Focus("name")), report.nodes());
  }

  @Test
  @DisplayName("a bridge hop records a Bridge, then the target field is a Focus")
  void bridgeHop() {
    final var report = FocusEntityTelescope.of().asFocusDto().email().explain();
    assertEquals(List.of(new Bridge("FocusDto"), new Focus("email")), report.nodes());
  }
}
