package io.github.eschizoid.telescope.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TransformationTest {

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
