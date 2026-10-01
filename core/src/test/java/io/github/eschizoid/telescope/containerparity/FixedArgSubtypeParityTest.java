package io.github.eschizoid.telescope.containerparity;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A container subtype that declares no type parameters of its own is a concrete container, and both
 * paths have to read it as one.
 *
 * <p>Such a type is written without type arguments, and so is a generic class used raw, so a rule
 * that asks a declaration for its own arguments finds none in either case and cannot tell them
 * apart. The two are not alike: the first fixes its element types on its supertype, and the second
 * leaves them unbound. Reading the first like the second refuses a program the generated path
 * converts.
 */
class FixedArgSubtypeParityTest {

  private static TreeMap<String, SortedParityA> source() {
    final var items = new TreeMap<String, SortedParityA>();
    items.put("a", new SortedParityA("1"));
    items.put("b", new SortedParityA("2"));
    return items;
  }

  @Test
  @DisplayName("a subtype that fixes its type arguments converts on both paths")
  void aFixedArgumentSubtypeConvertsOnBothPaths() {
    final var src = new FixedArgSrc(source());

    final var reflective = Telescope.mapper(FixedArgSrc.class, FixedArgTgt.class).forward(src);
    final var generated = FixedArgSrcBridge.BRIDGE.read(src);

    assertEquals(List.of("a", "b"), List.copyOf(reflective.items().keySet()), "the reflective path converts");
    assertEquals(List.copyOf(generated.items().keySet()), List.copyOf(reflective.items().keySet()), "and agrees");
    assertEquals("1", reflective.items().get("a").v(), "and converts the element");
  }

  /** A generic class used raw, whose elements nobody has named. */
  @SuppressWarnings("rawtypes")
  public record RawSrc(RawArgList items) {}

  /** A class that fixes its elements to String, which nothing says the raw side's elements are. */
  public record FixedTgt(FixedArgNames items) {}

  /** Another generic class used raw, so neither side names an element type. */
  @SuppressWarnings("rawtypes")
  public record RawTgt(ArrayList items) {}

  @Test
  @DisplayName("a generic class used raw is refused against a subtype fixing its element type")
  void aRawUseIsNotCopiedIntoAFixedElementType() {
    // The raw side's elements are of no type anything has said, so copying them unconverted could
    // fill the target with elements of a type it does not hold, and no conversion can be planned
    // from a type nobody named. The pairing is refused while the mapper is built.
    final var refusal = assertThrows(IllegalStateException.class, () -> Telescope.mapper(RawSrc.class, FixedTgt.class));
    assertTrue(refusal.getMessage().contains("a generic container used raw"), refusal::getMessage);
    assertTrue(refusal.getMessage().contains("Mapping.to(src, tgt, fwd, bwd)"), refusal::getMessage);
  }

  /** The fixed side as the source, so the raw use is the target. */
  public record FixedSrc(FixedArgNames items) {}

  @Test
  @DisplayName("a generic class used raw is refused as the target of a subtype fixing its element type")
  void aFixedElementTypeIsNotCopiedIntoARawUse() {
    // The rule is about the pair, not the direction: the backward half of the same mapper copies
    // the other way, so a raw target is refused for the same reason a raw source is.
    final var refusal = assertThrows(IllegalStateException.class, () -> Telescope.mapper(FixedSrc.class, RawSrc.class));
    assertTrue(refusal.getMessage().contains("a generic container used raw"), refusal::getMessage);
  }

  @Test
  @DisplayName("the row the refusal advises converts the raw side")
  @SuppressWarnings({ "rawtypes", "unchecked" })
  void theAdvisedRowConverts() {
    // The refusal names a four-argument to(...) row, which converts the whole container in each
    // direction. The raw shape is the subject here, so the warnings it raises are the fixture.
    final var mapper = Telescope.mapper(
      RawSrc.class,
      FixedTgt.class,
      to(
        RawSrc::items,
        FixedTgt::items,
        raw -> {
          final var names = new FixedArgNames();
          for (final var item : raw) names.add(String.valueOf(item));
          return names;
        },
        names -> {
          final RawArgList raw = new RawArgList();
          raw.addAll(names);
          return raw;
        }
      )
    );
    final RawArgList items = new RawArgList();
    items.add(7);

    final var out = mapper.forward(new RawSrc(items));

    assertEquals(FixedArgNames.class, out.items().getClass(), "the target's own class is built");
    assertEquals(List.of("7"), List.copyOf(out.items()), "each element is converted by the row");
  }

  @Test
  @DisplayName("two generic classes used raw copy their elements across unconverted")
  @SuppressWarnings({ "rawtypes", "unchecked" })
  void twoRawUsesCopy() {
    // Neither side names an element type, so the elements have nothing to differ by. The raw shape
    // is the subject here, so the warnings it raises are the fixture rather than a defect.
    final RawArgList items = new RawArgList();
    items.add("kept");
    final var out = Telescope.mapper(RawSrc.class, RawTgt.class).forward(new RawSrc(items));

    assertEquals(ArrayList.class, out.items().getClass(), "the target's own class is allocated");
    assertEquals(List.of("kept"), List.copyOf(out.items()), "the value carries across unconverted");
  }
}
