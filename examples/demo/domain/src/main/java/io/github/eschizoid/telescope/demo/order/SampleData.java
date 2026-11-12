package io.github.eschizoid.telescope.demo.order;

import io.github.eschizoid.telescope.demo.api.NewLine;
import io.github.eschizoid.telescope.demo.api.PlaceOrder;
import java.time.LocalDate;
import java.util.List;

/**
 * The two customers and two orders every app starts with, so there is something to ask for.
 *
 * <ul>
 *   <li>{@code SO-1001}: Ada's order, placed and not yet paid, by card, with the {@code WELCOME10}
 *       discount and a note for the warehouse.
 *   <li>{@code SO-1002}: Grace's order, paid by bank transfer and shipped.
 * </ul>
 */
public final class SampleData {

  public static final String ADA = "ada@example.com";
  public static final String GRACE = "grace@example.com";

  private SampleData() {}

  public static void load(final OrderDesk desk, final Orders orders) {
    if (orders.customerByEmail(ADA).isPresent()) {
      return;
    }
    orders.saveCustomer(new Customer("Ada Lovelace", ADA));
    orders.saveCustomer(new Customer("Grace Hopper", GRACE));

    final var first = desk.place(
      new PlaceOrder(
        ADA,
        List.of(new NewLine("MUG-01", 2), new NewLine("TEE-02", 1)),
        Delivery.STANDARD,
        true,
        new Card("VISA", "4242", "tok_visa_4242"),
        "WELCOME10",
        List.of("gift")
      )
    );
    first.setPlacedOn(LocalDate.of(2026, 11, 2));
    first.setInternalNotes("Wrap in the blue paper.");
    orders.save(first);

    final var second = desk.place(
      new PlaceOrder(
        GRACE,
        List.of(new NewLine("CAP-03", 1)),
        Delivery.EXPRESS,
        false,
        new BankTransfer("DE89370400440532013000"),
        null,
        List.of()
      )
    );
    second.setPlacedOn(LocalDate.of(2026, 10, 28));
    orders.save(second);
    desk.pay(second.getNumber());
    desk.ship(second.getNumber());
  }
}
