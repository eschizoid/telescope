package io.github.eschizoid.telescope.example.mapstruct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.eschizoid.telescope.example.mapstruct.domain.Customer;
import io.github.eschizoid.telescope.example.mapstruct.domain.LineItem;
import io.github.eschizoid.telescope.example.mapstruct.domain.Order;
import io.github.eschizoid.telescope.example.mapstruct.mapstruct.OrderMapStructMapper;
import io.github.eschizoid.telescope.example.mapstruct.mapstruct.SilentDropMapper;
import io.github.eschizoid.telescope.example.mapstruct.telescope.TelescopeMappings;
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
    assertEquals("ada@example.com", viaTelescope.customer().contactEmail(), "the email -> contactEmail rename landed");
    assertEquals(2, viaTelescope.lines().size(), "the line-item collection recursed");
  }

  @Test
  @DisplayName("telescope mapping is bidirectional for free — backward(forward(order)) == order")
  void telescopeMapperRoundTrips() {
    final var order = sampleOrder();
    final var roundTripped = TelescopeMappings.ORDER_MAPPER.backward(TelescopeMappings.ORDER_MAPPER.forward(order));
    assertEquals(order, roundTripped, "one mapper(...) value gives both directions; MapStruct needs a second method");
  }

  @Test
  @DisplayName("Unmapped-target footgun: MapStruct's default policy leaves a target with no source silently null")
  void mapStructSilentlyDropsUnmappedTarget() {
    final var dto = SilentDropMapper.INSTANCE.toContactDto(new Customer("Ada", "ada@example.com"));

    assertEquals("ada@example.com", dto.contactEmail(), "the mapped field is fine");
    assertNull(
      dto.region(),
      "default unmappedTargetPolicy=WARN compiles and nulls an unmapped target (a source rename is the separate case — a compile error)"
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
  }
}
