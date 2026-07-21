package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the one property-name derivation rule. These pin exactly the cases the five
 * pre-consolidation copies disagreed on — a name like {@code getaway} must NOT be a getter (the
 * JavaBeans uppercase-after-prefix requirement), and a leading acronym must survive
 * decapitalization.
 */
class PropertyNamesTest {

  @Test
  @DisplayName("getCity strips and decapitalizes to city")
  void getStripsAndDecapitalizes() {
    assertEquals("city", PropertyNames.afterGet("getCity"));
    assertEquals("city", PropertyNames.property("getCity"));
  }

  @Test
  @DisplayName("isActive strips and decapitalizes to active")
  void isStripsAndDecapitalizes() {
    assertEquals("active", PropertyNames.afterIs("isActive"));
    assertEquals("active", PropertyNames.property("isActive"));
  }

  @Test
  @DisplayName("a leading acronym survives: getURL derives URL, not uRL")
  void acronymSurvivesDecapitalization() {
    assertEquals("URL", PropertyNames.afterGet("getURL"));
    assertEquals("URL", PropertyNames.decapitalize("URL"));
  }

  @Test
  @DisplayName("getaway is not a getter — lowercase after the prefix fails the JavaBeans rule")
  void lowercaseAfterGetPrefixIsNotAGetter() {
    assertNull(PropertyNames.afterGet("getaway"));
    assertEquals("getaway", PropertyNames.property("getaway"));
  }

  @Test
  @DisplayName("isbn is not a boolean getter — lowercase after the prefix fails the rule")
  void lowercaseAfterIsPrefixIsNotAGetter() {
    assertNull(PropertyNames.afterIs("isbn"));
    assertEquals("isbn", PropertyNames.property("isbn"));
  }

  @Test
  @DisplayName("bare prefixes and short names pass through unchanged")
  void barePrefixesPassThrough() {
    assertNull(PropertyNames.afterGet("get"));
    assertNull(PropertyNames.afterIs("is"));
    assertEquals("get", PropertyNames.property("get"));
    assertEquals("is", PropertyNames.property("is"));
    assertEquals("x", PropertyNames.property("x"));
  }

  @Test
  @DisplayName("record component accessors pass through unchanged")
  void recordAccessorsPassThrough() {
    assertEquals("email", PropertyNames.property("email"));
    assertEquals("createdAt", PropertyNames.property("createdAt"));
  }

  @Test
  @DisplayName("null passes through — nested-telescope row shapes return null field names by design")
  void nullPassesThrough() {
    assertNull(PropertyNames.property(null));
    assertNull(PropertyNames.afterGet(null));
    assertNull(PropertyNames.afterIs(null));
    assertNull(PropertyNames.decapitalize(null));
  }

  @Test
  @DisplayName("single-char and empty decapitalize edge cases")
  void decapitalizeEdges() {
    assertEquals("", PropertyNames.decapitalize(""));
    assertEquals("x", PropertyNames.decapitalize("X"));
    assertEquals("ab", PropertyNames.decapitalize("Ab"));
  }
}
