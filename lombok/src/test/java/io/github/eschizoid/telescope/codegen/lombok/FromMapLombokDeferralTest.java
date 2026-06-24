package io.github.eschizoid.telescope.codegen.lombok;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies @FromMap on a Lombok @Data bean. The generated FromMapDataUserFromMap is emitted in the
 * final processing round (after Lombok synthesizes accessors), so it's loaded reflectively here and
 * run end-to-end: a String "30" must coerce to int 30 through the generated setter rebuild.
 */
class FromMapLombokDeferralTest {

  @Test
  @DisplayName("@FromMap on a Lombok @Data bean generates a working converter (round-deferred)")
  void lombokDataBeanGetsFromMapConverter() throws Exception {
    final var converter = Class.forName("io.github.eschizoid.telescope.codegen.lombok.fixtures.FromMapDataUserFromMap");
    final var fromMap = converter.getMethod("fromMap", Map.class);

    final var user = fromMap.invoke(null, Map.of("name", "Alice", "age", "30"));
    assertNotNull(user, "FromMapDataUserFromMap.fromMap returned null");
    assertEquals("Alice", user.getClass().getMethod("getName").invoke(user));
    assertEquals(30, user.getClass().getMethod("getAge").invoke(user));
  }
}
