package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.NativeImage;
import io.github.eschizoid.telescope.mapping.Compute;
import io.github.eschizoid.telescope.mapping.ComputeFromSource;
import io.github.eschizoid.telescope.mapping.Mapping;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The {@code compute(target, function)} row, which fills a target property with what a function
 * makes of the whole source, beside the {@code compute(target, supplier)} row it shares a name
 * with.
 */
class MappingComputeFromSourceTest {

  interface Named {
    String first();

    default String shout() {
      return first().toUpperCase();
    }
  }

  record Person(String first, String last, int age) implements Named {
    String display() {
      return first + " " + last;
    }

    int letters() {
      return first.length() + last.length();
    }
  }

  record Card(
    String first,
    String last,
    int age,
    String full,
    String shout,
    String display,
    int letters,
    Integer doubled,
    String missing
  ) {}

  record Plain(String first, String last, int age) {}

  record Labelled(String first, String last, int age, String label) {}

  record Made(String first, String last, int age, Wrapped made) {}

  static final AtomicInteger CALLS = new AtomicInteger();

  static final class Names {

    static String full(final Person person) {
      CALLS.incrementAndGet();
      return person.first() + " " + person.last();
    }

    static int doubled(final Person person) {
      return person.age() * 2;
    }

    static String none(final Person person) {
      return null;
    }

    static String foreign(final Plain plain) {
      return plain.first();
    }
  }

  /** A name with a form taking no argument and a form taking the source. */
  static final class Both {

    static String label() {
      return "plain";
    }

    static String label(final Person person) {
      return "from " + person.first();
    }
  }

  /** A name with a form taking no argument and a form taking something other than the source. */
  static final class Elsewhere {

    static String label() {
      return "plain";
    }

    static String label(final Integer number) {
      return "from " + number;
    }
  }

  /** A class with a constructor taking no argument and one taking the source. */
  public static final class Wrapped {

    final String from;

    public Wrapped() {
      this.from = "nothing";
    }

    public Wrapped(final Person person) {
      this.from = person.first();
    }
  }

  /** A supplier instance, which only the {@code Supplier} form takes. */
  static final class Stamp implements Supplier<Instant> {

    static final Stamp INSTANCE = new Stamp();

    @Override
    public Instant get() {
      return Instant.EPOCH;
    }
  }

  record Stamped(Instant at, Map<String, String> m, String name, int n) {}

  record User(String first) {
    String display() {
      return first;
    }

    int len() {
      return first.length();
    }
  }

  static final class Clock {

    static Instant now() {
      return Instant.EPOCH;
    }
  }

  private static Mapper<Person, Card> cardMapper() {
    return Telescope.mapper(
      Person.class,
      Card.class,
      compute(Card::full, Names::full),
      compute(Card::shout, Named::shout),
      compute(Card::display, Person::display),
      compute(Card::letters, Person::letters),
      compute(Card::doubled, Names::doubled),
      compute(Card::missing, Names::none)
    );
  }

  @Nested
  @DisplayName("which overload Java binds")
  class Binding {

    private Object supplierOf(final Mapping<?, ?> row) {
      return assertInstanceOf(Compute.class, row).supplier();
    }

    @Test
    @DisplayName("a reference whose name takes no argument or one binds as the supplier, through the shared subtype")
    void overloadedReferencesBindAsTheSupplier() {
      assertInstanceOf(Mapping.Fresh.class, supplierOf(compute(Stamped::at, Instant::now)));
      assertInstanceOf(Mapping.Fresh.class, supplierOf(compute(Stamped::m, HashMap::new)));
      assertInstanceOf(Mapping.Fresh.class, supplierOf(compute(Stamped::at, Clock::now)));
      assertInstanceOf(
        Mapping.Fresh.class,
        supplierOf(Mapping.<User, Stamped, Instant>compute(Stamped::at, Clock::now))
      );
    }

    @Test
    @DisplayName("a Supplier instance or variable binds to the Supplier form")
    void aSupplierValueBindsToTheSupplierForm() {
      final Supplier<String> variable = () -> "s";
      assertEquals(Stamp.INSTANCE, supplierOf(compute(Stamped::at, Stamp.INSTANCE)));
      assertEquals(variable, supplierOf(compute(Stamped::name, variable)));
      assertFalse(supplierOf(compute(Stamped::name, variable)) instanceof Mapping.Fresh<?>);
    }

    @Test
    @DisplayName("a reference taking one argument, or a Function variable, binds to the Function form")
    void aOneArgumentReferenceBindsToTheFunctionForm() {
      final Function<User, String> variable = User::display;
      assertInstanceOf(ComputeFromSource.class, compute(Card::full, Names::full));
      assertInstanceOf(ComputeFromSource.class, compute(Stamped::name, User::display));
      assertInstanceOf(ComputeFromSource.class, compute(Stamped::n, User::len));
      assertInstanceOf(ComputeFromSource.class, compute(Stamped::name, String::valueOf));
      assertInstanceOf(ComputeFromSource.class, compute(Stamped::name, variable));
      assertInstanceOf(ComputeFromSource.class, Mapping.<User, Stamped, String>compute(Stamped::name, User::display));
    }
  }

  @Nested
  @DisplayName("forward")
  class Forward {

    @Test
    @DisplayName("fills each target property from a static, an inherited, and an own method of the source")
    void fillsFromEachKindOfReference() {
      final var card = cardMapper().forward(new Person("ada", "lovelace", 36));
      assertEquals("ada lovelace", card.full());
      assertEquals("ADA", card.shout());
      assertEquals("ada lovelace", card.display());
      assertEquals(11, card.letters());
      assertEquals(72, card.doubled());
      assertNull(card.missing());
    }

    @Test
    @DisplayName("leaves every source property to pair with its namesake, though the function reads them")
    void namesakesStillPair() {
      final var card = cardMapper().forward(new Person("ada", "lovelace", 36));
      assertEquals("ada", card.first());
      assertEquals("lovelace", card.last());
      assertEquals(36, card.age());
    }

    @Test
    @DisplayName("hands a null source back as null without calling the function, on both kinds of mapper")
    void aNullSourceNeverReachesTheFunction() {
      CALLS.set(0);
      assertNull(cardMapper().forward(null));
      final var forwardOnly = Telescope.mapperForward(Person.class, Card.class, compute(Card::full, Names::full));
      assertNull(forwardOnly.forward(null));
      assertEquals(0, CALLS.get());
    }

    @Test
    @DisplayName("runs under when(...) only for a source the predicate accepts")
    void runsUnderWhen() {
      final var mapper = Telescope.mapper(
        Person.class,
        Card.class,
        Mapping.<Person, Card>when(person -> person.age() > 18, compute(Card::full, Names::full))
      );
      assertEquals("ada lovelace", mapper.forward(new Person("ada", "lovelace", 36)).full());
      assertNull(mapper.forward(new Person("tim", "lee", 9)).full());
    }

    @Test
    @DisplayName("a function whose parameter the source is not fails on the first forward call")
    void aFunctionTakingAnotherClassFailsWhenCalled() {
      // Java builds a function from a method reference with no trace of the class it takes, so
      // the runtime path cannot refuse it when the mapper is built; the generated path and the
      // verifier refuse it at compile time.
      final var mapper = Telescope.mapper(Person.class, Card.class, compute(Card::full, Names::foreign));
      assertThrows(ClassCastException.class, () -> mapper.forward(new Person("ada", "lovelace", 36)));
    }
  }

  @Nested
  @DisplayName("backward and patch")
  class BackwardAndPatch {

    @Test
    @DisplayName("skip the computed properties and never call the function")
    void skipTheComputedProperties() {
      final var mapper = cardMapper();
      final var card = new Card("ada", "lovelace", 36, "x", "y", "z", 1, 2, "w");
      CALLS.set(0);
      assertEquals(new Person("ada", "lovelace", 36), mapper.backward(card));
      assertEquals(new Person("ada", "lovelace", 36), mapper.patch(new Person("tim", "lee", 9), card));
      assertEquals(0, CALLS.get());
    }
  }

  @Nested
  @DisplayName("a reference whose name has a form taking no argument and one taking the source")
  class BothForms {

    @Test
    @DisplayName("is refused by name when the mapper is built, or runs as the supplier inside a native image")
    void isRefusedByName() {
      if (NativeImage.IN_IMAGE) {
        final var mapper = Telescope.mapper(Person.class, Labelled.class, compute(Labelled::label, Both::label));
        assertEquals("plain", mapper.forward(new Person("ada", "lovelace", 36)).label());
        return;
      }
      final var refused = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(Person.class, Labelled.class, compute(Labelled::label, Both::label))
      );
      assertEquals(
        "Deep map Person → Labelled: compute(...) fills 'label' from Both::label, which Java binds as a" +
          " supplier taking no argument, though Both.label(Person) takes the source Person. Hold the one meant" +
          " in a static final Supplier or Function field, or give the form taking the source a name of its own.",
        refused.getMessage()
      );
    }

    @Test
    @DisplayName("is refused by name for a constructor reference too")
    void aConstructorIsRefusedByName() {
      if (NativeImage.IN_IMAGE) return;
      final var refused = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(Person.class, Made.class, compute(Made::made, Wrapped::new))
      );
      assertTrue(refused.getMessage().contains("from Wrapped::new"), refused::getMessage);
      assertTrue(refused.getMessage().contains("though new Wrapped(Person) takes the source"), refused::getMessage);
    }

    @Test
    @DisplayName("runs as the supplier when the one-argument form takes something other than the source")
    void aFormTakingAnotherClassRunsAsTheSupplier() {
      final var mapper = Telescope.mapper(Person.class, Labelled.class, compute(Labelled::label, Elsewhere::label));
      assertEquals("plain", mapper.forward(new Person("ada", "lovelace", 36)).label());
    }

    @Test
    @DisplayName("runs as the function when it is held in a Function variable")
    void aFunctionVariableRunsAsTheFunction() {
      final Function<Person, String> held = Both::label;
      final var mapper = Telescope.mapper(Person.class, Labelled.class, compute(Labelled::label, held));
      assertEquals("from ada", mapper.forward(new Person("ada", "lovelace", 36)).label());
    }
  }
}
