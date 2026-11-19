package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.internal.pairing.JsonPropertyKeys.Keys;
import io.github.eschizoid.telescope.internal.pairing.JsonPropertyKeys.Member;
import io.github.eschizoid.telescope.internal.pairing.JsonPropertyKeys.Property;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a property's Jackson annotations decide the key it reads, the decision the runtime binder and
 * the {@code @FromJson} processor both take from the facts they read off their own models.
 */
class JsonPropertyKeysTest {

  private static Member parameter(final Property property) {
    return new Member(true, false, true, false, property, null);
  }

  private static Member field(final boolean ignored, final Property property, final List<String> aliases) {
    return new Member(true, false, false, ignored, property, aliases);
  }

  private static Member setter(final boolean ignored, final Property property) {
    return new Member(true, true, false, ignored, property, null);
  }

  private static Member getter(final boolean ignored, final Property property, final List<String> aliases) {
    return new Member(false, false, false, ignored, property, aliases);
  }

  private static Property named(final String value) {
    return new Property(value, false, false);
  }

  @Test
  @DisplayName("a property with no annotation reads its own name")
  void plain() {
    assertEquals(
      new Keys("id", List.of(), false, false),
      JsonPropertyKeys.decide("Row", "component", "id", List.of(parameter(null), field(false, null, null)), true)
    );
  }

  @Test
  @DisplayName("a key given where the property is written wins over one given where it is read")
  void writeNameWins() {
    final var keys = JsonPropertyKeys.decide(
      "Bean",
      "property",
      "label",
      List.of(setter(false, named("read_as")), getter(false, named("written_as"), null)),
      false
    );
    assertEquals("read_as", keys.key());
    assertEquals(
      "display",
      JsonPropertyKeys.decide(
        "Bean",
        "property",
        "label",
        List.of(setter(false, null), getter(false, named("display"), null)),
        false
      ).key()
    );
    assertEquals(
      "label",
      JsonPropertyKeys.decide("Bean", "property", "label", List.of(setter(false, named(""))), false).key()
    );
  }

  @Test
  @DisplayName("an ignored member drops the property unless another member is explicit and a setter is kept")
  void ignoral() {
    assertEquals(
      true,
      JsonPropertyKeys.decide(
        "Bean",
        "property",
        "a",
        List.of(setter(false, null), getter(true, null, null)),
        false
      ).ignored()
    );
    assertEquals(
      false,
      JsonPropertyKeys.decide(
        "Bean",
        "property",
        "a",
        List.of(setter(false, named("")), getter(true, null, null)),
        false
      ).ignored()
    );
    assertEquals(
      true,
      JsonPropertyKeys.decide(
        "Bean",
        "property",
        "a",
        List.of(setter(true, null), getter(false, named(""), null)),
        false
      ).ignored()
    );
    assertEquals(
      true,
      JsonPropertyKeys.decide(
        "Row",
        "component",
        "a",
        List.of(parameter(named("x")), field(true, null, null)),
        true
      ).ignored()
    );
    assertEquals(
      true,
      JsonPropertyKeys.decide(
        "Row",
        "component",
        "a",
        List.of(parameter(new Property("", true, false))),
        true
      ).ignored()
    );
  }

  @Test
  @DisplayName("a requirement holds only where a creator builds the type, and the first alias list counts")
  void requiredAndAliases() {
    final var required = new Property("id", false, true);
    assertEquals(true, JsonPropertyKeys.decide("Row", "component", "a", List.of(parameter(required)), true).required());
    assertEquals(
      false,
      JsonPropertyKeys.decide("Bean", "property", "a", List.of(setter(false, required)), false).required()
    );
    assertEquals(
      List.of("x", "y"),
      JsonPropertyKeys.decide(
        "Row",
        "component",
        "a",
        List.of(field(false, null, List.of("x", "y")), getter(false, null, List.of("z"))),
        true
      ).aliases()
    );
  }

  @Test
  @DisplayName("two keys given where a property is written are refused")
  void twoKeys() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      JsonPropertyKeys.decide(
        "Bean",
        "property",
        "a",
        List.of(setter(false, named("y")), field(false, named("x"), null)),
        false
      )
    );
    assertEquals(PairingMessages.jsonKeyNamedTwice("property", "a", "Bean", List.of("y", "x")), refused.getMessage());
  }

  @Test
  @DisplayName("a JSON binder names why a type or a map key has no reading")
  void noReading() {
    final var row = "name it with an extract(key, accessor, converter) row";
    assertEquals(null, PairingMessages.jsonNoReading(MapValueTypes.Kind.SCALAR, null, "int"));
    assertEquals(
      "java.util.ArrayList is a collection subtype; declare it as List/Set/Map/Optional, or " + row,
      PairingMessages.jsonNoReading(MapValueTypes.Kind.COLLECTION_SUBTYPE, "java.util.ArrayList", "ArrayList<String>")
    );
    assertEquals(
      "java.lang.Thread can't be read from a JSON value; " + row,
      PairingMessages.jsonNoReading(MapValueTypes.Kind.UNKNOWN_JDK, "java.lang.Thread", "java.lang.Thread")
    );
    assertEquals(
      "demo.Shape is neither a record nor a class with properties to bind; " + row,
      PairingMessages.jsonNoReading(MapValueTypes.Kind.NO_BINDER, "demo.Shape", "demo.Shape")
    );
    assertEquals(
      "T can't be read from a JSON value (type variable / array / unsupported kind); " + row,
      PairingMessages.jsonNoReading(MapValueTypes.Kind.UNSUPPORTED, null, "T")
    );
    assertEquals(null, PairingMessages.jsonNoKeyReading(MapValueTypes.Kind.ENUM, "demo.Status"));
    assertEquals(
      "a JSON object's keys are text, and demo.Line is not read from text; declare the key as a String, an enum or" +
        " a JDK value type, or " +
        row,
      PairingMessages.jsonNoKeyReading(MapValueTypes.Kind.NESTED, "demo.Line")
    );
  }
}
