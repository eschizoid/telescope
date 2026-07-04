package io.github.eschizoid.telescope.introspection;

import static io.github.eschizoid.telescope.mapping.Mapping.drop;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
  @DisplayName("Forward mapper — lenient rows in the value column")
  class Forward {

    record NarrowSource(String name) {}

    record WideTarget(String name, String region) {}

    record WideSource(String name, String legacyId) {}

    record NarrowTarget(String name) {}

    @Test
    @DisplayName("a lenient forward mapper traces the mapped field and the missing-source skip")
    void forwardMissingSourceTrace() {
      final var trace = Telescope.mapperForward(NarrowSource.class, WideTarget.class).trace(new NarrowSource("Ada"));
      final var text = trace.toString();
      assertTrue(text.contains("name") && text.contains("\"Ada\""), text);
      assertTrue(text.contains("region") && text.contains("(missing source)"), text);
    }

    @Test
    @DisplayName("a forward mapper traces a source field with no consumer as an (unused source) row")
    void forwardUnusedSourceTrace() {
      final var trace = Telescope.mapperForward(WideSource.class, NarrowTarget.class).trace(
        new WideSource("Ada", "L-9")
      );
      final var text = trace.toString();
      assertTrue(text.contains("name") && text.contains("\"Ada\""), text);
      assertTrue(text.contains("legacyId") && text.contains("(unused source)"), text);
    }
  }

  @Nested
  @DisplayName("Bean source — the mapping-side read path")
  class BeanSourceMapping {

    public static final class BeanUser {

      private final String name;

      public BeanUser(final String name) {
        this.name = name;
      }

      public String getName() {
        return name;
      }
    }

    record UserView(String name) {}

    @Test
    @DisplayName("a bean-source mapper traces the property value through the bean read path, no (n/a)")
    void beanSourceTrace() {
      final var trace = Telescope.mapper(BeanUser.class, UserView.class).trace(new BeanUser("Ada"));
      final var text = trace.toString();
      // readDotted must resolve the bean property "name" via the Reflective bean branch.
      assertTrue(text.contains("name") && text.contains("\"Ada\""), text);
      assertFalse(text.contains("(n/a)"), text);
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
