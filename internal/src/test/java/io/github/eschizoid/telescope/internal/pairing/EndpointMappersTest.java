package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.internal.pairing.EndpointMappers.Route;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The decision both container starters take for a mapped REST body, and the words of each refusal.
 * The messages are pinned in full here, and each starter's tests pin the same words through its own
 * endpoints.
 */
class EndpointMappersTest {

  record Order() {}

  record OrderDto() {}

  @Test
  @DisplayName("one projection for the pair is taken")
  void oneProjectionIsTaken() {
    assertEquals(
      Route.PROJECTION,
      EndpointMappers.route(Order.class, OrderDto.class, List.of("OrderProjection"), false, "@A")
    );
  }

  @Test
  @DisplayName("a registered mapper is taken when no projection maps the pair")
  void aRegisteredMapperIsTaken() {
    assertEquals(Route.MAPPER, EndpointMappers.route(Order.class, OrderDto.class, List.of(), true, "@A"));
  }

  @Test
  @DisplayName("with no candidate the pair falls back to the forward mapping")
  void noCandidateFallsBackToForward() {
    assertEquals(Route.FORWARD, EndpointMappers.route(Order.class, OrderDto.class, List.of(), false, "@A"));
  }

  @Test
  @DisplayName("a projection and a registered mapper for one pair are refused, projections named first")
  void twoCandidatesAreRefused() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      EndpointMappers.route(Order.class, OrderDto.class, List.of("OrderProjection"), true, "@TelescopeResponse")
    );
    assertEquals(
      "2 mappers for Order -> OrderDto: OrderProjection, a Mapper<Order, OrderDto> bean. Add using = ... to" +
        " @TelescopeResponse to choose one.",
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("two projections for one pair are refused")
  void twoProjectionsAreRefused() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      EndpointMappers.route(Order.class, OrderDto.class, List.of("A", "B"), false, "@TelescopeGetMapping")
    );
    assertEquals(
      "2 mappers for Order -> OrderDto: A, B. Add using = ... to @TelescopeGetMapping to choose one.",
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("a projection whose type arguments are unknown is refused")
  void unresolvedArgumentsAreRefused() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      EndpointMappers.checkProjection("OpenProjection", null, OrderDto.class, Order.class, "a", OrderDto.class, "b")
    );
    assertEquals(
      "OpenProjection does not resolve its TelescopeProjection type arguments. Declare both, as in" +
        " TelescopeProjection<Source, Target>.",
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("a projection that reads another type is refused in the endpoint's words")
  void anotherSourceIsRefused() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      EndpointMappers.checkProjection(
        "OrderProjection",
        Order.class,
        OrderDto.class,
        OrderDto.class,
        "the method returns",
        null,
        "to ="
      )
    );
    assertEquals("OrderProjection maps Order, but the method returns OrderDto", refused.getMessage());
  }

  @Test
  @DisplayName("a projection that writes another type is refused in the endpoint's words")
  void anotherTargetIsRefused() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      EndpointMappers.checkProjection(
        "OrderProjection",
        Order.class,
        OrderDto.class,
        Order.class,
        "the method returns",
        Order.class,
        "to ="
      )
    );
    assertEquals("OrderProjection maps to OrderDto, but to = Order", refused.getMessage());
  }

  @Test
  @DisplayName("a projection that fits the endpoint, with no target to check, passes")
  void aFittingProjectionPasses() {
    assertDoesNotThrow(() ->
      EndpointMappers.checkProjection(
        "OrderProjection",
        Order.class,
        OrderDto.class,
        Order.class,
        "the method returns",
        null,
        "to ="
      )
    );
  }
}
