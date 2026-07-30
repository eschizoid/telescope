package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;
import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code Telescope.fromMap(Class<T>, MapExtractStep...)} factory contract — adopters
 * consuming untyped sources (JDBC, framework body parsers, MQ payloads). Every test names the
 * real-world regression scenario it prevents.
 */
class TelescopeFromMapTest {

  record CaseListRequest(String bookingType, String caseId, int priority) {}

  static class CaseListRequestBean {

    private String bookingType;
    private String caseId;
    private int priority;

    public String getBookingType() {
      return bookingType;
    }

    public void setBookingType(final String bookingType) {
      this.bookingType = bookingType;
    }

    public String getCaseId() {
      return caseId;
    }

    public void setCaseId(final String caseId) {
      this.caseId = caseId;
    }

    public int getPriority() {
      return priority;
    }

    public void setPriority(final int priority) {
      this.priority = priority;
    }
  }

  @Nested
  @DisplayName("Forward — typed rebuild from a Map<String, Object>")
  class Forward {

    @Test
    @DisplayName("record target: each extract row reads its key, applies the converter, writes the component")
    void recordRoundTrips() {
      final var mapper = Telescope.fromMap(
        CaseListRequest.class,
        extract("bookingType", CaseListRequest::bookingType, Object::toString),
        extract("caseId", CaseListRequest::caseId, Object::toString),
        extract("priority", CaseListRequest::priority, v -> Integer.parseInt(v.toString()))
      );

      final var source = new HashMap<String, Object>();
      source.put("bookingType", "STANDARD");
      source.put("caseId", "case-99");
      source.put("priority", "3");

      final var result = mapper.forward(source);

      // Pins three contracts at once: (1) the map key flows from the extract row, NOT the
      // field name; (2) the converter transforms the raw Object value to the typed component
      // type; (3) the record's canonical constructor is invoked with the converted values in
      // component-declaration order. A regression in any of the three surfaces here.
      assertEquals(new CaseListRequest("STANDARD", "case-99", 3), result);
    }

    @Test
    @DisplayName("POJO target: bean getter accessors recover field names via Beans.propertyOf")
    void beanGetterAccessorsRecoverPropertyNames() {
      // Bean getter accessors yield `getBookingType` from SerializedLambda; fromMap must normalize
      // through Beans.propertyOf to `bookingType` so the value reaches the right property. Without
      // the normalization, every bean-target field would be filled with its JLS default.
      final var mapper = Telescope.fromMap(
        CaseListRequestBean.class,
        extract("type", CaseListRequestBean::getBookingType, Object::toString),
        extract("id", CaseListRequestBean::getCaseId, Object::toString),
        extract("prio", CaseListRequestBean::getPriority, v -> (int) v)
      );

      final var source = Map.<String, Object>of("type", "URGENT", "id", "case-7", "prio", 99);

      final var result = mapper.forward(source);

      assertEquals("URGENT", result.getBookingType());
      assertEquals("case-7", result.getCaseId());
      assertEquals(99, result.getPriority());
    }
  }

  @Nested
  @DisplayName("Lenient by default — missing keys and unmatched components")
  class Lenient {

    @Test
    @DisplayName("missing map key for a numeric primitive → JLS 0 (no NPE, no exception)")
    void missingPrimitiveTakesJlsDefault() {
      // The adopter scenario: JDBC ResultSet maps where some columns are null when the schema
      // declared NOT NULL via constraints. Without lenient-by-default, every nullable column
      // produces an NPE at construct time. With it, the value flows through as the primitive's
      // JLS default. Pins the contract that the factory does NOT throw on missing keys.
      final var mapper = Telescope.fromMap(
        CaseListRequest.class,
        extract("bookingType", CaseListRequest::bookingType, Object::toString),
        extract("caseId", CaseListRequest::caseId, Object::toString),
        extract("priority", CaseListRequest::priority, v -> v == null ? 0 : Integer.parseInt(v.toString()))
      );

      final var sourceMissingPriority = Map.<String, Object>of("bookingType", "X", "caseId", "y");

      final var result = mapper.forward(sourceMissingPriority);

      assertEquals(new CaseListRequest("X", "y", 0), result);
    }

    @Test
    @DisplayName("target component with NO extract row → NullDefaults value (empty String, 0 int, etc.)")
    void unspecifiedComponentTakesJlsDefault() {
      // Adopters often only care about a SUBSET of the target's fields — they'd otherwise have to
      // declare a no-op extract for every other component. Pin that omitted components flow through
      // NullDefaults: String → empty string (matches MapStruct's documented STRINGS → "" default
      // and the common nullable-VARCHAR convention); int → 0 (prevents unboxing NPE).
      final var mapper = Telescope.fromMap(
        CaseListRequest.class,
        extract("bookingType", CaseListRequest::bookingType, Object::toString)
        // caseId, priority intentionally omitted
      );

      final var source = Map.<String, Object>of("bookingType", "FAST_TRACK");

      final var result = mapper.forward(source);

      assertEquals("FAST_TRACK", result.bookingType());
      assertEquals("", result.caseId(), "unmatched String component → NullDefaults empty string");
      assertEquals(0, result.priority(), "unmatched int component → 0");
    }

    @Test
    @DisplayName("extra keys in the source map are silently ignored")
    void extraSourceKeysIgnored() {
      // The framework might enrich the map with metadata (traceId, timestamp, sessionId) the
      // target doesn't care about. Pin that those keys flow through without error and don't
      // corrupt other components. A regression that "validated" extra keys would break every
      // framework integration.
      final var mapper = Telescope.fromMap(
        CaseListRequest.class,
        extract("bookingType", CaseListRequest::bookingType, Object::toString),
        extract("caseId", CaseListRequest::caseId, Object::toString),
        extract("priority", CaseListRequest::priority, v -> Integer.parseInt(v.toString()))
      );

      final var sourceWithExtras = new LinkedHashMap<String, Object>();
      sourceWithExtras.put("bookingType", "STANDARD");
      sourceWithExtras.put("caseId", "case-5");
      sourceWithExtras.put("priority", "2");
      sourceWithExtras.put("traceId", "abc-123");
      sourceWithExtras.put("requestTimestamp", System.currentTimeMillis());

      final var result = mapper.forward(sourceWithExtras);

      assertEquals(new CaseListRequest("STANDARD", "case-5", 2), result);
    }
  }

  @Nested
  @DisplayName("Validation — bad row declarations surface precise errors at factory time")
  class Validation {

    @Test
    @DisplayName("an extract row targeting a non-component method is rejected at build time")
    void unknownTargetRowIsRejected() {
      // Pre-fix: such a row was silently ignored — key never read, converter never run.
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Telescope.fromMap(CaseListRequest.class, extract("k", CaseListRequest::toString, Object::toString))
      );
      assertTrue(ex.getMessage().contains("toString"), ex.getMessage());
      assertTrue(ex.getMessage().contains("Known fields"), ex.getMessage());
    }

    @Test
    @DisplayName("two extract rows naming the same target component throw at factory construction")
    void duplicateExtractRowIsRejected() {
      // Without this check, the second row would silently win and the first row's converter
      // becomes dead code — debugging is hard. Fail loudly at construction.
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Telescope.fromMap(
          CaseListRequest.class,
          extract("k1", CaseListRequest::bookingType, Object::toString),
          extract("k2", CaseListRequest::bookingType, Object::toString)
        )
      );
      assertTrue(ex.getMessage().contains("duplicate extract row"), ex::getMessage);
      assertTrue(ex.getMessage().contains("bookingType"), ex::getMessage);
    }

    @Test
    @DisplayName("forward(null) returns null — matches Mapper.forward / ForwardMapper.forward contracts")
    void nullSourceMapReturnsNull() {
      final var mapper = Telescope.fromMap(
        CaseListRequest.class,
        extract("bookingType", CaseListRequest::bookingType, Object::toString)
      );

      assertNull(mapper.forward(null));
    }
  }

  @Nested
  @DisplayName("ForwardMapper integration — sourceClass / targetClass / forward type")
  class ForwardMapperWiring {

    @Test
    @DisplayName("sourceClass is Map.class; targetClass is the supplied record/POJO")
    void classesPropagateToForwardMapper() {
      // The TelescopeMapperRegistry (Quarkus / Spring) keys mappers by (sourceClass, targetClass).
      // Pin that fromMap-built mappers register under Map.class so the registry can find them by
      // an adopter looking up the (Map, T) pair.
      final var mapper = Telescope.fromMap(
        CaseListRequest.class,
        extract("bookingType", CaseListRequest::bookingType, Object::toString)
      );

      assertSame(Map.class, mapper.sourceClass());
      assertSame(CaseListRequest.class, mapper.targetClass());
    }

    @Test
    @DisplayName("repeated forward calls produce equal records — no shared mutable state")
    void repeatedCallsAreIndependent() {
      final var mapper = Telescope.fromMap(
        CaseListRequest.class,
        extract("bookingType", CaseListRequest::bookingType, Object::toString),
        extract("caseId", CaseListRequest::caseId, Object::toString),
        extract("priority", CaseListRequest::priority, v -> Integer.parseInt(v.toString()))
      );

      final var first = mapper.forward(Map.of("bookingType", "A", "caseId", "1", "priority", "1"));
      final var second = mapper.forward(Map.of("bookingType", "B", "caseId", "2", "priority", "2"));

      assertNotNull(first);
      assertNotNull(second);
      assertEquals(new CaseListRequest("A", "1", 1), first);
      assertEquals(new CaseListRequest("B", "2", 2), second);
    }
  }
}
