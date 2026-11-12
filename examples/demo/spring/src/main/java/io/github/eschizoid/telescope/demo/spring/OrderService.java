package io.github.eschizoid.telescope.demo.spring;

import io.github.eschizoid.telescope.demo.api.InvoiceDto;
import io.github.eschizoid.telescope.demo.api.OrderChanges;
import io.github.eschizoid.telescope.demo.api.PlaceOrder;
import io.github.eschizoid.telescope.demo.order.Order;
import io.github.eschizoid.telescope.demo.order.OrderDesk;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Runs each of the desk's operations in a transaction. */
@Service
@Transactional
public class OrderService {

  private final OrderDesk desk;

  OrderService(final OrderDesk desk) {
    this.desk = desk;
  }

  @Transactional(readOnly = true)
  public List<Order> list() {
    return desk.list();
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
