package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.NativeImage;
import io.github.eschizoid.telescope.mapping.Compute;
import io.github.eschizoid.telescope.mapping.ComputeFromSource;
import io.github.eschizoid.telescope.mapping.Mapping;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

  record Held(String first, String last, int age, List<String> tags, AtomicReference<String> slot) {}

  record Inner(String n) {}

  record InnerDto(String n, String label) {}

  record Outer(Inner in, String a) {}

  record OuterDto(InnerDto in, String a, String c) {}

  /** A class implementing the function rather than a lambda or reference to one. */
  static final class FirstOf implements Mapping.FromSource<Person, String> {

    private static final long serialVersionUID = 1L;

    @Override
    public String apply(final Person person) {
      return person.first();
    }
  }

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

  /** A name with a form taking no argument and two forms the source fits, which sort by class. */
  static final class Several {

    static String label() {
      return "plain";
    }

    static String label(final Person person) {
      return "from person";
    }

    static String label(final Named named) {
      return "from named";
    }
  }

  /** A form taking the source declared by a superclass of the class the reference names. */
  static class Base {

    static String label(final Person person) {
      return "from " + person.first();
    }
  }

  static final class Derived extends Base {

    static String label() {
      return "plain";
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

  /** A name with a form taking no argument and a private form taking the source. */
  static final class Hidden {

    static String label() {
      return "plain";
    }

    @SuppressWarnings("unused")
    private static String label(final Person person) {
      return "from " + person.first();
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

  /**
   * A class that is both a supplier and a plain function, which only the {@code Supplier} form
   * takes.
   */
  static final class SupplierAndFunction implements Supplier<String>, Function<Object, String> {

    @Override
    public String get() {
      return "got";
    }

    @Override
    public String apply(final Object ignored) {
      return "applied";
    }
  }

  record Stamped(Instant at, Map<String, String> m, String name, int n, List<String> tags) {}

  record User(String first) {
    String display() {
      return first;
    }

    int len() {
      return first.length();
    }
  }

  record Other(String x) {
    static String f(final Other other) {
      return other.x();
    }
  }

  static final class Clock {

    static Instant now() {
      return Instant.EPOCH;
    }
  }

  private static final Mapping.FromSource<Person, String> FULL = Names::full;

  private static final Function<Person, String> PLAIN_FULL = Names::full;

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

  private static final Person ADA = new Person("ada", "lovelace", 36);

  @Nested
  @DisplayName("which overload Java binds")
  class Binding {

    private Object supplierOf(final Mapping<?, ?> row) {
      return assertInstanceOf(Compute.class, row).supplier();
    }

    @Test
    @DisplayName("a reference or lambda taking no argument binds as the supplier, through the shared subtype")
    void noArgumentBindsAsTheSupplier() {
      assertInstanceOf(Mapping.Fresh.class, supplierOf(compute(Stamped::at, Instant::now)));
      assertInstanceOf(Mapping.Fresh.class, supplierOf(compute(Stamped::m, HashMap::new)));
      assertInstanceOf(Mapping.Fresh.class, supplierOf(compute(Stamped::at, Clock::now)));
      assertInstanceOf(Mapping.Fresh.class, supplierOf(compute(Stamped::name, () -> "x")));
      assertInstanceOf(Mapping.Fresh.class, supplierOf(compute(Stamped::tags, List::of)));
      assertInstanceOf(
        Mapping.Fresh.class,
        supplierOf(Mapping.<User, Stamped, Instant>compute(Stamped::at, Clock::now))
      );
    }

    @Test
    @DisplayName(
      "a Supplier instance or variable, and a class both a Supplier and a Function, bind to the Supplier form"
    )
    void aSupplierValueBindsToTheSupplierForm() {
      final Supplier<String> variable = () -> "s";
      final var both = new SupplierAndFunction();
      assertSame(Stamp.INSTANCE, supplierOf(compute(Stamped::at, Stamp.INSTANCE)));
      assertSame(variable, supplierOf(compute(Stamped::name, variable)));
      assertSame(both, supplierOf(compute(Stamped::name, both)));
      assertFalse(supplierOf(compute(Stamped::name, variable)) instanceof Mapping.Fresh<?>);
    }

    @Test
    @DisplayName(
      "a reference taking one argument, a typed lambda, and a held function's apply bind to the FromSource form"
    )
    void aOneArgumentFunctionBindsToTheFromSourceForm() {
      assertInstanceOf(ComputeFromSource.class, compute(Card::full, Names::full));
      assertInstanceOf(ComputeFromSource.class, compute(Stamped::name, User::display));
      assertInstanceOf(ComputeFromSource.class, compute(Stamped::n, User::len));
      assertInstanceOf(ComputeFromSource.class, compute(Stamped::name, Other::f));
      assertInstanceOf(ComputeFromSource.class, compute(Stamped::name, String::valueOf));
      assertInstanceOf(ComputeFromSource.class, compute(Stamped::name, (User user) -> user.first()));
      assertInstanceOf(ComputeFromSource.class, compute(Card::full, FULL));
      assertInstanceOf(ComputeFromSource.class, compute(Card::full, PLAIN_FULL::apply));
      assertInstanceOf(ComputeFromSource.class, Mapping.<User, Stamped, String>compute(Stamped::name, User::display));
    }
  }

  @Nested
  @DisplayName("forward")
  class Forward {

    @Test
    @DisplayName("fills each target property from a static, an inherited, and an own method of the source")
    void fillsFromEachKindOfReference() {
      final var card = cardMapper().forward(ADA);
      assertEquals("ada lovelace", card.full());
      assertEquals("ADA", card.shout());
      assertEquals("ada lovelace", card.display());
      assertEquals(11, card.letters());
      assertEquals(72, card.doubled());
      assertNull(card.missing());
    }

    @Test
    @DisplayName("fills a property from a held function, its apply, and a typed lambda")
    void fillsFromAHeldFunctionAndALambda() {
      for (final var function : List.<Mapping.FromSource<Person, String>>of(
        FULL,
        PLAIN_FULL::apply,
        (Person person) -> person.first() + " " + person.last()
      )) {
        final var mapper = Telescope.mapper(Person.class, Labelled.class, compute(Labelled::label, function));
        assertEquals("ada lovelace", mapper.forward(ADA).label());
      }
    }

    @Test
    @DisplayName("leaves every source property to pair with its namesake, though the function reads them")
    void namesakesStillPair() {
      final var card = cardMapper().forward(ADA);
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
      assertEquals("ada lovelace", mapper.forward(ADA).full());
      assertNull(mapper.forward(new Person("tim", "lee", 9)).full());
    }
  }

  @Nested
  @DisplayName("the row itself")
  class Row {

    @Test
    @DisplayName("names no source property and no pair, so the mapper's own pair keys it and nothing pairs by it")
    void namesNoSourceProperty() {
      final var row = assertInstanceOf(ComputeFromSource.class, compute(Card::full, Names::full));
      assertNull(row.sourceClass());
      assertNull(row.targetClass());
      assertNull(row.sourceField());
      assertNull(row.targetField());
    }

    @Test
    @DisplayName("is filled by a class implementing the function, which nothing decodes")
    void anImplementingClassRuns() {
      final var mapper = Telescope.mapper(Person.class, Labelled.class, compute(Labelled::label, new FirstOf()));
      assertEquals("ada", mapper.forward(ADA).label());
    }

    @Test
    @DisplayName("a Fresh read as a function ignores its argument and supplies")
    void freshAppliesAsItsSupplier() {
      final Mapping.Fresh<String> fresh = () -> "made";
      assertEquals("made", fresh.apply(ADA));
    }

    @Test
    @DisplayName("beside a nested pair's rename leaves that pair's namesake unwritten, which a strict mapper refuses")
    void doesNotWriteANestedNamesake() {
      final var refused = assertThrows(IllegalStateException.class, () ->
        Telescope.mapper(
          Outer.class,
          OuterDto.class,
          Mapping.to(Inner::n, InnerDto::label),
          compute(OuterDto::c, Outer::a)
        )
      );
      assertEquals(
        "Deep map Inner → InnerDto: target field 'n' is not paired with the source field of its name, which" +
          " another row reads or drops. Add a row that writes 'n'.",
        refused.getMessage()
      );
      final var lenient = Telescope.mapperForward(
        Outer.class,
        OuterDto.class,
        Mapping.to(Inner::n, InnerDto::label),
        compute(OuterDto::c, Outer::a)
      );
      assertEquals(new OuterDto(new InnerDto(null, "x"), "y", "y"), lenient.forward(new Outer(new Inner("x"), "y")));
    }
  }

  @Nested
  @DisplayName("a function taking a class the source is not")
  class OffSource {

    @Test
    @DisplayName("is refused by name when the mapper is built, or fails on its first call inside a native image")
    void isRefusedWhenBuilt() {
      if (NativeImage.IN_IMAGE) {
        final var mapper = Telescope.mapper(Person.class, Card.class, compute(Card::full, Names::foreign));
        assertThrows(ClassCastException.class, () -> mapper.forward(ADA));
        return;
      }
      final var refused = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(Person.class, Card.class, compute(Card::full, Names::foreign))
      );
      assertEquals(
        "Deep map Person → Card: compute(...) fills 'full' from Names::foreign, which takes Plain, and the" +
          " source Person is not one. Name a method taking Person, or a method of Person.",
        refused.getMessage()
      );
    }

    @Test
    @DisplayName("is refused by name for a typed lambda and an unbound method of another class")
    void aLambdaAndAnUnboundMethodAreRefused() {
      if (NativeImage.IN_IMAGE) return;
      final var lambda = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(Person.class, Labelled.class, compute(Labelled::label, (Plain plain) -> plain.first()))
      );
      assertEquals(
        "Deep map Person → Labelled: compute(...) fills 'label' from a lambda, which takes Plain, and the" +
          " source Person is not one. Name a method taking Person, or a method of Person.",
        lambda.getMessage()
      );
      final var unbound = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(Person.class, Labelled.class, compute(Labelled::label, User::display))
      );
      assertEquals(
        "Deep map Person → Labelled: compute(...) fills 'label' from User::display, which takes User, and the" +
          " source Person is not one. Name a method taking Person, or a method of Person.",
        unbound.getMessage()
      );
    }

    @Test
    @DisplayName("is kept when it takes a type the source extends")
    void aSupertypeIsKept() {
      final var mapper = Telescope.mapper(Person.class, Labelled.class, compute(Labelled::label, Named::shout));
      assertEquals("ADA", mapper.forward(ADA).label());
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
      assertEquals(ADA, mapper.backward(card));
      assertEquals(ADA, mapper.patch(new Person("tim", "lee", 9), card));
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
        assertEquals("plain", mapper.forward(ADA).label());
        return;
      }
      final var refused = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(Person.class, Labelled.class, compute(Labelled::label, Both::label))
      );
      assertEquals(
        "Deep map Person → Labelled: compute(...) fills 'label' from Both::label, which Java binds as a" +
          " supplier taking no argument, though Both.label(Person) takes the source Person. Hold the one meant" +
          " in a static final Supplier or FromSource field, or give the form taking the source a name of its own.",
        refused.getMessage()
      );
    }

    @Test
    @DisplayName("is refused by name for a constructor reference, or runs the constructor taking nothing in an image")
    void aConstructorIsRefusedByName() {
      if (NativeImage.IN_IMAGE) {
        final var mapper = Telescope.mapper(Person.class, Made.class, compute(Made::made, Wrapped::new));
        assertEquals("nothing", mapper.forward(ADA).made().from);
        return;
      }
      final var refused = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(Person.class, Made.class, compute(Made::made, Wrapped::new))
      );
      assertEquals(
        "Deep map Person → Made: compute(...) fills 'made' from Wrapped::new, which Java binds as a supplier" +
          " taking no argument, though new Wrapped(Person) takes the source Person. Hold the one meant in a" +
          " static final Supplier or FromSource field, or give the form taking the source a name of its own.",
        refused.getMessage()
      );
    }

    @Test
    @DisplayName("names the form whose parameter's class sorts first, and the class the reference names")
    void namesOneFormAndTheReferencedClass() {
      if (NativeImage.IN_IMAGE) return;
      final var several = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(Person.class, Labelled.class, compute(Labelled::label, Several::label))
      );
      assertEquals(
        "Deep map Person → Labelled: compute(...) fills 'label' from Several::label, which Java binds as a" +
          " supplier taking no argument, though Several.label(Named) takes the source Person. Hold the one meant" +
          " in a static final Supplier or FromSource field, or give the form taking the source a name of its own.",
        several.getMessage()
      );
      final var inherited = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(Person.class, Labelled.class, compute(Labelled::label, Derived::label))
      );
      assertEquals(
        "Deep map Person → Labelled: compute(...) fills 'label' from Derived::label, which Java binds as a" +
          " supplier taking no argument, though Base.label(Person) takes the source Person. Hold the one meant" +
          " in a static final Supplier or FromSource field, or give the form taking the source a name of its own.",
        inherited.getMessage()
      );
    }

    @Test
    @DisplayName("runs as the supplier when the one-argument form takes something other than the source")
    void aFormTakingAnotherClassRunsAsTheSupplier() {
      final var mapper = Telescope.mapper(Person.class, Labelled.class, compute(Labelled::label, Elsewhere::label));
      assertEquals("plain", mapper.forward(ADA).label());
    }

    @Test
    @DisplayName("runs as the supplier when the form taking the source is private")
    void aPrivateFormRunsAsTheSupplier() {
      final var mapper = Telescope.mapper(Person.class, Labelled.class, compute(Labelled::label, Hidden::label));
      assertEquals("plain", mapper.forward(ADA).label());
    }

    @Test
    @DisplayName(
      "runs as the supplier when the form taking one value takes any Object, as List::of and AtomicReference::new do"
    )
    void aFormTakingAnyObjectRunsAsTheSupplier() {
      final var mapper = Telescope.mapper(
        Person.class,
        Held.class,
        compute(Held::tags, List::of),
        compute(Held::slot, AtomicReference::new)
      );
      final var held = mapper.forward(ADA);
      assertEquals(List.of(), held.tags());
      assertNull(held.slot().get());
    }

    @Test
    @DisplayName("runs as the function when it is held as a FromSource, or passed as a held function's apply")
    void aHeldFunctionRunsAsTheFunction() {
      final Mapping.FromSource<Person, String> held = Both::label;
      final Function<Person, String> plain = Both::label;
      for (final var function : List.of(held, (Mapping.FromSource<Person, String>) plain::apply)) {
        final var mapper = Telescope.mapper(Person.class, Labelled.class, compute(Labelled::label, function));
        assertEquals("from ada", mapper.forward(ADA).label());
      }
    }
  }
}
