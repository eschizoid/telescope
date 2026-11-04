package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Edit.over;
import static io.github.eschizoid.telescope.Telescope.path;
import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.constant;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.zip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code Telescope.path(A::b, B::c, ...)} builds the path the {@code of(A.class).field(...)} chain
 * builds: the same reads and writes, the same {@code explain()} nodes, the same hops {@code
 * Telescope.all} fuses by, and the same rows wherever a telescope path is accepted.
 */
class PathShorthandTest {

  public record Address(String city, int zip) {}

  public record Customer(Address address, String email) {}

  public record Invoice(Customer customer, int qty) {}

  public record InvoiceDto(String billTo, String city, int qty) {}

  public record Flat(String email, String city, int qty) {}

  public record L4(String v, int n) {}

  public record L3(L4 d) {}

  public record L2(L3 c) {}

  public record L1(L2 b) {}

  public record Root(L1 a, String tag) {}

  private static final Invoice INVOICE = new Invoice(new Customer(new Address("Paris", 75), "ANN@X.IO"), 3);
  private static final Root ROOT = new Root(new L1(new L2(new L3(new L4("deep", 5)))), "t");

  /** The fusion identity of each hop, in order. */
  private static List<Object> hopKeys(final Telescope<?, ?> path) {
    final var keys = new ArrayList<Object>();
    for (final var hop : path.hops) keys.add(hop.key());
    return keys;
  }

  /**
   * The single pass Telescope.all runs for {@code edits}, or null when it folds them in sequence.
   */
  @SafeVarargs
  @SuppressWarnings("varargs")
  private static <S> Function<S, S> fused(final Edit<S>... edits) {
    return Fusion.fuse(edits);
  }

  /** Holds {@code shorthand} to be the path {@code chain} is, member by member. */
  private static void assertSamePath(final Telescope<?, ?> chain, final Telescope<?, ?> shorthand) {
    assertEquals(chain.explain(), shorthand.explain());
    assertEquals(hopKeys(chain), hopKeys(shorthand));
    assertEquals(chain.firstHopName, shorthand.firstHopName);
    assertSame(chain.rootClass, shorthand.rootClass);
    assertSame(chain.fieldOptics, shorthand.fieldOptics);
    assertEquals(chain.pathName(), shorthand.pathName());
  }

  @Nested
  @DisplayName("on its own, path(...) is the field chain it stands for")
  class OnItsOwn {

    @Test
    @DisplayName("two hops read, update, set and list what the chain does, with the same explain() and hops")
    void twoHops() {
      final var chain = Telescope.of(Invoice.class).field(Invoice::customer).field(Customer::email);
      final Telescope<Invoice, String> shorthand = path(Invoice::customer, Customer::email);
      assertSamePath(chain, shorthand);
      assertEquals(chain.read(INVOICE), shorthand.read(INVOICE));
      assertEquals(chain.update(INVOICE, String::toLowerCase), shorthand.update(INVOICE, String::toLowerCase));
      assertEquals("ann@x.io", shorthand.update(INVOICE, String::toLowerCase).customer().email());
      assertEquals(chain.set(INVOICE, "b@y"), shorthand.set(INVOICE, "b@y"));
      assertEquals(chain.toList(INVOICE), shorthand.toList(INVOICE));
    }

    @Test
    @DisplayName("three hops are the chain of three field steps")
    void threeHops() {
      final var chain = Telescope.of(Invoice.class)
        .field(Invoice::customer)
        .field(Customer::address)
        .field(Address::city);
      final Telescope<Invoice, String> shorthand = path(Invoice::customer, Customer::address, Address::city);
      assertSamePath(chain, shorthand);
      assertEquals("Paris", shorthand.read(INVOICE));
      assertEquals(chain.update(INVOICE, String::toUpperCase), shorthand.update(INVOICE, String::toUpperCase));
      assertEquals(chain.toList(INVOICE), shorthand.toList(INVOICE));
    }

    @Test
    @DisplayName("four and five hops are the chains of four and five field steps")
    void fourAndFiveHops() {
      final var four = Telescope.of(Root.class).field(Root::a).field(L1::b).field(L2::c).field(L3::d);
      final Telescope<Root, L4> shortFour = path(Root::a, L1::b, L2::c, L3::d);
      assertSamePath(four, shortFour);
      assertEquals(four.read(ROOT), shortFour.read(ROOT));
      final var five = Telescope.of(Root.class).field(Root::a).field(L1::b).field(L2::c).field(L3::d).field(L4::v);
      final Telescope<Root, String> shortFive = path(Root::a, L1::b, L2::c, L3::d, L4::v);
      assertSamePath(five, shortFive);
      assertEquals("DEEP", shortFive.update(ROOT, String::toUpperCase).a().b().c().d().v());
      assertEquals(five.update(ROOT, String::toUpperCase), shortFive.update(ROOT, String::toUpperCase));
    }

    @Test
    @DisplayName("navigation continues after a path as it does after the chain")
    void navigationContinues() {
      final var chain = Telescope.of(Invoice.class)
        .field(Invoice::customer)
        .field(Customer::address)
        .field(Address::zip);
      final var shorthand = path(Invoice::customer, Customer::address).field(Address::zip);
      assertSamePath(chain, shorthand);
      assertEquals(
        76,
        shorthand
          .update(INVOICE, z -> z + 1)
          .customer()
          .address()
          .zip()
      );
    }

    @Test
    @DisplayName("a path fuses in Telescope.all as the chain does, with the chain and with another path")
    void fusesInAll() {
      final Edit<Invoice> email = over(path(Invoice::customer, Customer::email), String::toLowerCase);
      final Edit<Invoice> city = over(path(Invoice::customer, Customer::address, Address::city), String::toUpperCase);
      final Edit<Invoice> chainCity = over(
        Telescope.of(Invoice.class).field(Invoice::customer).field(Customer::address).field(Address::city),
        String::toUpperCase
      );
      assertNotNull(fused(email, city));
      assertNotNull(fused(email, chainCity));
      final var expected = new Invoice(new Customer(new Address("PARIS", 75), "ann@x.io"), 3);
      assertEquals(expected, Telescope.all(email, city).apply(INVOICE));
      assertEquals(expected, Telescope.all(email, chainCity).apply(INVOICE));
    }

    @Test
    @DisplayName("a bean root starts at ofBean, as the chain a bean path is written with does")
    void aBeanRootStartsAtOfBean() {
      final var chain = Telescope.ofBean(PathSub.class).field(PathSub::getCustomer).field(Customer::email);
      final Telescope<PathSub, String> shorthand = path(PathSub::getCustomer, Customer::email);
      assertSamePath(chain, shorthand);
      assertNotEquals(Telescope.of(PathSub.class).fieldOptics, shorthand.fieldOptics);
    }
  }

  @Nested
  @DisplayName("the class a path starts at")
  class RootClass {

    @Test
    @DisplayName("is the class the first accessor is applied to where it is written, not the one declaring the getter")
    void isTheReceiverForAnInheritedGetter() {
      final Accessor<PathSub, Customer> inherited = PathSub::getCustomer;
      assertSame(PathBase.class, LambdaIntrospection.implClassOf(inherited));
      final Telescope<PathSub, String> email = path(inherited, Customer::email);
      assertSame(PathSub.class, email.rootClass);
      final var sub = new PathSub();
      sub.setNote("kept");
      sub.setCustomer(new Customer(null, "ANN@X.IO"));
      final var lowered = email.update(sub, String::toLowerCase);
      final var rebuilt = assertInstanceOf(PathSub.class, lowered);
      assertEquals("ann@x.io", rebuilt.getCustomer().email());
      assertEquals("kept", rebuilt.getNote());
    }

    @Test
    @DisplayName("is the class a typed variable or a type witness names, when the first getter is overloaded")
    void isNamedByTheCallerForAnOverloadedGetter() {
      final var holder = new Overloaded(new Customer(null, "a@b"));
      final Telescope<Overloaded, String> typed = path(Overloaded::getCustomer, Customer::email);
      final var witnessed = Telescope.<Overloaded, Customer, String>path(Overloaded::getCustomer, Customer::email);
      assertSame(Overloaded.class, typed.rootClass);
      assertSame(Overloaded.class, witnessed.rootClass);
      assertEquals("a@b", typed.read(holder));
      assertEquals("a@b", witnessed.read(holder));
    }

    @Test
    @DisplayName("is the class written at the use site when the getter is the superclass's own")
    void isTheSuperclassWhenWrittenSo() {
      final Telescope<PathBase, String> email = path(PathBase::getCustomer, Customer::email);
      assertSame(PathBase.class, email.rootClass);
    }
  }

  @Nested
  @DisplayName("in a row, path(...) is accepted wherever the chain is, with the same result")
  class InRows {

    private final Telescope<Invoice, String> chainEmail = Telescope.of(Invoice.class)
      .field(Invoice::customer)
      .field(Customer::email);
    private final Telescope<Invoice, String> chainCity = Telescope.of(Invoice.class)
      .field(Invoice::customer)
      .field(Customer::address)
      .field(Address::city);

    @Test
    @DisplayName("as the source of to(path, accessor), both ways and in patch")
    void asASource() {
      final var chain = Telescope.mapper(
        Invoice.class,
        InvoiceDto.class,
        to(chainEmail, InvoiceDto::billTo),
        to(chainCity, InvoiceDto::city)
      );
      final var shorthand = Telescope.mapper(
        Invoice.class,
        InvoiceDto.class,
        to(path(Invoice::customer, Customer::email), InvoiceDto::billTo),
        to(path(Invoice::customer, Customer::address, Address::city), InvoiceDto::city)
      );
      assertEquals(new InvoiceDto("ANN@X.IO", "Paris", 3), shorthand.forward(INVOICE));
      assertEquals(chain.forward(INVOICE), shorthand.forward(INVOICE));
      final var dto = new InvoiceDto("b@y", "Rome", 4);
      assertEquals(chain.backward(dto), shorthand.backward(dto));
      assertEquals(chain.patch(INVOICE, dto), shorthand.patch(INVOICE, dto));
      assertEquals(chain.explain(), shorthand.explain());
    }

    @Test
    @DisplayName("as the target of to(accessor, path), both ways and in patch")
    void asATarget() {
      final var chain = Telescope.mapper(
        Flat.class,
        Invoice.class,
        to(Flat::email, chainEmail),
        to(Flat::city, chainCity)
      );
      final var shorthand = Telescope.mapper(
        Flat.class,
        Invoice.class,
        to(Flat::email, path(Invoice::customer, Customer::email)),
        to(Flat::city, path(Invoice::customer, Customer::address, Address::city))
      );
      final var flat = new Flat("a@b", "Oslo", 2);
      assertEquals(new Invoice(new Customer(new Address("Oslo", 0), "a@b"), 2), shorthand.forward(flat));
      assertEquals(chain.forward(flat), shorthand.forward(flat));
      assertEquals(chain.backward(INVOICE), shorthand.backward(INVOICE));
      assertEquals(chain.patch(flat, INVOICE), shorthand.patch(flat, INVOICE));
      assertEquals(chain.explain(), shorthand.explain());
    }

    @Test
    @DisplayName("on both sides of to(path, path) and zip(path, path)")
    void onBothSides() {
      final var target = Telescope.of(Root.class).field(Root::a).field(L1::b).field(L2::c).field(L3::d).field(L4::v);
      final var chain = Telescope.mapperForward(Invoice.class, Root.class, to(chainEmail, target));
      final var shorthand = Telescope.mapperForward(
        Invoice.class,
        Root.class,
        to(path(Invoice::customer, Customer::email), path(Root::a, L1::b, L2::c, L3::d, L4::v))
      );
      assertEquals("ANN@X.IO", shorthand.forward(INVOICE).a().b().c().d().v());
      assertEquals(chain.forward(INVOICE), shorthand.forward(INVOICE));
      final var zipped = Telescope.mapperForward(
        Invoice.class,
        Root.class,
        zip(path(Invoice::customer, Customer::email), path(Root::a, L1::b, L2::c, L3::d, L4::v))
      );
      final var zippedChain = Telescope.mapperForward(Invoice.class, Root.class, zip(chainEmail, target));
      assertEquals(zippedChain.forward(INVOICE), zipped.forward(INVOICE));
    }

    @Test
    @DisplayName("as the target of constant and compute")
    void asAFilledTarget() {
      final var chain = Telescope.mapperForward(
        Flat.class,
        Invoice.class,
        constant(chainEmail, "fixed@x"),
        compute(chainCity, () -> "Made")
      );
      final var shorthand = Telescope.mapperForward(
        Flat.class,
        Invoice.class,
        constant(path(Invoice::customer, Customer::email), "fixed@x"),
        compute(path(Invoice::customer, Customer::address, Address::city), () -> "Made")
      );
      final var flat = new Flat("a@b", "Oslo", 2);
      final var built = shorthand.forward(flat);
      assertEquals("fixed@x", built.customer().email());
      assertEquals("Made", built.customer().address().city());
      assertEquals(chain.forward(flat), built);
    }
  }

  /**
   * A bean whose getter has an overload taking an argument, which leaves a bare path(...)
   * uninferable.
   */
  public static final class Overloaded {

    private final Customer customer;

    public Overloaded(final Customer customer) {
      this.customer = customer;
    }

    public Customer getCustomer() {
      return customer;
    }

    public Customer getCustomer(final int ignored) {
      return customer;
    }
  }

  /** An accessor that is a class of its own, not a method reference. */
  private static final class EmailOf implements Accessor<Customer, String> {

    private static final long serialVersionUID = 1L;

    @Override
    public String apply(final Customer customer) {
      return customer.email();
    }
  }

  @Nested
  @DisplayName("a path whose start cannot be read is refused by name")
  class Refusals {

    @Test
    @DisplayName("a lambda as the first accessor")
    void aLambda() {
      final var refused = assertThrows(IllegalArgumentException.class, () ->
        path((final Invoice i) -> i.customer(), Customer::email)
      );
      assertTrue(
        refused.getMessage().startsWith("Telescope.path(...) starts at the class its first accessor is applied to"),
        refused::getMessage
      );
      assertTrue(refused.getMessage().contains("not a lambda"), refused::getMessage);
    }

    @Test
    @DisplayName("a class implementing Accessor as the first accessor, which no SerializedLambda describes")
    void anAccessorClass() {
      final var refused = assertThrows(IllegalArgumentException.class, () -> path(new EmailOf(), String::length));
      assertTrue(
        refused.getMessage().startsWith("Telescope.path(...) starts at the class its first accessor is applied to"),
        refused::getMessage
      );
      assertTrue(refused.getMessage().contains("must be a method reference"), refused::getMessage);
    }

    @Test
    @DisplayName("a null accessor, named by its position")
    void aNullAccessor() {
      final var first = assertThrows(NullPointerException.class, () -> path(null, Customer::email));
      assertEquals("Telescope.path(...): accessor 1 is null", first.getMessage());
      final var third = assertThrows(NullPointerException.class, () ->
        path(Invoice::customer, Customer::address, (Accessor<Address, String>) null)
      );
      assertEquals("Telescope.path(...): accessor 3 is null", third.getMessage());
    }

    @Test
    @DisplayName("a lambda after the first accessor is refused as field(...) refuses it")
    void aLaterLambda() {
      final var refused = assertThrows(IllegalArgumentException.class, () ->
        path(Invoice::customer, (final Customer c) -> c.email())
      );
      final var chain = assertThrows(IllegalArgumentException.class, () ->
        Telescope.of(Invoice.class)
          .field(Invoice::customer)
          .field((final Customer c) -> c.email())
      );
      // The synthetic method each lambda compiles to is named after its own position in the class.
      assertEquals(chain.getMessage().replaceAll("Got: .*", ""), refused.getMessage().replaceAll("Got: .*", ""));
    }
  }
}
