package io.github.eschizoid.telescope.introspection;

import static io.github.eschizoid.telescope.mapping.Mapping.drop;
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
      assertTrue(trace.toString().contains("address.city"), trace::toString);
      assertTrue(trace.toString().contains("\"Paris\""), trace::toString);
    }
  }
}
