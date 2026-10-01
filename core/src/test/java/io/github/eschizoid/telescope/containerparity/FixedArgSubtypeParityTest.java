package io.github.eschizoid.telescope.containerparity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.Telescope;
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

  /**
   * A class that fixes its elements, so the pair is two different types written without arguments.
   */
  public record FixedTgt(FixedArgNames items) {}

  @Test
  @DisplayName("a generic class used raw is copied into a fixed subtype without an element conversion")
  @SuppressWarnings({ "rawtypes", "unchecked" })
  void aRawUseIsNotReadAsAConcreteContainer() {
    // One side names no element type, so there is no element pair to convert between, and the
    // pairing copies the elements as they are into the target's own class. Reading the raw side's
    // unbound parameter as an element type would instead make the pair look like two containers of
    // different elements, which it refuses. The raw shape is the subject here, so the warnings it
    // raises are the fixture rather than a defect.
    final RawArgList items = new RawArgList();
    items.add("kept");
    final var out = Telescope.mapper(RawSrc.class, FixedTgt.class).forward(new RawSrc(items));

    assertEquals(FixedArgNames.class, out.items().getClass(), "the target's own class is allocated");
    assertEquals(List.of("kept"), List.copyOf(out.items()), "the value carries across unconverted");
  }
}
