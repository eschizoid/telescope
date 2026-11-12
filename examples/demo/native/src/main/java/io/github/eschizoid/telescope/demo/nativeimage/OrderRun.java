package io.github.eschizoid.telescope.demo.nativeimage;

import static io.github.eschizoid.telescope.Edit.over;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.demo.api.BankTransferDto;
import io.github.eschizoid.telescope.demo.api.CardDto;
import io.github.eschizoid.telescope.demo.api.ImportTrace;
import io.github.eschizoid.telescope.demo.api.ImportedOrderFromMap;
import io.github.eschizoid.telescope.demo.api.ItemsV2;
import io.github.eschizoid.telescope.demo.api.NewLine;
import io.github.eschizoid.telescope.demo.api.NewLineV2;
import io.github.eschizoid.telescope.demo.api.OrderChanges;
import io.github.eschizoid.telescope.demo.api.OrderDtoTelescope;
import io.github.eschizoid.telescope.demo.api.OrderLineV2Dto;
import io.github.eschizoid.telescope.demo.api.PlaceOrder;
import io.github.eschizoid.telescope.demo.api.PlaceOrderV2;
import io.github.eschizoid.telescope.demo.api.PlaceOrderV2ToPlaceOrderBridge;
import io.github.eschizoid.telescope.demo.api.RequestMappers;
import io.github.eschizoid.telescope.demo.order.Card;
import io.github.eschizoid.telescope.demo.order.Delivery;
import io.github.eschizoid.telescope.demo.order.InMemoryOrders;
import io.github.eschizoid.telescope.demo.order.Order;
import io.github.eschizoid.telescope.demo.order.OrderDesk;
import io.github.eschizoid.telescope.demo.order.OrderStatus;
import io.github.eschizoid.telescope.demo.order.SampleData;
import io.github.eschizoid.telescope.demo.order.Seller;
import io.github.eschizoid.telescope.introspection.OpticNode;
import io.github.eschizoid.telescope.mapping.Mapping;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A day at the shop in one process, in two parts. The first runs the order service: it takes a
 * partner's feed, a version 2 order and a PATCH, takes an order through payment and shipping, and
 * publishes its views and an invoice. The second is native coverage beyond the service: shapes the
 * native-image gate has to carry that the service itself does not use, such as runtime mappers onto
 * a bean's setters or a builder, a class-level {@code @Bridge}, rows typed by a type argument and a
 * runtime {@code via} row naming a class, each over a small type of this module. Every step checks
 * its answer and the run exits non-zero if any is wrong.
 *
 * <p>Built as a GraalVM native image, this is the native-image gate in CI. Each step leans on a
 * different piece of what an image has to carry: generated bridges found through {@code
 * StackWalker} and {@code ServiceLoader}, method references read back through {@code
 * SerializedLambda}, runtime mappers that reach accessors through method handles, and classes
 * loaded by name. {@code reflect-config.json} and {@code serialization-config.json} beside this
 * class register what the runtime steps need, and nothing more: the steps that say "no
 * registration" run over types neither file names.
 */
public final class OrderRun {

  private static final Clock TODAY = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC);
  private static final Address ADA_HOME = new Address("12 St James's Square", "London", "SW1Y 4JH");

  private OrderRun() {}

  public static void main(final String[] args) {
    final var results = run();
    System.out.println();
    System.out.println("=== a day at the shop (" + runtime() + ") ===");
    var failures = 0;
    for (final var result : results) {
      System.out.println(
        (result.passed() ? "PASS  " : "FAIL  ") + result.step() + (result.passed() ? "" : "  — " + result.detail())
      );
      if (!result.passed()) {
        failures++;
      }
    }
    System.out.println();
    System.out.println(
      failures == 0 ? "ALL " + results.size() + " STEPS PASSED" : failures + " of " + results.size() + " STEPS FAILED"
    );
    System.exit(failures == 0 ? 0 : 1);
  }

  /** Runs every step and reports each, whether it passed or not. */
  public static List<Result> run() {
    final var results = new ArrayList<Result>();
    // The order service, over its own generated mappers.
    results.add(
      step("the public view hides what only the shop sees, through order.toOrderDto()", OrderRun::publicView)
    );
    results.add(step("the order list, through order.toOrderSummary()", OrderRun::orderList));
    results.add(step("version 2 renames fields and writes the date and money as text", OrderRun::versionTwoView));
    results.add(
      step(
        "a version 2 request maps forward, backward and in a patch, with no registration",
        OrderRun::versionTwoRequest
      )
    );
    results.add(
      step("version 2 items convert through methods the generated bridge calls by name", OrderRun::versionTwoItems)
    );
    results.add(
      step("a new order is priced from the catalog and its lines added through addLine", OrderRun::placeOrder)
    );
    results.add(step("a PATCH changes only what it sends, written into the entity in place", OrderRun::patchOrder));
    results.add(step("an order is paid and shipped, and only then shows its tracking number", OrderRun::lifecycle));
    results.add(
      step("a partner's untyped payload binds through the generated @FromMap binder", OrderRun::partnerImport)
    );
    results.add(
      step("the import trace finds each line's generated binder through ServiceLoader", OrderRun::importTrace)
    );
    results.add(step("an invoice merges the order, the customer, the seller and the totals", OrderRun::invoice));
    results.add(step("orders waiting to ship are counted through a bean getter", OrderRun::countWaiting));
    results.add(step("a navigator reads and updates the public view", OrderRun::navigator));
    // Native coverage beyond the service: shapes the gate carries that the service does not use.
    results.add(step("the carrier's city is written in capitals through a record path", OrderRun::capitalCity));
    results.add(step("a shipment notice is mapped at run time, record to record", OrderRun::shipmentNotice));
    results.add(step("a warehouse ticket is mapped at run time onto a bean's setters", OrderRun::warehouseTicket));
    results.add(step("a carrier label is mapped at run time through a builder", OrderRun::carrierLabel));
    results.add(step("a parcel converts through the constant its class-level @Bridge generates", OrderRun::parcelView));
    results.add(step("a pick list is cleaned by navigator paths alone, with no registration", OrderRun::pickList));
    results.add(step("a carrier webhook is read as the class its envelope's type argument names", OrderRun::webhook));
    results.add(step("a restocking batch converts at run time through a via row naming a class", OrderRun::restocking));
    return results;
  }

  private static OrderDesk openShop() {
    final var orders = new InMemoryOrders();
    final var desk = new OrderDesk(orders, new Seller("Telescope Goods Ltd", "GB123456789"), TODAY);
    SampleData.load(desk, orders);
    return desk;
  }

  private static void publicView() {
    final var desk = openShop();
    final var placed = desk.get("SO-1001").toOrderDto();
    expect(placed.customerName().equals("Ada Lovelace"), "customer name: " + placed.customerName());
    expect(placed.total().compareTo(new BigDecimal("45.00")) == 0, "total: " + placed.total());
    expect(placed.shipping() == null, "a placed order shows a tracking number: " + placed.shipping());
    expect(new CardDto("VISA", "4242").equals(placed.payment()), "payment: " + placed.payment());
    expect(!placed.toString().contains("tok_visa_4242"), "the card token leaked: " + placed);
    final var shipped = desk.get("SO-1002").toOrderDto();
    expect(shipped.shipping().trackingNumber().equals("TRK-1002"), "tracking: " + shipped.shipping());
    expect(new BankTransferDto("3000").equals(shipped.payment()), "payment: " + shipped.payment());
  }

  private static void orderList() {
    final var summaries = openShop().list().stream().map(Order::toOrderSummary).toList();
    expect(summaries.size() == 2, "summaries: " + summaries);
    expect(summaries.get(1).status() == OrderStatus.SHIPPED, "second status: " + summaries.get(1));
    expect(summaries.get(1).total().compareTo(new BigDecimal("18.00")) == 0, "second total: " + summaries.get(1));
  }

  private static void versionTwoView() {
    final var v2 = openShop().get("SO-1001").toOrderV2Dto();
    expect(v2.buyer().equals("Ada Lovelace"), "buyer: " + v2.buyer());
    expect(v2.placedOn().equals("02.11.2026"), "placedOn: " + v2.placedOn());
    expect(v2.total().equals("45.00"), "total: " + v2.total());
    expect(v2.status().equals("PLACED"), "status: " + v2.status());
    expect(v2.tags().equals(Set.of("gift")), "tags: " + v2.tags());
    expect(new OrderLineV2Dto("MUG-01", "Enamel mug", 2L, "12.50").equals(v2.lines().get(0)), "line: " + v2.lines());
  }

  private static PlaceOrderV2 versionTwoOrder() {
    return new PlaceOrderV2(
      SampleData.GRACE,
      List.of(new NewLineV2("TEE-02", 2)),
      Delivery.EXPRESS,
      false,
      new Card("MC", "5454", "tok_mc"),
      "VIP25",
      Set.of("b2b")
    );
  }

  // PlaceOrderV2, PlaceOrder, NewLineV2 and NewLine have no reflection registration, so a mapper
  // that fell back to the runtime path fails here.
  private static void versionTwoRequest() {
    final var request = versionTwoOrder();
    final var command = RequestMappers.V2.forward(request);
    expect(command.equals(PlaceOrderV2ToPlaceOrderBridge.forward(request)), "the field disagrees with its bridge");
    expect(command.customerEmail().equals(SampleData.GRACE), "customerEmail: " + command.customerEmail());
    expect(command.lines().equals(List.of(new NewLine("TEE-02", 2))), "lines: " + command.lines());
    expect(command.tags().equals(List.of("b2b")), "tags: " + command.tags());
    expect(request.equals(RequestMappers.V2.backward(command)), "backward: " + RequestMappers.V2.backward(command));
    final var patched = RequestMappers.V2.patch(
      request,
      new PlaceOrder(null, null, Delivery.STANDARD, false, null, null, null)
    );
    expect(
      patched.delivery() == Delivery.STANDARD && patched.buyerEmail().equals(SampleData.GRACE),
      "patch: " + patched
    );
  }

  // The via row names ItemsV2::forward and ItemsV2::backward, which the bridge calls directly, so
  // the method references are never read back and need no serialization registration.
  private static void versionTwoItems() {
    final var runs = RequestMappers.V2.explain().declared().map(OpticNode.Declared::runs).orElse(null);
    expect(
      runs == OpticNode.Runs.GENERATED,
      "the version 2 mapper is not on the generated path: " + RequestMappers.V2.explain()
    );
    final var desk = openShop();
    final var placed = desk.place(RequestMappers.V2.forward(versionTwoOrder()));
    expect(placed.getNumber().equals("SO-1003"), "number: " + placed.getNumber());
    expect(
      placed.toOrderDto().total().compareTo(new BigDecimal("37.50")) == 0,
      "total: " + placed.toOrderDto().total()
    );
  }

  private static void placeOrder() {
    final var placed = openShop().place(
      new PlaceOrder(SampleData.ADA, List.of(new NewLine("BAG-04", 2)), null, false, null, null, List.of("tote"))
    );
    expect(placed.getLines().size() == 1, "lines: " + placed.getLines().size());
    final var line = placed.getLines().get(0);
    expect(line.getOrder() == placed, "the line does not point back at its order");
    expect(line.getCostPrice().compareTo(new BigDecimal("5.00")) == 0, "cost: " + line.getCostPrice());
    expect(placed.getDelivery() == Delivery.STANDARD, "delivery: " + placed.getDelivery());
    expect(placed.getTags().equals(List.of("tote")), "tags: " + placed.getTags());
  }

  private static void patchOrder() {
    final var desk = openShop();
    final var order = desk.get("SO-1001");
    final var lines = order.getLines();
    desk.change("SO-1001", new OrderChanges(null, Delivery.EXPRESS, null, null, List.of(new NewLine("CAP-03", 3))));
    expect(order.getLines() == lines, "the managed list of lines was replaced");
    expect(
      order.getLines().size() == 1 && order.getLines().get(0).getSku().equals("CAP-03"),
      "lines were not refilled"
    );
    expect(order.getLines().get(0).getOrder() == order, "the new line does not point back at its order");
    expect(order.getDelivery() == Delivery.EXPRESS, "delivery: " + order.getDelivery());
    expect("WELCOME10".equals(order.getDiscountCode()), "the discount code was lost: " + order.getDiscountCode());
    expect(
      "Wrap in the blue paper.".equals(order.getInternalNotes()),
      "the notes were lost: " + order.getInternalNotes()
    );
  }

  private static void lifecycle() {
    final var desk = openShop();
    desk.pay("SO-1001");
    expect(desk.get("SO-1001").toOrderDto().shipping() == null, "a paid order shows its tracking number");
    try {
      desk.cancel("SO-1002");
      throw new AssertionError("a shipped order was cancelled");
    } catch (final IllegalStateException refused) {
      expect(refused.getMessage().contains("SHIPPED"), "refusal: " + refused.getMessage());
    }
    final var shipped = desk.ship("SO-1001").toOrderDto();
    expect(shipped.shipping() != null && "TRK-1001".equals(shipped.shipping().trackingNumber()), "shipped: " + shipped);
  }

  private static Map<String, Object> partnerPayload(final String quantityKey) {
    return Map.of(
      "customerEmail",
      SampleData.ADA,
      "delivery",
      "EXPRESS",
      "giftWrap",
      "true",
      "lines",
      List.of(Map.of("sku", "CAP-03", quantityKey, "4"))
    );
  }

  private static void partnerImport() {
    final var command = RequestMappers.IMPORT.forward(ImportedOrderFromMap.fromMap(partnerPayload("quantity")));
    expect(command.delivery() == Delivery.EXPRESS, "delivery: " + command.delivery());
    expect(command.giftWrap(), "giftWrap was not read as true");
    expect(command.lines().equals(List.of(new NewLine("CAP-03", 4))), "lines: " + command.lines());
    final var total = openShop().place(command).toOrderDto().total();
    expect(total.compareTo(new BigDecimal("72.00")) == 0, "total: " + total);
  }

  private static void importTrace() {
    final var good = ImportTrace.of(partnerPayload("quantity"));
    expect(good.contains("ImportedLine[sku=CAP-03, quantity=4]"), "the line was not built by its binder: " + good);
    final var misspelled = ImportTrace.of(partnerPayload("qty"));
    expect(
      misspelled.contains("ImportedLine[sku=CAP-03, quantity=null]"),
      "the trace hides the misspelled key: " + misspelled
    );
  }

  private static void invoice() {
    final var invoice = openShop().invoice("SO-1001");
    expect(
      invoice.billTo().equals("Ada Lovelace") && invoice.sellerVatId().equals("GB123456789"),
      "invoice: " + invoice
    );
    expect(invoice.subtotal().compareTo(new BigDecimal("50.00")) == 0, "subtotal: " + invoice.subtotal());
    expect(invoice.discount().compareTo(new BigDecimal("5.00")) == 0, "discount: " + invoice.discount());
  }

  private static void countWaiting() {
    final Telescope<Order, OrderStatus> status = Telescope.ofBean(Order.class).field(Order::getStatus);
    final var waiting = openShop()
      .list()
      .stream()
      .filter(order -> status.read(order) == OrderStatus.PLACED)
      .count();
    expect(waiting == 1, "waiting: " + waiting);
  }

  private static void navigator() {
    final var dto = openShop().get("SO-1002").toOrderDto();
    final String tracking = OrderDtoTelescope.of().shipping().trackingNumber().read(dto);
    expect("TRK-1002".equals(tracking), "tracking: " + tracking);
    final var shouted = OrderDtoTelescope.of().customerName().update(dto, String::toUpperCase);
    expect(shouted.customerName().equals("GRACE HOPPER") && shouted.number().equals("SO-1002"), "update: " + shouted);
  }

  private static Shipment shipment() {
    return new Shipment("SO-1001", "Ada Lovelace", ADA_HOME, Delivery.EXPRESS);
  }

  private static void capitalCity() {
    final var city = Telescope.of(Shipment.class).field(Shipment::address).field(Address::city);
    final var labelled = city.update(shipment(), String::toUpperCase);
    expect("LONDON".equals(city.read(labelled)), "city: " + labelled.address());
    expect(
      labelled.address().postcode().equals("SW1Y 4JH") && labelled.delivery() == Delivery.EXPRESS,
      "lost: " + labelled
    );
  }

  private static void shipmentNotice() {
    final var notice = Telescope.mapper(Shipment.class, ShipmentNotice.class).forward(shipment());
    expect(notice.recipient().equals("Ada Lovelace") && notice.address().equals(ADA_HOME), "notice: " + notice);
    expect(notice.delivery() == Delivery.EXPRESS, "delivery: " + notice.delivery());
  }

  private static void warehouseTicket() {
    final var ticket = Telescope.mapper(Shipment.class, WarehouseTicket.class).forward(shipment());
    expect(
      ticket.getOrderNumber().equals("SO-1001") && ticket.getAddress().equals(ADA_HOME),
      "ticket: " + ticket.getOrderNumber()
    );
    expect(ticket.getDelivery() == Delivery.EXPRESS, "delivery: " + ticket.getDelivery());
  }

  private static void carrierLabel() {
    final var label = Telescope.mapper(Shipment.class, CarrierLabel.class).forward(shipment());
    expect(
      label.getRecipient().equals("Ada Lovelace") && label.getAddress().city().equals("London"),
      "label: " + label.getRecipient()
    );
  }

  private static void parcelView() {
    final var view = ParcelBridge.BRIDGE.read(new Parcel("SO-1001", 820));
    expect(new ParcelView("SO-1001", 820).equals(view), "view: " + view);
  }

  // PickList and Pick have no reflection registration, so each fused record level has to be rebuilt
  // through the navigators' own generated setters.
  private static void pickList() {
    final var forScanner = Telescope.all(
      over(PickListTelescope.of().orderNumber(), String::strip),
      over(PickListTelescope.of().picks().each().sku(), String::toUpperCase),
      over(PickListTelescope.of().picks().each().bin(), (final String bin) -> "BIN-" + bin)
    );
    final var cleaned = forScanner.apply(
      new PickList(" SO-1001 ", List.of(new Pick("mug-01", 2, "A4"), new Pick("tee-02", 1, "C1")))
    );
    final var expected = new PickList(
      "SO-1001",
      List.of(new Pick("MUG-01", 2, "BIN-A4"), new Pick("TEE-02", 1, "BIN-C1"))
    );
    expect(expected.equals(cleaned), "pick list: " + cleaned);
  }

  // Envelope declares its payload with a type variable, so each row reads it as the class its type
  // argument names, which the mapper loads by name and checks each value against. The classes are
  // passed as values so the compile-time verifier does not read the rows against the raw record.
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static void webhook() {
    final Class source = Envelope.class;
    final Class<Notice> target = Notice.class;
    final Mapper<Envelope<?>, Notice> events = Telescope.mapper(
      source,
      target,
      Mapping.<Envelope<String>, Notice, String, String>to(
        Envelope::payload,
        Notice::text,
        s -> "carrier: " + s,
        s -> s
      )
    );
    final Mapper<Envelope<?>, Notice> codes = Telescope.mapper(
      source,
      target,
      Mapping.<Envelope<TrackingCode>, Notice, TrackingCode, String>to(
        Envelope::payload,
        Notice::text,
        TrackingCode::code,
        TrackingCode::new
      )
    );
    final var code = codes.forward(new Envelope<>(new TrackingCode("TRK-20481")));
    expect(new Notice("TRK-20481").equals(code), "code: " + code);
    final var event = events.forward(new Envelope<>("out for delivery"));
    expect(new Notice("carrier: out for delivery").equals(event), "event: " + event);
    try {
      events.forward(new Envelope<>(5));
      throw new AssertionError("a number was handed to the conversion that takes text");
    } catch (final ClassCastException refused) {
      expect(
        refused.getMessage() != null &&
          refused.getMessage().contains("is handed to a conversion that takes java.lang.String"),
        "the refusal does not name the class the row reads: " + refused.getMessage()
      );
    }
  }

  // The runtime mapper reads ItemsV2's declared methods to choose forward and backward, which in an
  // image needs the class registered for its declared methods.
  private static void restocking() {
    final var mapper = Telescope.mapper(
      Batch.class,
      BatchLines.class,
      Mapping.via(Batch::items, BatchLines::items, ItemsV2.class)
    );
    final var runs = mapper.explain().declared().map(OpticNode.Declared::runs).orElse(OpticNode.Runs.RUNTIME);
    expect(runs == OpticNode.Runs.RUNTIME, "the batch mapper is not on the runtime path: " + mapper.explain());
    final var batch = new Batch("R-7", List.of(new NewLineV2("MUG-01", 24), new NewLineV2("BAG-04", 12)));
    final var lines = mapper.forward(batch);
    expect(
      new BatchLines("R-7", List.of(new NewLine("MUG-01", 24), new NewLine("BAG-04", 12))).equals(lines),
      "lines: " + lines
    );
    expect(batch.equals(mapper.backward(lines)), "backward: " + mapper.backward(lines));
  }

  private static String runtime() {
    return System.getProperty("org.graalvm.nativeimage.imagecode") != null ? "native image" : "JVM";
  }

  private static void expect(final boolean condition, final String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  private static Result step(final String step, final Runnable body) {
    try {
      body.run();
      return new Result(step, true, null);
    } catch (final Throwable failure) {
      System.err.println("[FAIL] " + step);
      failure.printStackTrace();
      return new Result(step, false, failure.getClass().getSimpleName() + ": " + failure.getMessage());
    }
  }

  /** One step of the run and how it went. */
  public record Result(String step, boolean passed, String detail) {}
}
