package io.github.eschizoid.telescope.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    assertThat(transformation.applyDefault(new Holder("ada"))).isEqualTo(new Holder("ADA"));
    assertThat(transformation.applyDefault(new Holder(null))).isEqualTo(new Holder("missing"));
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
    assertThat(rule.apply(null)).isNull();
    assertThat(calls).hasValue(0);
  }

  @Test
  void nullInputUsesNonNullDefaultWithoutCallingOperation() {
    final var calls = new AtomicInteger();
    final var rule = new Transformation<>("unknown", (String value) -> {
      calls.incrementAndGet();
      return value.toLowerCase(Locale.ROOT);
    });
    assertThat(rule.apply(null)).isEqualTo("unknown");
    assertThat(calls).hasValue(0);
  }

  @Test
  void nonNullInputRunsOperationExactlyOnce() {
    final var calls = new AtomicInteger();
    final var rule = new Transformation<>("fallback", (String value) -> {
      calls.incrementAndGet();
      return value.toLowerCase(Locale.ROOT);
    });
    assertThat(rule.apply("BOSTON")).isEqualTo("boston");
    assertThat(calls).hasValue(1);
  }

  @Test
  void emptyStringIsARealValue() {
    final var calls = new AtomicInteger();
    final var rule = new Transformation<>("fallback", (String value) -> {
      calls.incrementAndGet();
      return value + "!";
    });
    assertThat(rule.apply("")).isEqualTo("!");
    assertThat(calls).hasValue(1);
  }

  @Test
  void whitespaceOnlyStringIsARealValue() {
    final var rule = new Transformation<>("fallback", (String value) -> value.strip());
    assertThat(rule.apply("  ")).isEmpty();
  }

  @Test
  void emptyCollectionIsARealValue() {
    final var calls = new AtomicInteger();
    final var rule = new Transformation<List<String>>(List.of("fallback"), value -> {
      calls.incrementAndGet();
      return List.copyOf(value);
    });
    assertThat(rule.apply(List.of())).isEmpty();
    assertThat(calls).hasValue(1);
  }

  @Test
  void operationMayReturnNull() {
    final var rule = new Transformation<>("fallback", (String value) -> null);
    assertThat(rule.apply("anything")).isNull();
  }

  @Test
  void defaultCanBeAReferenceValue() {
    final var fallback = new ArrayList<String>();
    final var rule = new Transformation<List<String>>(fallback, List::copyOf);
    assertThat(rule.apply(null)).isSameAs(fallback);
  }

  @Test
  void operationFailurePropagates() {
    final var failure = new IllegalArgumentException("bad city");
    final var rule = new Transformation<>("fallback", (String value) -> {
      throw failure;
    });
    assertThatThrownBy(() -> rule.apply("Boston")).isSameAs(failure);
  }

  @Test
  void nullOperationFailsAtConstruction() {
    assertThatThrownBy(() -> new Transformation<String>(null, null))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("operation must not be null");
  }
}
