package io.github.eschizoid.telescope.example.mapstruct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.example.mapstruct.domain.Customer;
import io.github.eschizoid.telescope.example.mapstruct.domain.LineItem;
import io.github.eschizoid.telescope.example.mapstruct.domain.Order;
import io.github.eschizoid.telescope.example.mapstruct.mapstruct.OrderMapStructMapper;
import io.github.eschizoid.telescope.example.mapstruct.mapstruct.SilentDropMapper;
import io.github.eschizoid.telescope.example.mapstruct.telescope.TelescopeMappings;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reproducible proof behind the slice README's head-to-head. Committed state is green: both
 * frameworks map identically. The footgun test pins MapStruct's default-policy silent drop of an
 * unmapped target permanently, so CI demonstrates it without anyone applying a rename by hand. The
 * rename divergence itself (telescope's method reference auto-refactors; MapStruct's stale
 * {@code @Mapping} string fails to compile) is a documented manual step in the README — a compile
 * failure can't also be a passing test.
 */
class MapStructVsTelescopeTest {

  private static final Logger LOG = System.getLogger(MapStructVsTelescopeTest.class.getName());

  private static Order sampleOrder() {
    return new Order(
      "o-1",
      new Customer("Ada", "ada@example.com"),
      List.of(new LineItem("sku-1", 2, new BigDecimal("10.00")), new LineItem("sku-2", 1, new BigDecimal("5.00")))
    );
  }

  @Test
  @DisplayName("Act 1 baseline: MapStruct and telescope produce the identical OrderDto, rename and all")
  void bothFrameworksMapToTheSameDto() {
    final var order = sampleOrder();

    final var viaMapStruct = OrderMapStructMapper.INSTANCE.toDto(order);
    final var viaTelescope = TelescopeMappings.ORDER_MAPPER.forward(order);

    assertEquals(viaMapStruct, viaTelescope, "both frameworks map the same Order to the same OrderDto");
    assertEquals(
      "ada@example.com",
      viaTelescope.getCustomer().getContactEmail(),
      "the email -> contactEmail rename landed"
    );
    assertEquals(2, viaTelescope.getLines().size(), "the line-item collection recursed");
    log(
      "Act 1 — both frameworks produce the identical OrderDto (no strawman):",
      "MapStruct: " + viaMapStruct + "\ntelescope: " + viaTelescope
    );
  }

  @Test
  @DisplayName("telescope mapping is bidirectional for free — backward(forward(order)) == order")
  void telescopeMapperRoundTrips() {
    final var order = sampleOrder();
    final var roundTripped = TelescopeMappings.ORDER_MAPPER.backward(TelescopeMappings.ORDER_MAPPER.forward(order));
    assertEquals(order, roundTripped, "one mapper(...) value gives both directions; MapStruct needs a second method");
    log("Bidirectional for free — backward(forward(order)) == order:", roundTripped);
  }

  @Test
  @DisplayName("Unmapped-target footgun: MapStruct's default policy leaves a target with no source silently null")
  void mapStructSilentlyDropsUnmappedTarget() {
    final var dto = SilentDropMapper.INSTANCE.toContactDto(new Customer("Ada", "ada@example.com"));

    assertEquals("ada@example.com", dto.getContactEmail(), "the mapped field is fine");
    assertNull(
      dto.getRegion(),
      "default unmappedTargetPolicy=WARN compiles and nulls an unmapped target (a source rename is the separate case — a compile error)"
    );
    log(
      "Unmapped-target footgun — MapStruct's default policy nulls a target with no source:",
      "contactEmail = " + dto.getContactEmail() + "  |  region = " + dto.getRegion() + "  (silently null)"
    );
  }

  @Test
  @DisplayName("Act 2: deep immutable update rebuilds the whole graph; the original is untouched")
  void deepUpdateRebuildsImmutably() {
    final var order = sampleOrder();

    final var taxed = TelescopeMappings.applyRate(order, new BigDecimal("2"));

    assertEquals(new BigDecimal("20.00"), taxed.lines().get(0).price(), "every line price doubled");
    assertEquals(new BigDecimal("10.00"), taxed.lines().get(1).price(), "every line price doubled");
    assertNotSame(order, taxed, "a new Order graph is returned");
    assertEquals(
      new BigDecimal("10.00"),
      order.lines().get(0).price(),
      "the original Order is unchanged — immutable update"
    );
    log(
      "Act 2 — deep immutable update rebuilds the graph, original untouched:",
      "before: " + order.lines() + "\nafter:  " + taxed.lines()
    );
  }

  @Test
  @DisplayName("Act 3: the telescope mapper explains and traces itself; MapStruct is a black box")
  void introspectionExposesWhatTheMapperDoes() {
    final var order = sampleOrder();

    // Static structure — no input needed. The Act 1 rename is a first-class row here, not a string
    // buried in generated OrderMapStructMapperImpl.java.
    final var report = TelescopeMappings.CUSTOMER_MAPPER.explain();
    assertFalse(report.isEmpty(), "the mapper describes its own structure");
    assertTrue(
      report
        .mapped()
        .stream()
        .anyMatch(m -> m.from().equals("email") && m.to().equals("contactEmail")),
      () -> "the email -> contactEmail rename is enumerable data, not opaque generated code:\n" + report
    );
    log("Act 3 — CUSTOMER_MAPPER.explain() (structure as data; MapStruct has no equivalent):", report);

    // Per-conversion values — the same rows with the actual Order data filled in, whole graph deep.
    final var trace = TelescopeMappings.ORDER_MAPPER.trace(order).toString();
    assertTrue(trace.contains("o-1"), () -> "the trace shows the real values flowing through:\n" + trace);
    log("Act 3 — ORDER_MAPPER.trace(order) (per-conversion values, nested graph and all):", trace);
  }

  // Narrate what each act proves when the suite runs, through java.lang.System.Logger — the same
  // zero-dependency facade telescope logs its own mappings through — so `./gradlew test` reads as a
  // head-to-head walkthrough rather than a wall of green ticks.
  private static void log(final String heading, final Object body) {
    LOG.log(Level.INFO, () -> "\n" + heading + "\n" + body + "\n");
  }
}
