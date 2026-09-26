package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;
import static org.junit.jupiter.api.Assertions.*;

import io.github.eschizoid.telescope.mapping.MapExtractStep;
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
    @DisplayName("record target: each extract row reads its key, applies the converter, writes the" + " component")
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
    @DisplayName("missing map key for a numeric primitive → 0, without the row's converter being called")
    void missingPrimitiveTakesItsTypeDefault() {
      // The adopter scenario: JDBC ResultSet maps where some columns are null when the schema
      // declared NOT NULL via constraints. The component takes its type default and the row's
      // converter is not called, so the null-tolerant branch written below is never reached and
      // the converter could be Integer::parseInt alone. Pins that the factory does not throw on a
      // missing key.
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

  public record HasChar(char grade, String name) {}

  public static class BeanHasChar {

    private char grade;
    private String name;

    public char getGrade() {
      return grade;
    }

    public void setGrade(final char grade) {
      this.grade = grade;
    }

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }
  }

  @Test
  @DisplayName("a char component no row fills is constructed at its JLS default rather than failing to" + " construct")
  void charComponentTakesItsPrimitiveDefault() {
    // The substitution table leaves Character out on purpose, so a null character source value
    // stays null on the mapping path. A record component still cannot hold null, and the canonical
    // constructor is what rejects it, naming the record rather than the component.
    //
    // Only the record path reaches that constructor. A bean is filled through setters, and the
    // setter invoker skips a null into a primitive, so a bean's char keeps its default whatever
    // this table returns — which is why there is no bean half to this assertion.
    final var record = Telescope.fromMap(HasChar.class, extract("name", HasChar::name, Object::toString));
    assertEquals('\u0000', record.forward(Map.of("name", "Ada")).grade());
  }

  @Test
  @DisplayName("a bean property a row names takes its default when the key is absent, as a record component" + " does")
  void beanNamedPropertyWithAnAbsentKeyTakesItsDefault() {
    // The two rebuild paths fill their slots through different machinery, so the rule holds for
    // both only if each one is asked.
    final var mapper = Telescope.fromMap(
      BeanHasChar.class,
      extract("name", BeanHasChar::getName, Object::toString),
      extract("grade", BeanHasChar::getGrade, v -> v.toString().charAt(0))
    );

    final var filled = mapper.forward(Map.of());
    assertEquals("", filled.getName());
    assertEquals('\u0000', filled.getGrade());
  }

  public record Named(String bookingType, int priority) {}

  @Test
  @DisplayName("a named component whose key is absent takes its default rather than converting nothing")
  void namedComponentWithAnAbsentKeyTakesItsDefault() {
    // A converter says how to read a value, not what to do in place of one. Handing it null makes
    // the common converters throw, and the generated binder for the same annotation defaults here,
    // so converting would also make the two paths disagree about the same map.
    final var mapper = Telescope.fromMap(
      Named.class,
      extract("bookingType", Named::bookingType, Object::toString),
      extract("priority", Named::priority, v -> Integer.parseInt(v.toString()))
    );

    assertEquals(new Named("", 0), mapper.forward(Map.of()));
    assertEquals(new Named("AIR", 3), mapper.forward(Map.of("bookingType", "AIR", "priority", "3")));
  }

  @Test
  @DisplayName("a key present with a null value is the same as an absent one, as it is to the generated" + " binder")
  void anExplicitNullIsTreatedAsAbsent() {
    // The generated binder reads map.get(key) and cannot tell the two apart, so neither does this.
    final var mapper = Telescope.fromMap(Named.class, extract("bookingType", Named::bookingType, Object::toString));
    final var withNull = new java.util.HashMap<String, Object>();
    withNull.put("bookingType", null);

    assertEquals("", mapper.forward(withNull).bookingType());
  }

  public record Ticket(String id, String note) {}

  @Test
  @DisplayName("a required row refuses an absent key, naming both the key and the component it fills")
  void aRequiredRowRefusesAnAbsentKey() {
    // A default is indistinguishable from a supplied value once it is in the record, so a boundary
    // that must have a value needs to say so where the row is written rather than in a converter.
    final var mapper = Telescope.fromMap(
      Ticket.class,
      MapExtractStep.required("customer_id", Ticket::id, Object::toString),
      extract("note", Ticket::note, Object::toString)
    );

    final var thrown = assertThrows(IllegalStateException.class, () -> mapper.forward(Map.of("note", "n")));

    assertTrue(thrown.getMessage().contains("customer_id"), () -> thrown.getMessage());
    assertTrue(thrown.getMessage().contains("id"), () -> thrown.getMessage());
  }

  @Test
  @DisplayName("a required row converts like any other when its key carries a value")
  void aRequiredRowConvertsWhenPresent() {
    final var mapper = Telescope.fromMap(
      Ticket.class,
      MapExtractStep.required("customer_id", Ticket::id, Object::toString),
      extract("note", Ticket::note, Object::toString)
    );

    assertEquals(new Ticket("c-1", "n"), mapper.forward(Map.of("customer_id", "c-1", "note", "n")));
  }

  @Test
  @DisplayName("a key present holding null is as absent to a required row as it is to an extract row")
  void aRequiredRowRefusesAnExplicitNull() {
    // The generated binder reads map.get(key) and cannot tell the two apart, and neither does the
    // defaulting path, so a required row that accepted one would disagree with both.
    final var mapper = Telescope.fromMap(
      Ticket.class,
      MapExtractStep.required("customer_id", Ticket::id, Object::toString)
    );
    final var withNull = new HashMap<String, Object>();
    withNull.put("customer_id", null);

    assertThrows(IllegalStateException.class, () -> mapper.forward(withNull));
  }

  @Test
  @DisplayName("an extract row on the same record still takes its default, so required is the opt-in")
  void extractRemainsLenientAlongsideRequired() {
    final var mapper = Telescope.fromMap(
      Ticket.class,
      MapExtractStep.required("customer_id", Ticket::id, Object::toString),
      extract("note", Ticket::note, Object::toString)
    );

    assertEquals(new Ticket("c-1", ""), mapper.forward(Map.of("customer_id", "c-1")));
  }

  public record Concrete(java.util.ArrayList<String> tags, String name) {}

  @Test
  @DisplayName("a component declared as a concrete container is not filled with a value it cannot hold")
  void aConcreteContainerSlotIsNotFilledWithTheSingleton() {
    // The table answers for anything a List is assignable from, and the empty singleton it returns
    // is not an ArrayList. Handing it over reaches the constructor, which rejects it while naming
    // the record rather than the component.
    final var named = Telescope.fromMap(
      Concrete.class,
      extract("tags", Concrete::tags, v -> new java.util.ArrayList<>(java.util.List.of(v.toString()))),
      extract("name", Concrete::name, Object::toString)
    );
    assertNull(named.forward(Map.of("name", "a")).tags());

    final var unnamed = Telescope.fromMap(Concrete.class, extract("name", Concrete::name, Object::toString));
    assertNull(unnamed.forward(Map.of("name", "a")).tags());
  }

  @Test
  @DisplayName("a required row on a bean target refuses an absent key, as it does on a record")
  void aRequiredRowOnABeanRefusesAnAbsentKey() {
    // Beans and records are filled through different machinery, so a rule holds for both only if
    // each one is asked.
    final var mapper = Telescope.fromMap(
      BeanHasChar.class,
      MapExtractStep.required("display_name", BeanHasChar::getName, Object::toString)
    );

    final var thrown = assertThrows(IllegalStateException.class, () -> mapper.forward(Map.of()));

    assertTrue(thrown.getMessage().contains("display_name"), () -> thrown.getMessage());
    assertTrue(thrown.getMessage().contains("name"), () -> thrown.getMessage());
  }
}
