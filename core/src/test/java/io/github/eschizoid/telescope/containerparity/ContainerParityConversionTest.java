package io.github.eschizoid.telescope.containerparity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.Telescope;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedMap;
import java.util.SequencedSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Finding an allocator is not the same as the conversion working. The corpus gate asks only whether
 * the reflective path accepts a declared type, which leaves the allocator it picked unexecuted — so
 * a type could be accepted and still fail, or be allocated into the wrong shape, with nothing
 * noticing.
 *
 * <p>These convert through each declared type the parity fix newly accepts, and check the value
 * that comes out rather than that nothing was thrown.
 */
class ContainerParityConversionTest {

  record A(String v) {}

  record B(String v) {}

  record AbsListSrc(AbstractList<A> items) {}

  record AbsListDst(AbstractList<B> items) {}

  record AbsSetSrc(AbstractSet<A> items) {}

  record AbsSetDst(AbstractSet<B> items) {}

  record AbsMapSrc(AbstractMap<String, A> items) {}

  record AbsMapDst(AbstractMap<String, B> items) {}

  record SeqSetSrc(SequencedSet<A> items) {}

  record SeqSetDst(SequencedSet<B> items) {}

  record SeqMapSrc(SequencedMap<String, A> items) {}

  record SeqMapDst(SequencedMap<String, B> items) {}

  record HashtableSrc(Hashtable<String, A> items) {}

  record HashtableDst(Hashtable<String, B> items) {}

  private static <K> LinkedHashMap<String, A> oneEntry() {
    final var m = new LinkedHashMap<String, A>();
    m.put("k", new A("x"));
    return m;
  }

  @Test
  @DisplayName("an abstract List declaration converts, into the family's default")
  void abstractListConverts() {
    final var out = Telescope.mapper(AbsListSrc.class, AbsListDst.class).forward(
      new AbsListSrc(new ArrayList<>(List.of(new A("x"), new A("y"))))
    );

    assertEquals(List.of(new B("x"), new B("y")), List.copyOf(out.items()));
    assertInstanceOf(ArrayList.class, out.items(), "the family default is what stands in");
  }

  @Test
  @DisplayName("an abstract Set declaration converts, keeping insertion order")
  void abstractSetConverts() {
    final var src = new LinkedHashSet<A>();
    src.add(new A("x"));
    src.add(new A("y"));

    final var out = Telescope.mapper(AbsSetSrc.class, AbsSetDst.class).forward(new AbsSetSrc(src));

    assertEquals(List.of(new B("x"), new B("y")), List.copyOf(out.items()), "order survives the rebuild");
    assertInstanceOf(LinkedHashSet.class, out.items());
  }

  @Test
  @DisplayName("an abstract Map declaration converts its values and keeps its keys")
  void abstractMapConverts() {
    final var out = Telescope.mapper(AbsMapSrc.class, AbsMapDst.class).forward(new AbsMapSrc(oneEntry()));

    assertEquals(new B("x"), out.items().get("k"));
    assertInstanceOf(LinkedHashMap.class, out.items());
  }

  @Test
  @DisplayName("the sequenced interfaces convert too, being newer than the table they were missing from")
  void sequencedDeclarationsConvert() {
    final var set = new LinkedHashSet<A>();
    set.add(new A("x"));

    assertEquals(
      List.of(new B("x")),
      List.copyOf(Telescope.mapper(SeqSetSrc.class, SeqSetDst.class).forward(new SeqSetSrc(set)).items())
    );
    assertEquals(
      new B("x"),
      Telescope.mapper(SeqMapSrc.class, SeqMapDst.class).forward(new SeqMapSrc(oneEntry())).items().get("k")
    );
  }

  private static final String REFUSAL = "this container refuses to be built";

  /** A container whose constructor binds and then throws — a real failure of the class itself. */
  public static final class ThrowingCtor<E> extends ArrayList<E> {

    private static final long serialVersionUID = 1L;

    public ThrowingCtor() {
      throw new IllegalStateException(REFUSAL);
    }
  }

  record ThrowSrc(ThrowingCtor<A> items) {}

  record ThrowDst(ThrowingCtor<B> items) {}

  @Test
  @DisplayName("a container that cannot be built fails while the mapper is built, not on every use")
  void unbuildableContainerFailsAtPlanTime() {
    // The probe that decides whether an allocator answers has to call it, and the call can fail
    // for two different reasons. An abstract class binds a constructor that raises a linkage error
    // on invocation, which means "there is no allocator here" and should fall through. A class
    // whose constructor throws is a different thing: the allocator is real and the class is
    // broken, and swallowing that defers the same failure to every conversion — past the point
    // where both starters build their mappers and would have caught it.
    final var thrown = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(ThrowSrc.class, ThrowDst.class)
    );

    // A swallowed probe leaves the plan valid and throws nothing here, which is what assertThrows
    // above catches. The message assertion catches a different mutation: one where the plan is
    // still refused, but with telescope's own generic "no allocator for this type" text in place
    // of the constructor's failure -- a refusal that names the wrong cause and sends the adopter
    // to fix a table when the class is what is broken.
    assertEquals(REFUSAL, thrown.getMessage(), "the container's own refusal, not a generic one");
  }

  @Test
  @DisplayName("a concrete container outside the table converts through its own constructor")
  void hashtableConvertsAsItself() {
    // The other half of the fix: not a family default standing in, but the declared class itself,
    // reached through a public-lookup constructor. The runtime class is the assertion that
    // distinguishes the two routes.
    final var src = new Hashtable<String, A>();
    src.put("k", new A("x"));

    final var out = Telescope.mapper(HashtableSrc.class, HashtableDst.class).forward(new HashtableSrc(src));

    assertEquals(new B("x"), out.items().get("k"));
    assertInstanceOf(Hashtable.class, out.items(), "the declared class itself, not a stand-in");
  }
}
