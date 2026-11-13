package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The text of the refusals telescope-jackson's JSON binder gives, which it raises in these words
 * for a value, a missing key and a type it cannot read.
 */
class JsonMessagesTest {

  @Test
  @DisplayName("a refused value names its path, the value as the document writes it, the type and its position")
  void notAValue() {
    assertEquals(
      "'lines[1].quantity' got 1.5, not an int, at line 3, column 18",
      PairingMessages.jsonNotAValue("lines[1].quantity", "1.5", "an int", 3, 18)
    );
    assertEquals(
      "the document got an array, not an Order, at line 1, column 1",
      PairingMessages.jsonNotAValue("", "an array", "an Order", 1, 1)
    );
  }

  @Test
  @DisplayName("a missing required key names the key, the property it fills, the object and its position")
  void missingRequired() {
    assertEquals(
      "TelescopeJson.fromJson: the object at 'lines[0]' carries no value for required key \"sku\" (component" +
        " 'sku') of Line, at line 1, column 24",
      PairingMessages.jsonMissingRequired("lines[0]", List.of("\"sku\" (component 'sku')"), "Line", 1, 24)
    );
    assertEquals(
      "TelescopeJson.fromJson: the document carries no value for required keys \"a\" (component 'a'), \"b\"" +
        " (component 'b') of Pair, at line 1, column 2",
      PairingMessages.jsonMissingRequired("", List.of("\"a\" (component 'a')", "\"b\" (component 'b')"), "Pair", 1, 2)
    );
  }

  @Test
  @DisplayName("each build-time refusal names the property, the type and the way out")
  void buildTime() {
    assertEquals(
      "TelescopeJson.fromJson: component 'value' of Holder is declared T and no row names it: reason",
      PairingMessages.jsonUnbindable("component", "value", "Holder", "T", "reason")
    );
    assertEquals(
      "TelescopeJson.fromJson: a.Shape is neither a record nor a class with properties to bind, so no JSON object" +
        " can build it",
      PairingMessages.jsonNotBindable("a.Shape")
    );
    assertEquals(
      "TelescopeJson.fromJson: a row names 'x', which is not a component or property of Line. Known fields: [sku].",
      PairingMessages.jsonRowOffTarget("x", "Line", List.of("sku"))
    );
    assertEquals(
      "TelescopeJson.fromJson: two rows name 'sku' of Line; keep one",
      PairingMessages.jsonDuplicateRow("sku", "Line")
    );
    assertEquals(
      "TelescopeJson.fromJson: a row names 'zip' of Address, which Line never holds, so the row would never be read",
      PairingMessages.jsonRowNeverReached("zip", "Address", "Line")
    );
    assertEquals(
      "TelescopeJson.fromJson: 'left' and 'right' of Pair both read the key \"left\", and a JSON value is read" +
        " once; give one of them a row reading another key",
      PairingMessages.jsonKeyReadTwice("left", "left", "right", "Pair")
    );
  }

  @Test
  @DisplayName("a name takes the article its first sound takes, an initialism by its first letter's name")
  void withArticle() {
    for (final var expected : List.of(
      "an Order",
      "an int",
      "an Instant",
      "a List",
      "a UUID",
      "a URI",
      "a URL",
      "an SKU",
      "an HTTPClient",
      "an XMLNode",
      "a BigDecimal",
      "a Unit",
      "a User",
      "an Umbrella",
      "a Euro",
      "a OneOf"
    )) {
      assertEquals(expected, PairingMessages.withArticle(expected.substring(expected.indexOf(' ') + 1)));
    }
  }
}
