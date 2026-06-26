package io.github.eschizoid.telescope.nestedextract;

import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A nested {@code Telescope.fromMap(...)} composes as the converter of an {@code extract(...)} row
 * via the {@code extract(key, accessor, ForwardMapper)} overload — so a nested {@code Map<String,
 * Object>} fills a nested POJO declaratively, without dropping into a manual static method + cast.
 */
class FromMapNestedExtractTest {

  @Test
  @DisplayName("a nested fromMap converter fills a nested POJO from a nested map, composing declaratively")
  void nestedFromMapComposes() {
    final var mapper = Telescope.fromMap(
      CaseListRequest.class,
      extract("caseId", CaseListRequest::caseId, Object::toString),
      extract(
        "pageDetails",
        CaseListRequest::pageDetails,
        Telescope.fromMap(
          PageDetails.class,
          extract("pageSize", PageDetails::pageSize, v -> (Integer) v),
          extract("exclusiveStartKey", PageDetails::exclusiveStartKey, Object::toString)
        )
      )
    );

    final var input = new HashMap<String, Object>();
    input.put("caseId", "c-1");
    input.put("pageDetails", Map.of("pageSize", 50, "exclusiveStartKey", "k-9"));

    final var result = mapper.forward(input);

    assertEquals("c-1", result.caseId());
    assertEquals(50, result.pageDetails().pageSize());
    assertEquals("k-9", result.pageDetails().exclusiveStartKey());
  }

  @Test
  @DisplayName("an absent nested key yields a null nested component, not a throw")
  void absentNestedKeyIsNull() {
    final var mapper = Telescope.fromMap(
      CaseListRequest.class,
      extract("caseId", CaseListRequest::caseId, Object::toString),
      extract(
        "pageDetails",
        CaseListRequest::pageDetails,
        Telescope.fromMap(PageDetails.class, extract("pageSize", PageDetails::pageSize, v -> (Integer) v))
      )
    );

    final var result = mapper.forward(Map.of("caseId", "c-2"));

    assertEquals("c-2", result.caseId());
    assertNull(result.pageDetails(), "absent nested map key → null nested component");
  }

  @Test
  @DisplayName("a key present but holding a non-Map value fails with an error naming the key, not a bare CCE")
  void presentNonMapKeyNamesTheKey() {
    final var mapper = Telescope.fromMap(
      CaseListRequest.class,
      extract(
        "pageDetails",
        CaseListRequest::pageDetails,
        Telescope.fromMap(PageDetails.class, extract("pageSize", PageDetails::pageSize, v -> (Integer) v))
      )
    );

    final var source = new HashMap<String, Object>();
    source.put("pageDetails", "not-a-map"); // wrong shape for a nested fromMap row
    final var ex = assertThrows(IllegalArgumentException.class, () -> mapper.forward(source));
    assertTrue(ex.getMessage().contains("pageDetails"), "error must name the offending key");
  }
}
