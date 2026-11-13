package io.github.eschizoid.telescope.jackson;

import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;
import static io.github.eschizoid.telescope.mapping.MapExtractStep.required;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.introspection.OpticNode;
import io.github.eschizoid.telescope.jackson.Fixtures.Address;
import io.github.eschizoid.telescope.jackson.Fixtures.BadKeys;
import io.github.eschizoid.telescope.jackson.Fixtures.Booking;
import io.github.eschizoid.telescope.jackson.Fixtures.Customer;
import io.github.eschizoid.telescope.jackson.Fixtures.Holder;
import io.github.eschizoid.telescope.jackson.Fixtures.Inner;
import io.github.eschizoid.telescope.jackson.Fixtures.Keyed;
import io.github.eschizoid.telescope.jackson.Fixtures.Line;
import io.github.eschizoid.telescope.jackson.Fixtures.Listy;
import io.github.eschizoid.telescope.jackson.Fixtures.Nesting;
import io.github.eschizoid.telescope.jackson.Fixtures.Order;
import io.github.eschizoid.telescope.jackson.Fixtures.Outer;
import io.github.eschizoid.telescope.jackson.Fixtures.Pair;
import io.github.eschizoid.telescope.jackson.Fixtures.Scalars;
import io.github.eschizoid.telescope.jackson.Fixtures.Shape;
import io.github.eschizoid.telescope.jackson.Fixtures.Status;
import io.github.eschizoid.telescope.jackson.Fixtures.Tree;
import io.github.eschizoid.telescope.jackson.Fixtures.WithShape;
import io.github.eschizoid.telescope.jackson.Fixtures.WithThread;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.json.JsonFactory;

class JsonBinderTest {

  private static final JsonBinder<Order> ORDERS = TelescopeJson.fromJson(Order.class);
  private static final JsonBinder<Line> LINES = TelescopeJson.fromJson(Line.class);
  private static final JsonBinder<Scalars> SCALARS = TelescopeJson.fromJson(Scalars.class);

  private static IllegalArgumentException refusal(final Runnable read) {
    return assertThrows(IllegalArgumentException.class, read::run);
  }

  @Nested
  class Binding {

    @Test
    @DisplayName("every kind of property reads from the JSON kind it is written as")
    void readsEveryKind() {
      final var order = ORDERS.read(
        """
        {"number":"SO-1","lines":[{"sku":"MUG","quantity":2,"price":12.50}],"status":"PAID",
         "note":"ring twice","counts":{"a":1},"tags":["x","y"],"placedOn":"2026-01-02T03:04:05Z",
         "gift":true,"version":9000000000,"weight":1.5,"grade":"A","serial":123456789012345678901234567890,
         "extra":{"n":[1,2]}}
        """
      );
      assertEquals(
        new Order(
          "SO-1",
          List.of(new Line("MUG", 2, new BigDecimal("12.50"))),
          Status.PAID,
          Optional.of("ring twice"),
          Map.of("a", 1),
          Set.of("x", "y"),
          Instant.parse("2026-01-02T03:04:05Z"),
          true,
          9_000_000_000L,
          1.5,
          'A',
          new BigInteger("123456789012345678901234567890"),
          Map.of("n", List.of(1, 2))
        ),
        order
      );
    }

    @Test
    @DisplayName("a missing key and a null key both leave the declared type's default")
    void missingAndNullDefault() {
      final var defaults = new Order(
        null,
        List.of(),
        null,
        Optional.empty(),
        Map.of(),
        Set.of(),
        null,
        false,
        0L,
        0.0,
        '\0',
        null,
        null
      );
      assertEquals(defaults, ORDERS.read("{}"));
      assertEquals(
        defaults,
        ORDERS.read(
          """
          {"number":null,"lines":null,"status":null,"note":null,"counts":null,"tags":null,"placedOn":null,
           "gift":null,"version":null,"weight":null,"grade":null,"serial":null,"extra":null}
          """
        )
      );
    }

    @Test
    @DisplayName("a key no property reads is skipped, whatever it holds")
    void unknownKeysAreSkipped() {
      assertEquals(
        new Line("MUG", 3, null),
        LINES.read("{\"colour\":{\"r\":[1,{\"g\":2}]},\"sku\":\"MUG\",\"more\":[[],{}],\"quantity\":3}")
      );
    }

    @Test
    @DisplayName("a key that appears twice keeps the value it holds last, null included")
    void duplicateKeyKeepsTheLast() {
      assertEquals(
        new Line("B", 0, null),
        LINES.read("{\"sku\":\"A\",\"quantity\":5,\"sku\":\"B\",\"quantity\":null}")
      );
    }

    @Test
    @DisplayName("each object level has its own keys")
    void eachLevelHasItsOwnKeys() {
      assertEquals(
        new Outer("o", new Inner("i")),
        TelescopeJson.fromJson(Outer.class).read("{\"inner\":{\"id\":\"i\"},\"id\":\"o\"}")
      );
    }

    @Test
    @DisplayName("a type that holds itself reads at every depth")
    void recursiveType() {
      assertEquals(
        new Tree(
          "root",
          List.of(new Tree("leaf", List.of()), new Tree("branch", List.of(new Tree("twig", List.of()))))
        ),
        TelescopeJson.fromJson(Tree.class).read(
          "{\"name\":\"root\",\"children\":[{\"name\":\"leaf\"},{\"name\":\"branch\",\"children\":[{\"name\":\"twig\"}]}]}"
        )
      );
    }

    @Test
    @DisplayName("a map key is read from text as a String, an enum, a JDK value type or a primitive")
    void mapKeys() {
      final var id = UUID.randomUUID();
      assertEquals(
        new Keyed(Map.of(Status.PAID, "yes"), Map.of(id, 4), Map.of(7, "seven")),
        TelescopeJson.fromJson(Keyed.class).read(
          "{\"byStatus\":{\"PAID\":\"yes\"},\"byId\":{\"" + id + "\":4},\"byNumber\":{\"7\":\"seven\"}}"
        )
      );
    }

    @Test
    @DisplayName("a null element takes its own type's absent value")
    void nullElements() {
      final var nesting = TelescopeJson.fromJson(Nesting.class).read(
        "{\"grid\":[[1],null],\"maybe\":[null,\"a\"],\"groups\":{\"g\":null,\"h\":[\"x\"]}}"
      );
      assertEquals(List.of(List.of(1), List.of()), nesting.grid());
      assertEquals(List.of(Optional.empty(), Optional.of("a")), nesting.maybe());
      assertEquals(Map.of("g", List.of(), "h", List.of("x")), nesting.groups());
    }

    @Test
    @DisplayName("BigDecimal and BigInteger keep every digit, and a long integer reads into a double")
    void losslessNumbers() {
      final var scalars = SCALARS.read(
        """
        {"i":-1,"l":9223372036854775807,"s":-32768,"b":127,"d":3,"f":2.5,"z":false,"c":"q","boxed":5,
         "decimal":123456789012345678901234567890.123456789,"big":-98765432109876543210,"text":"t","chars":"cs",
         "id":"00000000-0000-0000-0000-000000000001"}
        """
      );
      assertEquals(Long.MAX_VALUE, scalars.l());
      assertEquals((short) -32768, scalars.s());
      assertEquals((byte) 127, scalars.b());
      assertEquals(3.0, scalars.d());
      assertEquals(2.5f, scalars.f());
      assertEquals('q', scalars.c());
      assertEquals(new BigDecimal("123456789012345678901234567890.123456789"), scalars.decimal());
      assertEquals(new BigInteger("-98765432109876543210"), scalars.big());
      assertEquals("cs", scalars.chars().toString());
      assertEquals(new UUID(0, 1), scalars.id());
    }

    @Test
    @DisplayName("a property declared Object takes the value as an untyped map holds it")
    void objectProperty() {
      final var order = ORDERS.read("{\"extra\":[1,9000000000,99999999999999999999,0.1,\"s\",true,false,null]}");
      final var values = (List<?>) order.extra();
      assertInstanceOf(Integer.class, values.get(0));
      assertInstanceOf(Long.class, values.get(1));
      assertInstanceOf(BigInteger.class, values.get(2));
      assertEquals(new BigDecimal("0.1"), values.get(3));
      final var expectedTail = new ArrayList<Object>(List.of("s", true, false));
      expectedTail.add(null);
      assertEquals(expectedTail, values.subList(4, 8));
    }

    @Test
    @DisplayName("a bean is built through its writer, and a property it cannot set is never read")
    void bean() {
      final var binder = TelescopeJson.fromJson(Customer.class);
      final var customer = binder.read("{\"name\":\"Ada\",\"visits\":3,\"tags\":[\"vip\"],\"label\":{\"ignored\":1}}");
      assertEquals("Ada", customer.getName());
      assertEquals(3, customer.getVisits());
      assertEquals(List.of("vip"), customer.getTags());
      assertTrue(
        binder
          .explain()
          .skipped()
          .stream()
          .anyMatch(skip -> skip.field().equals("label"))
      );
    }

    @Test
    @DisplayName("the document null reads as null, and so does a null list")
    void documentNull() {
      assertNull(ORDERS.read("null"));
      assertNull(LINES.readList("null"));
    }
  }

  @Nested
  class Refusals {

    @Test
    @DisplayName("a fraction in an integral property is refused, naming the property and its position")
    void fractionIntoInt() {
      assertEquals(
        "'quantity' got 1.5, not an int, at line 1, column 23",
        refusal(() -> LINES.read("{\"sku\":\"x\",\"quantity\":1.5}")).getMessage()
      );
    }

    @Test
    @DisplayName("an integer past its range is refused rather than wrapped")
    void outOfRange() {
      final var refused = refusal(() -> LINES.read("{\"quantity\":2147483648}"));
      assertEquals("'quantity' got 2147483648, not an int, at line 1, column 13", refused.getMessage());
      assertInstanceOf(StreamReadException.class, refused.getCause());
      assertEquals(
        "'b' got 128, not a byte, at line 1, column 6",
        refusal(() -> SCALARS.read("{\"b\":128}")).getMessage()
      );
      assertEquals(
        "'l' got 9223372036854775808, not a long, at line 1, column 6",
        refusal(() -> SCALARS.read("{\"l\":9223372036854775808}")).getMessage()
      );
      assertEquals(
        "'d' got 1e400, not a double, at line 1, column 6",
        refusal(() -> SCALARS.read("{\"d\":1e400}")).getMessage()
      );
      assertEquals(
        "'f' got 1e40, not a float, at line 1, column 6",
        refusal(() -> SCALARS.read("{\"f\":1e40}")).getMessage()
      );
    }

    @Test
    @DisplayName("a value is never read from another JSON kind")
    void crossKindIsRefused() {
      assertEquals(
        "'quantity' got \"12\", not an int, at line 1, column 13",
        refusal(() -> LINES.read("{\"quantity\":\"12\"}")).getMessage()
      );
      assertEquals(
        "'sku' got 12, not a String, at line 1, column 8",
        refusal(() -> LINES.read("{\"sku\":12}")).getMessage()
      );
      assertEquals(
        "'price' got \"1.50\", not a BigDecimal, at line 1, column 10",
        refusal(() -> LINES.read("{\"price\":\"1.50\"}")).getMessage()
      );
      assertEquals(
        "'z' got 1, not a boolean, at line 1, column 6",
        refusal(() -> SCALARS.read("{\"z\":1}")).getMessage()
      );
      assertEquals(
        "'c' got \"ab\", not a char, at line 1, column 6",
        refusal(() -> SCALARS.read("{\"c\":\"ab\"}")).getMessage()
      );
      assertEquals(
        "'boxed' got true, not an Integer, at line 1, column 10",
        refusal(() -> SCALARS.read("{\"boxed\":true}")).getMessage()
      );
      assertEquals(
        "'big' got 1.0, not a BigInteger, at line 1, column 8",
        refusal(() -> SCALARS.read("{\"big\":1.0}")).getMessage()
      );
      assertEquals(
        "'chars' got an array, not a CharSequence, at line 1, column 10",
        refusal(() -> SCALARS.read("{\"chars\":[]}")).getMessage()
      );
      assertEquals(
        "'id' got 7, not a UUID, at line 1, column 7",
        refusal(() -> SCALARS.read("{\"id\":7}")).getMessage()
      );
    }

    @Test
    @DisplayName("a refusal inside a list names the element's position and the line it is on")
    void positionInAList() {
      assertEquals(
        "'lines[1].quantity' got 1.5, not an int, at line 3, column 15",
        refusal(() ->
          ORDERS.read(
            """
            {"lines":[
              {"sku":"A","quantity":1},
              {"quantity":1.5}]}
            """
          )
        ).getMessage()
      );
    }

    @Test
    @DisplayName("an enum constant is read by its exact name only")
    void unknownEnumConstant() {
      assertEquals(
        "'status' got \"paid\", not a constant of Status, at line 1, column 11",
        refusal(() -> ORDERS.read("{\"status\":\"paid\"}")).getMessage()
      );
    }

    @Test
    @DisplayName("text that does not parse as its JDK value type is refused with the parse failure as the cause")
    void unparsableText() {
      final var refused = refusal(() -> ORDERS.read("{\"placedOn\":\"yesterday\"}"));
      assertEquals("'placedOn' got \"yesterday\", not an Instant, at line 1, column 13", refused.getMessage());
      assertInstanceOf(DateTimeParseException.class, refused.getCause());
    }

    @Test
    @DisplayName("a structure of the wrong kind is named by its kind")
    void wrongStructure() {
      assertEquals(
        "'lines' got an object, not a List, at line 1, column 10",
        refusal(() -> ORDERS.read("{\"lines\":{}}")).getMessage()
      );
      assertEquals(
        "'tags' got \"x\", not a Set, at line 1, column 9",
        refusal(() -> ORDERS.read("{\"tags\":\"x\"}")).getMessage()
      );
      assertEquals(
        "'counts' got an array, not a Map, at line 1, column 11",
        refusal(() -> ORDERS.read("{\"counts\":[]}")).getMessage()
      );
      assertEquals(
        "'lines[0]' got 4, not a Line, at line 1, column 11",
        refusal(() -> ORDERS.read("{\"lines\":[4]}")).getMessage()
      );
      assertEquals(
        "the document got an array, not an Order, at line 1, column 1",
        refusal(() -> ORDERS.read("[]")).getMessage()
      );
      assertEquals(
        "the document got no value, not an Order, at line 1, column 0",
        refusal(() -> ORDERS.read("")).getMessage()
      );
    }

    @Test
    @DisplayName("a map key or value that does not read names the key it sits under")
    void mapPositions() {
      final var keyed = TelescopeJson.fromJson(Keyed.class);
      assertEquals(
        "'byNumber[x]' got \"x\", not an Integer, at line 1, column 14",
        refusal(() -> keyed.read("{\"byNumber\":{\"x\":\"seven\"}}")).getMessage()
      );
      assertEquals(
        "'counts[a]' got \"1\", not an Integer, at line 1, column 16",
        refusal(() -> ORDERS.read("{\"counts\":{\"a\":\"1\"}}")).getMessage()
      );
    }

    @Test
    @DisplayName("JSON that does not parse throws jackson-core's own exception")
    void malformedJson() {
      assertThrows(StreamReadException.class, () -> LINES.read("{\"sku\":"));
    }

    @Test
    @DisplayName("a document holds one value: text or a second value after it is refused, by every entry point")
    void trailingContentIsRefused() {
      final var one = "{\"sku\":\"A\"}";
      assertEquals(new Line("A", 0, null), LINES.read(one + " \n\t "));
      assertThrows(StreamReadException.class, () -> LINES.read(one + " x"));
      assertEquals(
        "Trailing token (START_OBJECT) after the document's value: a JSON document holds one value",
        assertThrows(StreamReadException.class, () -> LINES.read(one + " {\"sku\":\"B\"}")).getOriginalMessage()
      );
      assertThrows(StreamReadException.class, () -> LINES.read("null 1"));
      assertThrows(StreamReadException.class, () -> LINES.read((one + " 1").getBytes(StandardCharsets.UTF_8)));
      assertThrows(StreamReadException.class, () ->
        LINES.read(new ByteArrayInputStream((one + " []").getBytes(StandardCharsets.UTF_8)))
      );
      assertThrows(StreamReadException.class, () -> LINES.readList("[" + one + "] []"));
      assertThrows(StreamReadException.class, () ->
        LINES.readList(new ByteArrayInputStream(("[" + one + "] x").getBytes(StandardCharsets.UTF_8)))
      );
    }
  }

  @Nested
  class Rows {

    @Test
    @DisplayName("a row reads another key, converts the value itself, and reaches a nested type's property")
    void rowsOverride() {
      final var binder = TelescopeJson.fromJson(
        Booking.class,
        extract("booking_type", Booking::bookingType, Object::toString),
        extract("priority_level", Booking::priority, v -> ((BigDecimal) v).intValue()),
        extract("postal_code", Address::zip, Object::toString)
      );
      assertEquals(
        new Booking("12", 2, new Address("Main", "90210")),
        binder.read(
          "{\"booking_type\":12,\"priority_level\":2.7,\"address\":{\"street\":\"Main\",\"postal_code\":90210}}"
        )
      );
      assertEquals(
        List.of("booking_type", "priority_level", "address"),
        binder.explain().extractions().stream().map(OpticNode.Extracted::key).toList()
      );
    }

    @Test
    @DisplayName("a converter is not called for a missing or null value")
    void converterSeesOnlyValues() {
      final var called = new AtomicBoolean();
      final var binder = TelescopeJson.fromJson(
        Line.class,
        extract("sku", Line::sku, v -> {
          called.set(true);
          return v.toString();
        })
      );
      assertEquals(new Line(null, 0, null), binder.read("{\"sku\":null}"));
      assertEquals(new Line(null, 0, null), binder.read("{}"));
      assertFalse(called.get());
    }

    @Test
    @DisplayName("a required row refuses a document without its key, naming where the object is")
    void requiredRow() {
      final var binder = TelescopeJson.fromJson(Order.class, required("sku", Line::sku, Object::toString));
      assertEquals(
        "TelescopeJson.fromJson: the object at 'lines[0]' carries no value for required key \"sku\"" +
          " (component 'sku') of Line, at line 1, column 24",
        refusal(() -> binder.read("{\"lines\":[{\"quantity\":1}]}")).getMessage()
      );
      assertEquals(
        "TelescopeJson.fromJson: the object at 'lines[0]' carries no value for required key \"sku\"" +
          " (component 'sku') of Line, at line 1, column 32",
        refusal(() -> binder.read("{\"lines\":[{\"sku\":\"A\",\"sku\":null}]}")).getMessage()
      );
    }

    @Test
    @DisplayName("the keys a target's @FromMap binder declares required are required in JSON too")
    void declaredRequired() {
      final var binder = TelescopeJson.fromJson(Signup.class);
      assertEquals(new Signup("a@b.c", null), binder.read("{\"email\":\"a@b.c\"}"));
      assertEquals(
        "TelescopeJson.fromJson: the document carries no value for required key \"email\" (component" +
          " 'email') of Signup, at line 1, column 14",
        refusal(() -> binder.read("{\"name\":\"Ada\"}")).getMessage()
      );
      assertEquals(
        OpticNode.WhenAbsent.REFUSES,
        binder
          .explain()
          .extractions()
          .stream()
          .filter(row -> row.field().equals("email"))
          .findFirst()
          .orElseThrow()
          .whenAbsent()
      );
    }
  }

  @Nested
  class BuildTime {

    @Test
    @DisplayName("a property whose type has no reading from JSON is refused when the binder is built")
    void unbindableTypes() {
      assertEquals(
        "TelescopeJson.fromJson: component 'value' of Holder is declared T and no row names it: T can't be" +
          " read from a JSON value (type variable / array / unsupported kind); name it with an extract(key," +
          " accessor, converter) row",
        refusal(() -> TelescopeJson.fromJson(Holder.class)).getMessage()
      );
      assertTrue(
        refusal(() -> TelescopeJson.fromJson(Listy.class))
          .getMessage()
          .contains("java.util.ArrayList is a collection subtype")
      );
      assertTrue(
        refusal(() -> TelescopeJson.fromJson(WithThread.class))
          .getMessage()
          .contains("java.lang.Thread can't be read from a JSON value")
      );
      assertTrue(
        refusal(() -> TelescopeJson.fromJson(WithShape.class))
          .getMessage()
          .contains("is neither a record nor a class with properties to bind")
      );
      assertTrue(
        refusal(() -> TelescopeJson.fromJson(BadKeys.class))
          .getMessage()
          .contains("a JSON object's keys are text")
      );
      assertEquals(
        "TelescopeJson.fromJson: " +
          Shape.class.getName() +
          " is neither a record nor a class with properties to bind, so no JSON object can build it",
        refusal(() -> TelescopeJson.fromJson(Shape.class)).getMessage()
      );
    }

    @Test
    @DisplayName("a row is refused when it names a type the target never holds, or repeats another")
    void rowRefusals() {
      assertEquals(
        "TelescopeJson.fromJson: a row names 'zip' of Address, which Line never holds, so the row would" +
          " never be read",
        refusal(() -> TelescopeJson.fromJson(Line.class, extract("z", Address::zip, Object::toString))).getMessage()
      );
      assertEquals(
        "TelescopeJson.fromJson: two rows name 'sku' of Line; keep one",
        refusal(() ->
          TelescopeJson.fromJson(
            Line.class,
            extract("a", Line::sku, Object::toString),
            extract("b", Line::sku, Object::toString)
          )
        ).getMessage()
      );
      assertEquals(
        "TelescopeJson.fromJson: 'left' and 'right' of Pair both read the key \"left\", and a JSON value is" +
          " read once; give one of them a row reading another key",
        refusal(() -> TelescopeJson.fromJson(Pair.class, extract("left", Pair::right, Object::toString))).getMessage()
      );
      assertTrue(
        refusal(() -> TelescopeJson.fromJson(Customer.class, extract("label", Customer::getLabel, Object::toString)))
          .getMessage()
          .contains("which has no setter, builder method or constructor parameter to write it")
      );
    }
  }

  @Nested
  class EntryPoints {

    @Test
    @DisplayName("a list of objects reads in order, a null element stays null, and anything else is refused")
    void readList() {
      assertEquals(
        Arrays.asList(new Line("A", 1, null), null),
        LINES.readList("[{\"sku\":\"A\",\"quantity\":1},null]")
      );
      assertEquals(
        "'[1]' got 3, not a Line, at line 1, column 5",
        refusal(() -> LINES.readList("[{},3]")).getMessage()
      );
      assertEquals(
        "'[0].quantity' got \"x\", not an int, at line 1, column 14",
        refusal(() -> LINES.readList("[{\"quantity\":\"x\"}]")).getMessage()
      );
      assertEquals(
        "the document got an object, not an array of Line, at line 1, column 1",
        refusal(() -> LINES.readList("{}")).getMessage()
      );
    }

    @Test
    @DisplayName("bytes and a stream read the same value, and the stream is closed")
    void bytesAndStreams() {
      final var json = "{\"sku\":\"é\",\"quantity\":1}";
      assertEquals(new Line("é", 1, null), LINES.read(json.getBytes(StandardCharsets.UTF_8)));
      final var closed = new AtomicBoolean();
      final var in = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)) {
        @Override
        public void close() {
          closed.set(true);
        }
      };
      assertEquals(new Line("é", 1, null), LINES.read(in));
      assertTrue(closed.get());
      assertEquals(
        List.of(new Line("é", 1, null)),
        LINES.readList(new ByteArrayInputStream(("[" + json + "]").getBytes(StandardCharsets.UTF_8)))
      );
    }

    @Test
    @DisplayName(
      "parse reads from a parser the caller owns, fresh or already on the object, and leaves it on the object's end"
    )
    void parsePositioning() {
      final var factory = new JsonFactory();
      try (final var fresh = factory.createParser(ObjectReadContext.empty(), "{\"sku\":\"A\"}")) {
        assertEquals(new Line("A", 0, null), LINES.parse(fresh));
        assertEquals(JsonToken.END_OBJECT, fresh.currentToken());
      }
      try (
        final var inner = factory.createParser(
          ObjectReadContext.empty(),
          "{\"wrap\":{\"sku\":\"B\"},\"after\":[{\"sku\":\"C\"}]}"
        )
      ) {
        inner.nextToken();
        inner.nextName();
        inner.nextToken();
        assertEquals(new Line("B", 0, null), LINES.parse(inner));
        assertEquals("after", inner.nextName());
        inner.nextToken();
        assertEquals(List.of(new Line("C", 0, null)), LINES.parseList(inner));
        assertEquals(JsonToken.END_ARRAY, inner.currentToken());
        assertEquals(JsonToken.END_OBJECT, inner.nextToken());
      }
      try (final var fresh = factory.createParser(ObjectReadContext.empty(), "[{\"sku\":\"D\"}]")) {
        assertEquals(List.of(new Line("D", 0, null)), LINES.parseList(fresh));
      }
    }

    @Test
    @DisplayName("explain reports each property's key and what a missing key does")
    void explain() {
      final var report = LINES.explain();
      assertEquals(
        List.of(
          new OpticNode.Extracted("sku", "sku", "String", OpticNode.WhenAbsent.DEFAULTS),
          new OpticNode.Extracted("quantity", "quantity", "int", OpticNode.WhenAbsent.DEFAULTS),
          new OpticNode.Extracted("price", "price", "BigDecimal", OpticNode.WhenAbsent.DEFAULTS)
        ),
        report.nodes()
      );
      assertEquals("JsonBinder[" + Line.class.getName() + "]", LINES.toString());
    }
  }
}
