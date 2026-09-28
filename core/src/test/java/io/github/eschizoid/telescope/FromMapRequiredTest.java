package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;
import static io.github.eschizoid.telescope.mapping.MapExtractStep.required;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code required(...)} row: its key has to carry a value, and a map that does not is refused
 * rather than filled with a default indistinguishable from a supplied value.
 */
class FromMapRequiredTest {

  record Ticket(String id, String note, int count) {}

  @Test
  @DisplayName("an absent required key is refused, naming the key and the component it fills")
  void anAbsentRequiredKeyIsRefused() {
    final var mapper = Telescope.fromMap(
      Ticket.class,
      required("cust", Ticket::id, Object::toString),
      extract("note", Ticket::note, Object::toString)
    );

    final var refusal = assertThrows(IllegalArgumentException.class, () -> mapper.forward(Map.of("note", "n")));

    assertEquals(
      "Telescope.fromMap: the map carries no value for required key \"cust\" (component 'id') of Ticket",
      refusal.getMessage()
    );
  }

  @Test
  @DisplayName("every missing required key is named in one refusal, in component order")
  void everyMissingRequiredKeyIsNamed() {
    final var mapper = Telescope.fromMap(
      Ticket.class,
      required("qty", Ticket::count, v -> Integer.parseInt(v.toString())),
      required("cust", Ticket::id, Object::toString),
      required("memo", Ticket::note, Object::toString)
    );

    final var refusal = assertThrows(IllegalArgumentException.class, () -> mapper.forward(Map.of("memo", "m")));

    assertEquals(
      "Telescope.fromMap: the map carries no value for required keys \"cust\" (component 'id'), " +
        "\"qty\" (component 'count') of Ticket",
      refusal.getMessage()
    );
  }

  @Test
  @DisplayName("a required key holding null is refused, as an absent one is")
  void aNullRequiredValueIsRefused() {
    final var mapper = Telescope.fromMap(Ticket.class, required("cust", Ticket::id, Object::toString));
    final var withNull = new HashMap<String, Object>();
    withNull.put("cust", null);

    assertThrows(IllegalArgumentException.class, () -> mapper.forward(withNull));
  }

  @Test
  @DisplayName("the refusal comes before any converter runs, so no converter sees a source that is refused")
  void theRefusalPrecedesEveryConverter() {
    final var calls = new AtomicInteger();
    final var mapper = Telescope.fromMap(
      Ticket.class,
      extract("cust", Ticket::id, v -> {
        calls.incrementAndGet();
        return v.toString();
      }),
      required("memo", Ticket::note, Object::toString)
    );

    assertThrows(IllegalArgumentException.class, () -> mapper.forward(Map.of("cust", "c")));
    assertEquals(0, calls.get());
  }

  @Test
  @DisplayName("a required row converts like any other when its key carries a value, beside lenient extract rows")
  void aSatisfiedRequiredRowConverts() {
    final var mapper = Telescope.fromMap(
      Ticket.class,
      required("cust", Ticket::id, Object::toString),
      extract("note", Ticket::note, Object::toString)
    );

    assertEquals(new Ticket("c-1", null, 0), mapper.forward(Map.of("cust", "c-1")));
  }

  record Address(String city) {}

  record Order(String id, Address shipTo) {}

  @Test
  @DisplayName("a nested required row refuses an absent nested map and binds a present one through its mapper")
  void aNestedRequiredRow() {
    final var mapper = Telescope.fromMap(
      Order.class,
      extract("id", Order::id, Object::toString),
      required(
        "ship_to",
        Order::shipTo,
        Telescope.fromMap(Address.class, extract("city", Address::city, Object::toString))
      )
    );

    assertEquals(
      new Order("o", new Address("Austin")),
      mapper.forward(Map.of("id", "o", "ship_to", Map.of("city", "Austin")))
    );
    final var refusal = assertThrows(IllegalArgumentException.class, () -> mapper.forward(Map.of("id", "o")));
    assertEquals(
      "Telescope.fromMap: the map carries no value for required key \"ship_to\" (component 'shipTo') of Order",
      refusal.getMessage()
    );
    final var wrongShape = assertThrows(IllegalArgumentException.class, () ->
      mapper.forward(Map.of("ship_to", "Austin"))
    );
    assertEquals(
      "fromMap key \"ship_to\" expects a nested Map<String, Object> but got java.lang.String",
      wrongShape.getMessage()
    );
  }
}
