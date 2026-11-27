package io.github.eschizoid.telescope.demo;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.compileFully;
import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.codegen.MapperVerifierProcessor;
import io.github.eschizoid.telescope.codegen.ProcessorHarness;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The compile-time verifier on the demo's own types. Each case compiles a mapper the way the demo
 * writes it, with one row taken out, and expects the build to fail naming the field. The rows the
 * demo ships compile, which {@code OrderDeskTest} runs.
 */
class VerifierTest {

  @Test
  @DisplayName("a PATCH mapper that forgets to keep the payment fails the build, naming the field")
  void aPatchMapperMissingAKeepFailsTheBuild() {
    final var compilation = verify(
      """
      package demo.broken;
      import static io.github.eschizoid.telescope.mapping.Mapping.keep;
      import static io.github.eschizoid.telescope.mapping.Mapping.via;
      import io.github.eschizoid.telescope.Telescope;
      import io.github.eschizoid.telescope.demo.api.OrderChanges;
      import io.github.eschizoid.telescope.demo.order.Order;
      import io.github.eschizoid.telescope.demo.order.OrderMappers;
      class Holder {
        static final Object EDITS = Telescope.edits(
          OrderChanges.class,
          Order.class,
          via(OrderChanges::lines, Order::getLines, OrderMappers.NEW_LINE),
          keep(Order::getNumber),
          keep(Order::getCustomer),
          keep(Order::getStatus),
          keep(Order::getPlacedOn),
          keep(Order::getTrackingNumber),
          keep(Order::getInternalNotes));
      }
      """
    );
    assertThat(compilation.success()).isFalse();
    assertThat(compilation.errorMessages()).contains(
      "Deep map OrderChanges → Order: an edit writes target property 'payment', and nothing fills it"
    );
  }

  @Test
  @DisplayName("the PATCH mapper with every row the demo writes compiles, the generated identifier needing none")
  void theDemosPatchRowsCompile() {
    final var compilation = verify(
      """
      package demo.fixed;
      import static io.github.eschizoid.telescope.mapping.Mapping.keep;
      import static io.github.eschizoid.telescope.mapping.Mapping.via;
      import io.github.eschizoid.telescope.Telescope;
      import io.github.eschizoid.telescope.demo.api.OrderChanges;
      import io.github.eschizoid.telescope.demo.order.Order;
      import io.github.eschizoid.telescope.demo.order.OrderMappers;
      class Holder {
        static final Object EDITS = Telescope.edits(
          OrderChanges.class,
          Order.class,
          via(OrderChanges::lines, Order::getLines, OrderMappers.NEW_LINE),
          keep(Order::getNumber),
          keep(Order::getCustomer),
          keep(Order::getStatus),
          keep(Order::getPlacedOn),
          keep(Order::getTrackingNumber),
          keep(Order::getInternalNotes),
          keep(Order::getPayment));
      }
      """
    );
    assertThat(compilation.success()).as(compilation::errorMessages).isTrue();
  }

  @Test
  @DisplayName("a version 2 mapper that forgets the buyerEmail rename fails the build, naming the field")
  void aVersionTwoMapperMissingARenameFailsTheBuild() {
    final var compilation = verify(
      """
      package demo.broken;
      import static io.github.eschizoid.telescope.mapping.Mapping.to;
      import static io.github.eschizoid.telescope.mapping.Mapping.via;
      import io.github.eschizoid.telescope.Telescope;
      import io.github.eschizoid.telescope.demo.api.ItemsV2;
      import io.github.eschizoid.telescope.demo.api.PlaceOrder;
      import io.github.eschizoid.telescope.demo.api.PlaceOrderV2;
      class Holder {
        static final Object V2 = Telescope.mapper(
          PlaceOrderV2.class,
          PlaceOrder.class,
          to(PlaceOrderV2::coupon, PlaceOrder::discountCode),
          via(PlaceOrderV2::items, PlaceOrder::lines, ItemsV2::forward, ItemsV2::backward));
      }
      """
    );
    assertThat(compilation.success()).isFalse();
    assertThat(compilation.errorMessages()).contains("'buyerEmail' has no same-name target field");
  }

  @Test
  @DisplayName("the same mapper with every row the demo writes compiles")
  void theDemosRowsCompile() {
    final var compilation = verify(
      """
      package demo.fixed;
      import static io.github.eschizoid.telescope.mapping.Mapping.to;
      import static io.github.eschizoid.telescope.mapping.Mapping.via;
      import io.github.eschizoid.telescope.Telescope;
      import io.github.eschizoid.telescope.demo.api.ItemsV2;
      import io.github.eschizoid.telescope.demo.api.PlaceOrder;
      import io.github.eschizoid.telescope.demo.api.PlaceOrderV2;
      class Holder {
        static final Object V2 = Telescope.mapper(
          PlaceOrderV2.class,
          PlaceOrder.class,
          to(PlaceOrderV2::buyerEmail, PlaceOrder::customerEmail),
          to(PlaceOrderV2::coupon, PlaceOrder::discountCode),
          via(PlaceOrderV2::items, PlaceOrder::lines, ItemsV2::forward, ItemsV2::backward));
      }
      """
    );
    assertThat(compilation.success()).as(compilation::errorMessages).isTrue();
  }

  private static ProcessorHarness.Compilation verify(final String code) {
    return compileFully(List.of(new MapperVerifierProcessor()), List.of(), source("demo.Holder", code));
  }
}
