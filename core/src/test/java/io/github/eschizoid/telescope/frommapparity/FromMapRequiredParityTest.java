package io.github.eschizoid.telescope.frommapparity;

import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;
import static io.github.eschizoid.telescope.mapping.MapExtractStep.required;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.Telescope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A key declared required is refused by the generated binder through {@code @FromMap(required =
 * ...)} and by the runtime mapper through a {@code required(...)} row. The two are meant to refuse
 * the same maps, name the same keys, and agree on every map they accept; a map one refuses and the
 * other accepts is a record that exists on one path and not on the other.
 */
class FromMapRequiredParityTest {

  /** The map shapes a required key can meet: absent, present, holding null, beside others. */
  private static List<Map<String, Object>> sources() {
    final var withNullId = new HashMap<String, Object>();
    withNullId.put("id", null);
    withNullId.put("count", 2);
    return List.of(
      Map.of(),
      Map.of("note", "n"),
      Map.of("id", "a"),
      Map.of("count", 3),
      Map.of("id", "a", "count", 3),
      Map.of("id", "a", "count", "4", "note", "n"),
      withNullId
    );
  }

  /** The integer reading the generated binder applies to a Number or a numeric String. */
  private static int asInt(final Object v) {
    return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
  }

  @Test
  @DisplayName("a record: both paths refuse the same maps with the same message, and agree on the rest")
  void recordPathsAgree() {
    final var runtime = Telescope.fromMap(
      RequiredRow.class,
      required("count", RequiredRow::count, FromMapRequiredParityTest::asInt),
      required("id", RequiredRow::id, Object::toString),
      extract("note", RequiredRow::note, Object::toString)
    );
    assertAgreement(RequiredRowFromMap::fromMap, runtime::forward, Function.identity(), "component");
  }

  @Test
  @DisplayName("a bean: both paths refuse the same maps with the same message, and agree on the rest")
  void beanPathsAgree() {
    final var runtime = Telescope.fromMap(
      RequiredBean.class,
      required("count", RequiredBean::getCount, FromMapRequiredParityTest::asInt),
      required("id", RequiredBean::getId, Object::toString),
      extract("note", RequiredBean::getNote, Object::toString)
    );
    assertAgreement(
      RequiredBeanFromMap::fromMap,
      runtime::forward,
      b -> List.of(Objects.toString(b.getId()), b.getCount(), Objects.toString(b.getNote())),
      "property"
    );
  }

  private static <T> void assertAgreement(
    final Function<Map<String, Object>, T> generated,
    final Function<Map<String, Object>, T> runtime,
    final Function<T, Object> view,
    final String kind
  ) {
    final var refusals = new ArrayList<String>();
    for (final var source : sources()) {
      final var g = outcome(() -> view.apply(generated.apply(source)));
      final var r = outcome(() -> view.apply(runtime.apply(source)));
      assertEquals(g, r, () -> "the two paths disagree about " + source);
      if (g.startsWith("refused: ")) refusals.add(g);
    }
    // The comparison above holds trivially if neither path refuses anything, so pin that the
    // sources include maps both refuse, and what they say. Both paths declare the required keys
    // out of component order and both name them in component order.
    assertEquals(
      List.of(
        "refused: the map carries no value for required keys \"id\" (component 'id'), \"count\" (component 'count')",
        "refused: the map carries no value for required keys \"id\" (component 'id'), \"count\" (component 'count')",
        "refused: the map carries no value for required key \"count\" (component 'count')",
        "refused: the map carries no value for required key \"id\" (component 'id')",
        "refused: the map carries no value for required key \"id\" (component 'id')"
      )
        .stream()
        .map(s -> s.replace("component", kind))
        .toList(),
      refusals
        .stream()
        .map(s -> s.replaceFirst(" of \\w+$", ""))
        .toList()
    );
  }

  /**
   * A result, or the refusal with its path-specific prefix removed: the runtime says {@code
   * Telescope.fromMap}, the binder names itself, and everything after the first colon is shared.
   */
  private static String outcome(final Supplier<Object> run) {
    try {
      return "value: " + run.get();
    } catch (final IllegalArgumentException e) {
      return "refused: " + e.getMessage().substring(e.getMessage().indexOf(": ") + 2);
    }
  }
}
