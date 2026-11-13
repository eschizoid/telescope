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
      PairingMessages.jsonNotAValue("lines[1].quantity", "lines[1].quantity", "1.5", "an int", 3, 18)
    );
    assertEquals(
      "'partner.id' (JSON 'partner.partner_id') got 12, not a String, at line 1, column 30",
      PairingMessages.jsonNotAValue("partner.id", "partner.partner_id", "12", "a String", 1, 30)
    );
    assertEquals(
      "the document got an array, not an Order, at line 1, column 1",
      PairingMessages.jsonNotAValue("", "", "an array", "an Order", 1, 1)
    );
  }

  @Test
  @DisplayName("a missing required key names the key, the property it fills, the object and its position")
  void missingRequired() {
    assertEquals(
      "TelescopeJson.fromJson: the object at 'lines[0]' carries no value for required key \"sku\" (component" +
        " 'sku') of Line, at line 1, column 24",
      PairingMessages.jsonMissingRequired("lines[0]", "lines[0]", List.of("\"sku\" (component 'sku')"), "Line", 1, 24)
    );
    assertEquals(
      "TelescopeJson.fromJson: the object at 'partner' (JSON 'partner_info') carries no value for required key" +
        " \"partner_id\" (component 'id') of Partner, at line 1, column 17",
      PairingMessages.jsonMissingRequired(
        "partner",
        "partner_info",
        List.of("\"partner_id\" (component 'id')"),
        "Partner",
        1,
        17
      )
    );
    assertEquals(
      "TelescopeJson.fromJson: the document carries no value for required keys \"a\" (component 'a'), \"b\"" +
        " (component 'b') of Pair, at line 1, column 2",
      PairingMessages.jsonMissingRequired(
        "",
        "",
        List.of("\"a\" (component 'a')", "\"b\" (component 'b')"),
        "Pair",
        1,
        2
      )
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
        " once; give one of them another key with @JsonProperty or a row, or drop the @JsonAlias that names it",
      PairingMessages.jsonKeyReadTwice("left", "left", "right", "Pair")
    );
    assertEquals(
      "TelescopeJson.fromJson: property 'name' of Customer is named \"f\" and \"s\" by @JsonProperty on the" +
        " members it is written through, and a JSON value is read from one key; give them one name",
      PairingMessages.jsonKeyNamedTwice("property", "name", "Customer", List.of("f", "s"))
    );
    assertEquals(
      "TelescopeJson.fromJson: Partner carries @JsonNaming on the class, which JsonBinder does not read; it" +
        " reads @JsonProperty, @JsonAlias and @JsonIgnore only. Read Partner with Jackson databind, through a" +
        " TelescopeModule where a mapper converts it, or remove the annotation",
      PairingMessages.jsonAnnotationUnsupported("JsonNaming", "the class", "Partner")
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
