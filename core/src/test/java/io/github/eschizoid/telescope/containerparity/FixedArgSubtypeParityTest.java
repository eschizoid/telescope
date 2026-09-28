package io.github.eschizoid.telescope.containerparity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.Telescope;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A container subtype that fixes its own type arguments is a concrete container, and both paths
 * have to read it as one.
 *
 * <p>Such a type declares no parameters of its own, so a rule that asks a declaration for its type
 * arguments finds none and cannot tell it apart from a raw use of a generic type. The two are not
 * alike: a raw use has element types nobody wrote down, and this one has them written on its
 * supertype. Reading them the same way refuses a program the generated path converts.
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

  /** Raw on both sides: the element type is unsaid, so there is nothing to convert element-wise. */
  @SuppressWarnings("rawtypes")
  public record RawSrc(RawArgList items) {}

  @SuppressWarnings("rawtypes")
  public record RawTgt(RawArgList items) {}

  @Test
  @DisplayName("a raw use of a generic subtype keeps the policy it had, rather than being paired")
  @SuppressWarnings({ "rawtypes", "unchecked" })
  void aRawUseIsNotReadAsAConcreteContainer() {
    // The discrimination the fix turns on. A raw declaration resolves through its supertype to its
    // own unbound parameter, not to a type, so nobody has said what its elements are and reading it
    // as a container would invent an element conversion. The raw shape is the point here, so the
    // warnings it raises are the fixture rather than a defect.
    final RawArgList items = new RawArgList();
    items.add("kept");
    final var out = Telescope.mapper(RawSrc.class, RawTgt.class).forward(new RawSrc(items));

    assertEquals(List.of("kept"), List.copyOf(out.items()), "the value carries across unconverted");
  }
}
