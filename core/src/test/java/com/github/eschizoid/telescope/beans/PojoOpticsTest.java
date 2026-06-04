package com.github.eschizoid.telescope.beans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.eschizoid.telescope.Telescope;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Native POJO navigation ({@code ofBean}), POJO&harr;POJO conversion ({@code mapBean}), and
 * nested-collection bridges.
 */
class PojoOpticsTest {

  static final class AddrBean {

    private String city;
    private String zip;

    public AddrBean() {}

    public String getCity() {
      return city;
    }

    public String getZip() {
      return zip;
    }

    public void setCity(final String city) {
      this.city = city;
    }

    public void setZip(final String zip) {
      this.zip = zip;
    }
  }

  static final class UserBean {

    private String name;
    private AddrBean address;
    private List<String> tags;

    public UserBean() {}

    public String getName() {
      return name;
    }

    public AddrBean getAddress() {
      return address;
    }

    public List<String> getTags() {
      return tags;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public void setAddress(final AddrBean address) {
      this.address = address;
    }

    public void setTags(final List<String> tags) {
      this.tags = tags;
    }
  }

  private static UserBean user() {
    final var addr = new AddrBean();
    addr.setCity("nyc");
    addr.setZip("10001");
    final var u = new UserBean();
    u.setName("alice");
    u.setAddress(addr);
    u.setTags(List.of("x", "y"));
    return u;
  }

  @Nested
  @DisplayName("ofBean — native POJO navigation")
  class OfBean {

    @Test
    @DisplayName("shallow field update rebuilds immutably (original untouched)")
    void shallow() {
      final var before = user();
      final var after = Telescope.ofBean(UserBean.class).field(UserBean::getName).update(before, String::toUpperCase);
      assertEquals("ALICE", after.getName());
      assertEquals("alice", before.getName());
    }

    @Test
    @DisplayName("deep field path rebuilds at each level, preserving siblings")
    void deep() {
      final var after = Telescope.ofBean(UserBean.class)
        .field(UserBean::getAddress)
        .field(AddrBean::getCity)
        .update(user(), String::toUpperCase);
      assertEquals("NYC", after.getAddress().getCity());
      assertEquals("10001", after.getAddress().getZip());
      assertEquals("alice", after.getName());
    }

    @Test
    @DisplayName("each over a collection property")
    void eachCollection() {
      final var after = Telescope.ofBean(UserBean.class).each(UserBean::getTags).update(user(), String::toUpperCase);
      assertEquals(List.of("X", "Y"), after.getTags());
    }
  }

  static final class PersonA {

    private String id;
    private String name;

    public PersonA() {}

    public String getId() {
      return id;
    }

    public String getName() {
      return name;
    }

    public void setId(final String id) {
      this.id = id;
    }

    public void setName(final String name) {
      this.name = name;
    }
  }

  static final class PersonB {

    private String id;
    private String name;

    public PersonB() {}

    public String getId() {
      return id;
    }

    public String getName() {
      return name;
    }

    public void setId(final String id) {
      this.id = id;
    }

    public void setName(final String name) {
      this.name = name;
    }
  }

  static final class PersonView {

    private String id;
    private String fullName;
    private String role;

    public PersonView() {}

    public String getId() {
      return id;
    }

    public String getFullName() {
      return fullName;
    }

    public String getRole() {
      return role;
    }

    public void setId(final String id) {
      this.id = id;
    }

    public void setFullName(final String fullName) {
      this.fullName = fullName;
    }

    public void setRole(final String role) {
      this.role = role;
    }
  }

  @Nested
  @DisplayName("mapBean — POJO to POJO")
  class MapBean {

    @Test
    @DisplayName("forward + backward round-trip")
    void roundTrip() {
      final var a = new PersonA();
      a.setId("u1");
      a.setName("Alice");

      final var bridge = Telescope.mapBean(PersonA.class).to(PersonB.class).build();
      final var b = bridge.read(a);
      assertEquals("u1", b.getId());
      assertEquals("Alice", b.getName());

      final var backA = bridge.set(a, b);
      assertEquals("u1", backA.getId());
      assertEquals("Alice", backA.getName());
    }

    @Test
    @DisplayName("rename maps a differently-named property; ignoreUnmatched drops the rest")
    void renameAndIgnore() {
      final var a = new PersonA();
      a.setId("u1");
      a.setName("Alice");

      final var bridge = Telescope.mapBean(PersonA.class)
        .to(PersonView.class)
        .rename(PersonA::getName, PersonView::getFullName) // name <-> fullName
        .ignoreUnmatched() // PersonView.role has no PersonA counterpart
        .build();

      final var view = bridge.read(a);
      assertEquals("u1", view.getId());
      assertEquals("Alice", view.getFullName());
      assertNull(view.getRole());

      final var backA = bridge.set(a, view);
      assertEquals("Alice", backA.getName());
    }
  }

  static final class OrderPojo {

    private final String sku;

    public OrderPojo(final String sku) {
      this.sku = sku;
    }

    public String getSku() {
      return sku;
    }
  }

  record OrderRecord(String sku) {}

  static final class CartPojo {

    private List<OrderPojo> orders;

    public CartPojo() {}

    public List<OrderPojo> getOrders() {
      return orders;
    }

    public void setOrders(final List<OrderPojo> orders) {
      this.orders = orders;
    }
  }

  record CartRecord(List<OrderRecord> orders) {}

  static final class ContainerBean {

    private java.util.Map<String, String> labels;
    private java.util.Optional<String> note;

    public ContainerBean() {}

    public java.util.Map<String, String> getLabels() {
      return labels;
    }

    public java.util.Optional<String> getNote() {
      return note;
    }

    public void setLabels(final java.util.Map<String, String> labels) {
      this.labels = labels;
    }

    public void setNote(final java.util.Optional<String> note) {
      this.note = note;
    }
  }

  @Nested
  @DisplayName("ofBean — eachValue / whenPresent on a bean")
  class BeanModeContainers {

    @Test
    @DisplayName("eachValue updates a Map property's values, keys preserved")
    void eachValue() {
      final var bean = new ContainerBean();
      bean.setLabels(java.util.Map.of("a", "x", "b", "y"));
      bean.setNote(java.util.Optional.of("hi"));

      final var after = Telescope.ofBean(ContainerBean.class)
        .eachValue(ContainerBean::getLabels)
        .update(bean, String::toUpperCase);

      assertEquals(java.util.Map.of("a", "X", "b", "Y"), after.getLabels());
      assertEquals(java.util.Optional.of("hi"), after.getNote());
    }

    @Test
    @DisplayName("whenPresent updates an Optional property when present")
    void whenPresent() {
      final var bean = new ContainerBean();
      bean.setLabels(java.util.Map.of());
      bean.setNote(java.util.Optional.of("hi"));

      final var after = Telescope.ofBean(ContainerBean.class)
        .whenPresent(ContainerBean::getNote)
        .update(bean, String::toUpperCase);

      assertEquals(java.util.Optional.of("HI"), after.getNote());
    }
  }

  record SwapRecord(String first, String second) {}

  // All-args constructor whose parameters are in the OPPOSITE order to the record components,
  // both String — the classic positional footgun. With -parameters, name matching keeps it correct.
  static final class SwapPojo {

    private final String first;
    private final String second;

    public SwapPojo(final String second, final String first) {
      this.first = first;
      this.second = second;
    }

    public String getFirst() {
      return first;
    }

    public String getSecond() {
      return second;
    }
  }

  @Nested
  @DisplayName("viaConstructor — name matching survives a reordered constructor")
  class ViaConstructorNameMatching {

    @Test
    @DisplayName("a constructor with reversed parameter order still maps by name, not position")
    void reorderedCtor() {
      final var bridge = Telescope.fromBean(SwapPojo.class).to(SwapRecord.class).viaConstructor();
      final var pojo = new SwapPojo("SECOND", "FIRST"); // ctor(second, first): first="FIRST", second="SECOND"
      final var back = bridge.set(pojo, new SwapRecord("FIRST", "SECOND"));
      assertEquals("FIRST", back.getFirst());
      assertEquals("SECOND", back.getSecond());
    }
  }

  // Record component 'displayName' has no same-named getter on the POJO; rename maps it to 'name'.
  record AccountRecord(String id, String displayName) {}

  static final class AccountBean {

    private String id;
    private String name;

    public AccountBean() {}

    public String getId() {
      return id;
    }

    public String getName() {
      return name;
    }

    public void setId(final String id) {
      this.id = id;
    }

    public void setName(final String name) {
      this.name = name;
    }
  }

  @Nested
  @DisplayName("fromBean — rename a record component to a differently-named POJO property")
  class FromBeanRename {

    @Test
    @DisplayName("rename maps component <-> property both ways")
    void renameBothWays() {
      final var bridge = Telescope.fromBean(AccountBean.class)
        .to(AccountRecord.class)
        .rename(AccountRecord::displayName, AccountBean::getName)
        .viaFields();

      final var bean = new AccountBean();
      bean.setId("a1");
      bean.setName("Alice");

      final var rec = bridge.read(bean);
      assertEquals("a1", rec.id());
      assertEquals("Alice", rec.displayName());

      final var back = bridge.set(bean, new AccountRecord("a2", "Bob"));
      assertEquals("a2", back.getId());
      assertEquals("Bob", back.getName());
    }
  }

  @Nested
  @DisplayName("nested collections — viaEach element bridge")
  class NestedCollections {

    @Test
    @DisplayName("List<Pojo> <-> List<Record> converts element-wise both ways")
    void elementWise() {
      final var orderBridge = Telescope.fromBean(OrderPojo.class).to(OrderRecord.class).viaConstructor();
      final var cartBridge = Telescope.fromBean(CartPojo.class)
        .to(CartRecord.class)
        .viaEach(CartRecord::orders, orderBridge)
        .viaFields();

      final var cart = new CartPojo();
      cart.setOrders(List.of(new OrderPojo("A"), new OrderPojo("B")));

      final var rec = cartBridge.read(cart);
      assertEquals(new CartRecord(List.of(new OrderRecord("A"), new OrderRecord("B"))), rec);
      assertInstanceOf(OrderRecord.class, rec.orders().get(0));

      final var back = cartBridge.set(cart, rec);
      assertEquals("A", back.getOrders().get(0).getSku());
      assertInstanceOf(OrderPojo.class, back.getOrders().get(0));
    }
  }
}
