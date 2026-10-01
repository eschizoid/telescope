package io.github.eschizoid.telescope.inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.MapperBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TransformationTest {

  record Holder(String value) {}

  @Test
  void transformationDefaultApplyUsesItsTypedPath() {
    final class DefaultTransformation implements TelescopeTransformation<Holder, String> {

      @Override
      public Telescope<Holder, String> path() {
        return Telescope.of(Holder.class).field(Holder::value);
      }

      @Override
      public Transformation<String> transform() {
        return new Transformation<>("missing", String::toUpperCase);
      }

      Holder applyDefault(final Holder source) {
        return TelescopeTransformation.super.apply(source);
      }
    }
    final var transformation = new DefaultTransformation();

    assertEquals(new Holder("ADA"), transformation.applyDefault(new Holder("ada")));
    assertEquals(new Holder("missing"), transformation.applyDefault(new Holder(null)));
  }

  @Test
  void projectionDefaultTranslateIsCallable() {
    final TelescopeProjection<Holder, Holder> projection = new TelescopeProjection<>() {
      @Override
      public Holder map(final Holder source) {
        return source;
      }

      @Override
      public Holder backward(final Holder target) {
        return target;
      }

      @Override
      public Holder patch(final Holder source, final Holder partial) {
        return partial;
      }

      @Override
      public TelescopeProjection<Holder, Holder> addTransformer(final TelescopeTransformation<Holder, ?> t) {
        return this;
      }
    };
    projection.translate(MapperBuilder.create(Holder.class, Holder.class));
  }

  @Test
  void nullInputUsesNullDefaultWithoutCallingOperation() {
    final var calls = new AtomicInteger();
    final var rule = new Transformation<String>(null, value -> {
      calls.incrementAndGet();
      return value.toLowerCase(Locale.ROOT);
    });
    assertNull(rule.apply(null));
    assertEquals(0, calls.get());
  }

  @Test
  void nullInputUsesNonNullDefaultWithoutCallingOperation() {
    final var calls = new AtomicInteger();
    final var rule = new Transformation<>("unknown", (String value) -> {
      calls.incrementAndGet();
      return value.toLowerCase(Locale.ROOT);
    });
    assertEquals("unknown", rule.apply(null));
    assertEquals(0, calls.get());
  }

  @Test
  void nonNullInputRunsOperationExactlyOnce() {
    final var calls = new AtomicInteger();
    final var rule = new Transformation<>("fallback", (String value) -> {
      calls.incrementAndGet();
      return value.toLowerCase(Locale.ROOT);
    });
    assertEquals("boston", rule.apply("BOSTON"));
    assertEquals(1, calls.get());
  }

  @Test
  void emptyStringIsARealValue() {
    final var calls = new AtomicInteger();
    final var rule = new Transformation<>("fallback", (String value) -> {
      calls.incrementAndGet();
      return value + "!";
    });
    assertEquals("!", rule.apply(""));
    assertEquals(1, calls.get());
  }

  @Test
  void whitespaceOnlyStringIsARealValue() {
    final var rule = new Transformation<>("fallback", (String value) -> value.strip());
    assertTrue(rule.apply("  ").isEmpty());
  }

  @Test
  void emptyCollectionIsARealValue() {
    final var calls = new AtomicInteger();
    final var rule = new Transformation<List<String>>(List.of("fallback"), value -> {
      calls.incrementAndGet();
      return List.copyOf(value);
    });
    assertTrue(rule.apply(List.of()).isEmpty());
    assertEquals(1, calls.get());
  }

  @Test
  void operationMayReturnNull() {
    final var rule = new Transformation<>("fallback", (String value) -> null);
    assertNull(rule.apply("anything"));
  }

  @Test
  void defaultCanBeAReferenceValue() {
    final var fallback = new ArrayList<String>();
    final var rule = new Transformation<List<String>>(fallback, List::copyOf);
    assertSame(fallback, rule.apply(null));
  }

  @Test
  void operationFailurePropagates() {
    final var failure = new IllegalArgumentException("bad city");
    final var rule = new Transformation<>("fallback", (String value) -> {
      throw failure;
    });
    assertSame(failure, assertThrows(IllegalArgumentException.class, () -> rule.apply("Boston")));
  }

  @Test
  void nullOperationFailsAtConstruction() {
    final var thrown = assertThrows(NullPointerException.class, () -> new Transformation<String>(null, null));
    assertEquals("operation must not be null", thrown.getMessage());
  }
}
