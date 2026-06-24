package io.github.eschizoid.telescope.frommap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
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
