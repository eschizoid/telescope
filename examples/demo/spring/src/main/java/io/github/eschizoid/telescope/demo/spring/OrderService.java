package io.github.eschizoid.telescope.demo.spring;

import io.github.eschizoid.telescope.demo.api.InvoiceDto;
import io.github.eschizoid.telescope.demo.api.OrderChanges;
import io.github.eschizoid.telescope.demo.api.OrderRow;
import io.github.eschizoid.telescope.demo.api.PlaceOrder;
import io.github.eschizoid.telescope.demo.order.Order;
import io.github.eschizoid.telescope.demo.order.OrderDesk;
import io.github.eschizoid.telescope.demo.order.OrderStatus;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Runs each of the desk's operations in a transaction. */
@Service
@Transactional
public class OrderService {

  private final OrderDesk desk;
  private final OrderRepository repository;

  OrderService(final OrderDesk desk, final OrderRepository repository) {
    this.desk = desk;
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public List<Order> list() {
    return desk.list();
  }

  /**
   * One page of the orders in {@code status} as dashboard rows, with their customers. With {@code
   * withTags} the tags of the whole page are loaded in one more query and each row carries its
   * order's; without it each row's tags are null.
   */
  @Transactional(readOnly = true)
  public Page<OrderRow> dashboard(final OrderStatus status, final Pageable pageable, final boolean withTags) {
    final var page = repository.findByStatus(status, pageable);
    if (withTags && page.hasContent()) repository.loadTags(page.getContent());
    return page.map(order -> withTags ? order.toOrderRow() : order.toOrderRow().withoutTags());
  }

  @Transactional(readOnly = true)
  public Order get(final String number) {
    return desk.get(number);
  }

  public Order place(final PlaceOrder command) {
    return desk.place(command);
  }

  public Order change(final String number, final OrderChanges changes) {
    return desk.change(number, changes);
  }

  public Order pay(final String number) {
    return desk.pay(number);
  }

  public Order ship(final String number) {
    return desk.ship(number);
  }

  public Order cancel(final String number) {
    return desk.cancel(number);
  }

  @Transactional(readOnly = true)
  public InvoiceDto invoice(final String number) {
    return desk.invoice(number);
  }
}
