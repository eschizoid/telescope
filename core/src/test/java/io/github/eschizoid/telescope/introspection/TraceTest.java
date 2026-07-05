package io.github.eschizoid.telescope.introspection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link Trace}'s render. The tree render itself is pinned end-to-end by {@code
 * NavigationTraceTest} / {@code MapperTraceTest}; this pins the degenerate empty-roots case.
 */
class TraceTest {

  @Test
  @DisplayName("an empty trace renders a neutral label, mirroring OpticReport, not a blank string")
  void emptyTraceLabel() {
    assertEquals("(empty optic)", new Trace(List.of()).toString());
  }
}
