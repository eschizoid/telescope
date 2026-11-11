package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.MergeStep.from;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A bag names its sources' classes in the order they were added, whichever way it was built, so the
 * missing-source failure of a merge lists them in an order that is the same on every run. Eight
 * classes make an order that is not the one given vanishingly unlikely to match it by chance.
 */
class SourcesOrderTest {

  record Missing(String v) {}

  record Target(String v) {}

  private static final List<Object> GIVEN = List.of(7, "s", 3L, 2.5d, true, 'c', (short) 4, (byte) 1);

  private static final List<Class<?>> CLASSES = List.of(
    Integer.class,
    String.class,
    Long.class,
    Double.class,
    Boolean.class,
    Character.class,
    Short.class,
    Byte.class
  );

  @Test
  @DisplayName("Sources.of names its classes in the order the sources were given")
  void ofKeepsInsertionOrder() {
    assertEquals(CLASSES, List.copyOf(Sources.of(GIVEN.toArray()).classes()));
  }

  @Test
  @DisplayName("Sources.builder names its classes in the order the sources were added")
  void builderKeepsInsertionOrder() {
    final var builder = Sources.builder();
    for (final var source : GIVEN) builder.with(source);
    assertEquals(CLASSES, List.copyOf(builder.build().classes()));
  }

  @Test
  @DisplayName("a merge given no source a row reads lists the bag in the order its sources were given")
  void mergeMissingSourceListsTheBagInOrder() {
    final var mapper = Telescope.merge(Target.class, from(Missing::v, Target::v));
    final var failure = assertThrows(IllegalStateException.class, () -> mapper.forward(Sources.of(GIVEN.toArray())));
    assertEquals(
      "Telescope.merge: forward called without a source for class " +
        Missing.class.getName() +
        " (required by the row writing target field 'v'). Sources bag contains: " +
        "[Integer, String, Long, Double, Boolean, Character, Short, Byte]. Pass a value of Missing via" +
        " Sources.of(...) or Sources.builder().with(...).",
      failure.getMessage()
    );
  }
}
