package io.github.eschizoid.telescope.frommapparity;

import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.Telescope;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a component with no value is filled with is decided twice — once while a binder is generated
 * and once while a mapper is built — and the two are meant to hand back the same record for the
 * same map. A value either one substitutes is invisible afterwards: it is in the record,
 * indistinguishable from one the source supplied, so a disagreement here is two programs that both
 * look correct and return different data.
 */
class FromMapDefaultsParityTest {

  @Test
  @DisplayName("an empty map fills every component the same way on both paths")
  void anEmptyMapAgreesOnBothPaths() {
    final var runtime = Telescope.fromMap(DefaultsRow.class).forward(Map.of());
    final var generated = DefaultsRowFromMap.fromMap(Map.of());

    assertEquals(generated, runtime, "the generated binder and the runtime mapper disagree about an empty map");
  }

  @Test
  @DisplayName("a partial map fills the components it does not carry the same way on both paths")
  void aPartialMapAgreesOnBothPaths() {
    // The rows read the keys a component without them would read by its own name, so both paths
    // read the same two keys, and the comparison is about the components the map does not carry.
    final var source = Map.<String, Object>of("text", "a", "count", 3);

    final var runtime = Telescope.fromMap(
      DefaultsRow.class,
      extract("text", DefaultsRow::text, Object::toString),
      extract("count", DefaultsRow::count, v -> Integer.parseInt(v.toString()))
    ).forward(source);
    final var generated = DefaultsRowFromMap.fromMap(source);

    assertEquals(generated, runtime, "the two paths disagree about a partially filled map");
    assertEquals("a", runtime.text(), "and the row that was given is read");
    assertEquals(3, runtime.count());
  }
}
