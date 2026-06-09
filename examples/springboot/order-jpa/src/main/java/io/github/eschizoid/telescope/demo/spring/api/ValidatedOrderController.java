package io.github.eschizoid.telescope.demo.spring.api;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.demo.spring.domain.LineItem;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
import io.github.eschizoid.telescope.effects.Validated;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sibling endpoint that exercises {@link Telescope#updateValidated} through Spring's request
 * pipeline. POSTs to {@code /orders/validated}; runs a deep traversal that visits every line item's
 * quantity, reports negatives as a {@link LineItemValidationError}, and accumulates every problem
 * before throwing. The companion {@link ValidatedExceptionAdvice} catches the throwable and
 * surfaces the accumulated errors as a 400 JSON body.
 */
@RestController
@RequestMapping("/orders/validated")
public class ValidatedOrderController {

  @PostMapping
  public ResponseEntity<Order> create(@RequestBody final Order request) {
    final Validated<LineItemValidationError, Order> result = Telescope.of(Order.class)
      .each(Order::lineItems)
      .field(LineItem::quantity)
      .updateValidated(request, quantity ->
        quantity < 0
          ? Validated.invalid(
              new LineItemValidationError(skuFor(request, quantity), quantity, "quantity must be non-negative")
            )
          : Validated.valid(quantity)
      );

    return switch (result) {
      case Validated.Valid<LineItemValidationError, Order>(Order accepted) -> ResponseEntity.ok(accepted);
      case Validated.Invalid<LineItemValidationError, Order> bad -> throw new InvalidOrderException(bad);
    };
  }

  // Recover the SKU of the first line item with this quantity for error context. The validator
  // closes over the request because the optic only surfaces the focused leaf (the quantity int).
  private static String skuFor(final Order request, final int quantity) {
    return request
      .lineItems()
      .stream()
      .filter(li -> li.quantity() == quantity)
      .map(LineItem::sku)
      .findFirst()
      .orElse("(unknown)");
  }
}
