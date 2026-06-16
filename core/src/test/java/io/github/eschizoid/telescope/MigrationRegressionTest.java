package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.mapping.WriteHint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Regressions for the migration-feedback bugs (see {@code docs/migration-feedback.md}). One nested
 * class per bug — keeps the test names traceable back to the feedback entries.
 */
class MigrationRegressionTest {

  @Nested
  @DisplayName("Bug 2 — boolean accessor NPE (P0 blocker)")
  class Bug2BooleanAccessorNpe {

    public static class Order {

      private boolean shipped;
      private String name;

      public boolean isShipped() {
        return shipped;
      }

      public void setShipped(final boolean shipped) {
        this.shipped = shipped;
      }

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }
    }

    public static class OrderDto {

      private boolean shipped;
      private String name;

      public boolean isShipped() {
        return shipped;
      }

      public void setShipped(final boolean shipped) {
        this.shipped = shipped;
      }

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }
    }

    @Test
    @DisplayName("Telescope.mapper(Order, OrderDto) constructs without NPE when boolean accessors are present")
    void mapperConstructionDoesNotNpeOnBooleanAccessors() {
      // The migration feedback reports any class with a boolean primitive field is unusable with
      // Telescope.mapper() / mapperForward() because Beans.propertyOf(null) NPEs in the bean
      // auto-discovery path. The unit-level fix lives in Beans; this test pins the end-to-end
      // contract from the public API surface.
      assertDoesNotThrow(() ->
        Telescope.mapper(Order.class, OrderDto.class, writeBeans(WriteHint.WriteStrategy.SETTERS))
      );
    }

    @Test
    @DisplayName("forward(Order) on a populated source round-trips the boolean field correctly")
    void mapperRoundTripsBooleanField() {
      final var mapper = Telescope.mapper(Order.class, OrderDto.class, writeBeans(WriteHint.WriteStrategy.SETTERS));
      final var src = new Order();
      src.setShipped(true);
      src.setName("alice");
      final var tgt = mapper.forward(src);
      assertEquals(true, tgt.isShipped());
      assertEquals("alice", tgt.getName());
    }

    public static class OrderWithCustomer {

      private Customer customer;

      public Customer getCustomer() {
        return customer;
      }

      public void setCustomer(final Customer customer) {
        this.customer = customer;
      }
    }

    public static class Customer {

      private String email;

      public String getEmail() {
        return email;
      }

      public void setEmail(final String email) {
        this.email = email;
      }
    }

    public static class FlatOrderDto {

      private String customerEmail;

      public String getCustomerEmail() {
        return customerEmail;
      }

      public void setCustomerEmail(final String customerEmail) {
        this.customerEmail = customerEmail;
      }
    }

    @Test
    @DisplayName("Mapping.to(srcTelescope, tgtAccessor) — nested source path — does not NPE at mapper construction")
    void nestedSourceTelescopeRowConstructsWithoutNpe() {
      // The actual reproduction of Bug 2's reported NPE: `Mapping.to(srcTelescope, tgtAccessor)`
      // builds a FromTelescopeTo row whose `sourceField()` is null by design (the source is a
      // nested telescope, not a flat accessor). DeepMap.populateIso normalizes the source field
      // unconditionally before the FromTelescopeTo `instanceof` peel, so `Beans.normalize(null)`
      // → `Beans.propertyOf(null)` would NPE without the defensive guard.
      assertDoesNotThrow(() ->
        Telescope.mapper(
          OrderWithCustomer.class,
          FlatOrderDto.class,
          io.github.eschizoid.telescope.mapping.Mapping.to(
            Telescope.ofBean(OrderWithCustomer.class).field(OrderWithCustomer::getCustomer).field(Customer::getEmail),
            FlatOrderDto::getCustomerEmail
          ),
          writeBeans(WriteHint.WriteStrategy.SETTERS)
        )
      );
    }
  }

  @Nested
  @DisplayName("Bug 4 — NPE on null intermediate objects in nested telescope paths")
  class Bug4NullIntermediateNpe {

    public static class Order {

      private Customer customer; // may be null

      public Customer getCustomer() {
        return customer;
      }

      public void setCustomer(final Customer customer) {
        this.customer = customer;
      }
    }

    public static class Customer {

      private String email;

      public String getEmail() {
        return email;
      }

      public void setEmail(final String email) {
        this.email = email;
      }
    }

    public static class OrderDto {

      private String customerEmail;

      public String getCustomerEmail() {
        return customerEmail;
      }

      public void setCustomerEmail(final String customerEmail) {
        this.customerEmail = customerEmail;
      }
    }

    @Test
    @DisplayName("forward(order) on a source whose nested intermediate is null short-circuits to null instead of NPE")
    void forwardWithNullIntermediateShortCircuitsToNull() {
      // The adopter's exact scenario: a 2-hop nested source path order → customer → email, with
      // order.customer == null at runtime. Before the fix, the second hop calls
      // Beans.readProperty(null, "email") which delegates to persistentClassOf(null) → null,
      // then ClassValue.get(null) NPEs. After the fix, readProperty short-circuits to null and
      // the optic pipeline propagates the null through the rest of the chain.
      final var mapper = Telescope.mapper(
        Order.class,
        OrderDto.class,
        io.github.eschizoid.telescope.mapping.Mapping.to(
          Telescope.ofBean(Order.class).field(Order::getCustomer).field(Customer::getEmail),
          OrderDto::getCustomerEmail
        ),
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var src = new Order(); // customer left null
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(null, tgt.getCustomerEmail());
    }

    @Test
    @DisplayName("forward(order) on a populated nested intermediate still reads through the chain")
    void forwardWithPopulatedIntermediateReadsThrough() {
      final var mapper = Telescope.mapper(
        Order.class,
        OrderDto.class,
        io.github.eschizoid.telescope.mapping.Mapping.to(
          Telescope.ofBean(Order.class).field(Order::getCustomer).field(Customer::getEmail),
          OrderDto::getCustomerEmail
        ),
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var customer = new Customer();
      customer.setEmail("alice@example.com");
      final var src = new Order();
      src.setCustomer(customer);
      final var tgt = mapper.forward(src);
      assertEquals("alice@example.com", tgt.getCustomerEmail());
    }
  }
}
