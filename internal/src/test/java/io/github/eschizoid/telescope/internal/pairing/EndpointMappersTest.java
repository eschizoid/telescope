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

  record Invoice(String number, long totalCents) {}

  record Unrelated(String zzz, int yyy) {}

  // Pairs number with the invoice and leaves memo at its default.
  record InvoiceLine(String number, String memo) {}

  // Pairs totalCents with the invoice, whose long converts to text by the built-in conversion.
  record InvoiceTotal(String totalCents) {}

  public static final class UnrelatedBean {

    private String zzz;

    public String getZzz() {
      return zzz;
    }

    public void setZzz(final String zzz) {
      this.zzz = zzz;
    }
  }

  // A pair whose bridge holder sits beside the source, under the short name the forward mapping
  // reads.
  public record Bridged(String id) {}

  public static final class BridgedBridge {

    public static final String BRIDGE = "a bridge";

    private BridgedBridge() {}
  }

  // A non-sealed interface the runtime mapper reads by its getters.
  public interface CityView {
    String getCity();
  }

  record CityRecord(String city) {}

  // Two sealed interfaces convert case by case, whatever the names of their own getters.
  public sealed interface Shape permits Circle {
    String getSize();
  }

  public record Circle(String size) implements Shape {
    @Override
    public String getSize() {
      return size;
    }
  }

  public sealed interface Form permits Square {
    String getLabel();
  }

  public record Square(String label) implements Form {
    @Override
    public String getLabel() {
      return label;
    }
  }

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

  @Test
  @DisplayName("a pair the forward mapping fills no property of is refused, naming the properties on each side")
  void aPairThatPairsNothingIsRefused() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      EndpointMappers.checkForwardPairs(Invoice.class, Unrelated.class, false)
    );
    assertEquals(
      "Invoice -> Unrelated pairs nothing: no field of Unrelated (zzz, yyy) is named like a field of Invoice" +
        " (number, totalCents), so the mapping would leave each at its default. Check the type the endpoint" +
        " names, or map the pair with a TelescopeProjection, a Mapper bean or a @Bridge.",
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("a bean target is worded by its properties, and a source with none says so")
  void aBeanTargetIsWordedByItsProperties() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      EndpointMappers.checkForwardPairs(Order.class, UnrelatedBean.class, false)
    );
    assertEquals(
      "Order -> UnrelatedBean pairs nothing: no property of UnrelatedBean (zzz) is named like a field of Order" +
        " (none), so the mapping would leave each at its default. Check the type the endpoint names, or map" +
        " the pair with a TelescopeProjection, a Mapper bean or a @Bridge.",
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("a pair where one property pairs by name is taken, whatever the others hold")
  void aPartialPairIsTaken() {
    assertDoesNotThrow(() -> EndpointMappers.checkForwardPairs(Invoice.class, InvoiceLine.class, false));
  }

  @Test
  @DisplayName("a property paired by name through a built-in conversion counts as filled")
  void aBuiltInConversionCountsAsFilled() {
    assertDoesNotThrow(() -> EndpointMappers.checkForwardPairs(Invoice.class, InvoiceTotal.class, false));
  }

  @Test
  @DisplayName("a target with no property has nothing left at its default and is taken")
  void aTargetWithNoPropertyIsTaken() {
    assertDoesNotThrow(() -> EndpointMappers.checkForwardPairs(Invoice.class, OrderDto.class, false));
  }

  @Test
  @DisplayName("a pair a provider registers a generated bridge for is not checked")
  void aRegisteredBridgeIsNotChecked() {
    assertDoesNotThrow(() -> EndpointMappers.checkForwardPairs(Invoice.class, Unrelated.class, true));
  }

  @Test
  @DisplayName("a pair whose bridge holder sits beside the source is not checked")
  void aBridgeHolderBesideTheSourceIsNotChecked() {
    assertDoesNotThrow(() -> EndpointMappers.checkForwardPairs(Bridged.class, Unrelated.class, false));
  }

  @Test
  @DisplayName("a pair of two sealed interfaces, converted case by case, is left to the forward mapping")
  void aPairOfSealedInterfacesIsNotChecked() {
    assertDoesNotThrow(() -> EndpointMappers.checkForwardPairs(Shape.class, Form.class, false));
  }

  @Test
  @DisplayName("a non-sealed interface source is paired by its getters, and refused when none pairs")
  void anInterfaceSourceIsPairedByItsGetters() {
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      EndpointMappers.checkForwardPairs(CityView.class, Unrelated.class, false)
    );
    assertEquals(
      "CityView -> Unrelated pairs nothing: no field of Unrelated (zzz, yyy) is named like a property of CityView" +
        " (city), so the mapping would leave each at its default. Check the type the endpoint names, or map the" +
        " pair with a TelescopeProjection, a Mapper bean or a @Bridge.",
      refused.getMessage()
    );
    assertDoesNotThrow(() -> EndpointMappers.checkForwardPairs(CityView.class, CityRecord.class, false));
  }
}
