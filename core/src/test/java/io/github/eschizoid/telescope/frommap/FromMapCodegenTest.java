package io.github.eschizoid.telescope.frommap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Compiles AND runs the {@code @FromMap}-generated converters (the {@code FromMapProcessorTest}
 * harness only asserts on generated text). The {@code <X>FromMap} classes referenced here are
 * generated at test-compile time by {@code telescope-codegen}; if generation produced non-compiling
 * code, this test would not compile. Proves every coercion + both rebuild strategies end-to-end.
 */
class FromMapCodegenTest {

  @Test
  @DisplayName("record: String, primitive-from-String, enum, nested, List<nested>, Set<String>, Map<String,nested>")
  void recordAllCoercions() {
    final var input = Map.of(
      "name",
      "Platform",
      "size",
      "5", // String → int
      "role",
      "ADMIN", // String → enum
      "hq",
      Map.of("city", "New York", "zip", "10001"), // nested Map → FmAddress
      "sites",
      List.of(Map.of("city", "Austin", "zip", "73301")), // List<Map> → List<FmAddress>
      "tags",
      Set.of("java", "native"), // Set<String>
      "byCity",
      Map.of("sf", Map.of("city", "San Francisco", "zip", "94107")) // Map<String, Map> → Map<String, FmAddress>
    );

    final var team = FmTeamFromMap.fromMap(input);

    assertEquals("Platform", team.name());
    assertEquals(5, team.size());
    assertEquals(FmRole.ADMIN, team.role());
    assertEquals(new FmAddress("New York", "10001"), team.hq());
    assertEquals(List.of(new FmAddress("Austin", "73301")), team.sites());
    assertEquals(Set.of("java", "native"), team.tags());
    assertEquals(new FmAddress("San Francisco", "94107"), team.byCity().get("sf"));
  }

  @Test
  @DisplayName("record: an absent primitive key takes the JLS default; an absent reference key is null")
  void recordAbsentKeys() {
    final var team = FmTeamFromMap.fromMap(Map.of("name", "Bare"));
    assertEquals("Bare", team.name());
    assertEquals(0, team.size()); // absent int → 0
    assertNull(team.role()); // absent reference → null
    assertTrue(team.sites().isEmpty()); // absent List → empty
  }

  @Test
  @DisplayName("every primitive parses from a String; boxed wrappers parse too; absent keys default")
  void scalarsParseFromStrings() {
    final var full = FmScalarsFromMap.fromMap(
      Map.of(
        "active",
        "true",
        "ratio",
        "1.5",
        "small",
        "7",
        "tiny",
        "3",
        "letter",
        "X",
        "boxedInt",
        "42",
        "boxedBool",
        "false"
      )
    );
    assertTrue(full.active());
    assertEquals(1.5f, full.ratio());
    assertEquals((short) 7, full.small());
    assertEquals((byte) 3, full.tiny());
    assertEquals('X', full.letter());
    assertEquals(Integer.valueOf(42), full.boxedInt());
    assertEquals(Boolean.FALSE, full.boxedBool());

    // Absent keys: primitives take JLS defaults (no NPE on unbox), boxed wrappers stay null.
    final var bare = FmScalarsFromMap.fromMap(Map.of());
    assertFalse(bare.active());
    assertEquals(0.0f, bare.ratio());
    assertEquals('\0', bare.letter());
    assertNull(bare.boxedInt());
    assertNull(bare.boxedBool());
  }

  @Test
  @DisplayName("Optional field wraps the coerced element; Map coerces non-String keys")
  void optionalAndMapKeyCoercions() {
    final var present = FmExtrasFromMap.fromMap(
      Map.of("maybeHq", Map.of("city", "NYC", "zip", "10001"), "byCode", Map.of(1, "a"), "notes", Map.of("k", "v"))
    );
    assertEquals(Optional.of(new FmAddress("NYC", "10001")), present.maybeHq());
    assertEquals("a", present.byCode().get(1)); // Integer key coerced through the key coercion
    assertEquals("v", present.notes().get("k"));

    final var absent = FmExtrasFromMap.fromMap(Map.of());
    assertEquals(Optional.empty(), absent.maybeHq()); // absent Optional → empty, not null
  }

  @Test
  @DisplayName("Map with a null value doesn't throw (lenient accumulation, not Collectors.toMap)")
  void mapToleratesNullValue() {
    final var notes = new java.util.HashMap<String, Object>();
    notes.put("k", null);
    final var source = new java.util.HashMap<String, Object>();
    source.put("notes", notes);
    final var result = FmExtrasFromMap.fromMap(source); // would NPE under Collectors.toMap
    assertNull(result.notes().get("k"));
  }

  @Test
  @DisplayName("bean: rebuilds via no-arg constructor + setters")
  void beanViaSetters() {
    final var bean = FmBeanFromMap.fromMap(Map.of("label", "widget", "count", 7));
    assertEquals("widget", bean.getLabel());
    assertEquals(7, bean.getCount());
  }

  @Test
  @DisplayName("a null source map converts to null, not a throw")
  void nullSourceMap() {
    assertNull(FmTeamFromMap.fromMap(null));
    assertNull(FmBeanFromMap.fromMap(null));
  }
}
