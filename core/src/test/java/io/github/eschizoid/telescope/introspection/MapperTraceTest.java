package io.github.eschizoid.telescope.introspection;

import static io.github.eschizoid.telescope.mapping.Mapping.drop;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for mapping {@code trace(input)} — running a mapper against a value and showing,
 * per resolved field, the source value flowing to the target value. Pins the aligned value-column
 * render (a golden master), the dropped-field row, and that a nested mapper traces its dotted rows.
 */
class MapperTraceTest {

  record Source(String name, String city) {}

  record Target(String name, String city) {}

  @Nested
  @DisplayName("Value column")
  class ValueColumn {

    @Test
    @DisplayName("each field shows source value → target value, arrows aligned")
    void alignedValueColumn() {
      final var trace = Telescope.mapper(Source.class, Target.class).trace(new Source("Ada", "Paris"));
      final var expected = String.join("\n", "✓ name  \"Ada\"   → name \"Ada\"", "✓ city  \"Paris\" → city \"Paris\"");
      assertEquals(expected, trace.toString());
    }
  }

  @Nested
  @DisplayName("Dropped and nested")
  class DroppedAndNested {

    record DropSource(String name, String legacyId) {}

    record DropTarget(String name) {}

    @Test
    @DisplayName("a dropped source field traces to a (dropped) row")
    void droppedRow() {
      final var trace = Telescope.mapper(DropSource.class, DropTarget.class, drop(DropSource::legacyId)).trace(
        new DropSource("Ada", "L-9")
      );
      assertTrue(trace.toString().contains("legacyId"), trace::toString);
      assertTrue(trace.toString().contains("(dropped)"), trace::toString);
      assertTrue(trace.toString().contains("name"), trace::toString);
    }

    record Address(String city) {}

    record AddressDto(String city) {}

    record Customer(String name, Address address) {}

    record CustomerDto(String name, AddressDto address) {}

    @Test
    @DisplayName("a nested mapper traces dotted-path rows with their values")
    void nestedDottedValues() {
      final var input = new Customer("Ada", new Address("Paris"));
      final var trace = Telescope.mapper(Customer.class, CustomerDto.class).trace(input);
      // The nested same-name row shows source→target with the value on the target side.
      assertTrue(trace.toString().contains("→ address.city \"Paris\""), trace::toString);
    }
  }

  @Nested
  @DisplayName("Renamed typed transform")
  class RenamedTransform {

    record Src(String count) {}

    record Tgt(Integer amount) {}

    @Test
    @DisplayName("a renamed typed-transform row traces source→target reading the correct field on each side, no throw")
    void renamedTransformTrace() {
      final var mapper = Telescope.mapper(
        Src.class,
        Tgt.class,
        to(Src::count, Tgt::amount, Integer::parseInt, String::valueOf)
      );
      // Regression: Transformed used to drop the source field name, so trace read the target name
      // "amount" off the source and threw. It must read "count" off source, "amount" off target.
      final var text = mapper.trace(new Src("42")).toString();
      assertTrue(text.contains("count"), text);
      assertTrue(text.contains("\"42\""), text);
      assertTrue(text.contains("amount"), text);
      assertTrue(text.contains("42") && !text.contains("(n/a)"), text);
    }
  }
}
