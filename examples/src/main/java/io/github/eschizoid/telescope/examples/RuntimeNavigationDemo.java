package io.github.eschizoid.telescope.examples;

import io.github.eschizoid.telescope.Telescope;

/**
 * Exercises the reflection-backed entry points: {@code Telescope.of(Class)} for records, {@code
 * Telescope.ofBean(Class)} for POJOs, and the two {@code fieldByName} runtime escape hatches.
 */
final class RuntimeNavigationDemo {

  private RuntimeNavigationDemo() {}

  // ---------- record fixtures ----------

  record Address(String city, String zip) {}

  record User(String name, int age, String email, Address address) {}

  // ---------- bean fixture ----------

  static final class UserBean {

    private String name;
    private String email;

    public UserBean() {}

    public UserBean(final String name, final String email) {
      this.name = name;
      this.email = email;
    }

    public String getName() {
      return name;
    }

    public String getEmail() {
      return email;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public void setEmail(final String email) {
      this.email = email;
    }

    @Override
    public String toString() {
      return "UserBean[name=" + name + ", email=" + email + "]";
    }
  }

  static void run() {
    recordFieldUpdate();
    nestedRecordFieldUpdate();
    beanFieldUpdate();
    fieldByNameRuntimePath();
    fieldByNameWithTypeWitness();
  }

  // Telescope.of(Class) + .field(Accessor) + .read / .update / .set on a record.
  private static void recordFieldUpdate() {
    final var namePath = Telescope.of(User.class).field(User::name);
    final var alice = new User("alice", 30, "alice@acme.com", new Address("NYC", "10001"));

    System.out.println("[of/field] read name        : " + namePath.read(alice));

    final var loud = namePath.update(alice, String::toUpperCase);
    System.out.println("[of/field] update -> upper  : " + loud);

    final var renamed = namePath.set(alice, "BOB");
    System.out.println("[of/field] set 'BOB'        : " + renamed);
  }

  // Two .field(Accessor) calls chain into nested records.
  private static void nestedRecordFieldUpdate() {
    final var cityPath = Telescope.of(User.class).field(User::address).field(Address::city);
    final var alice = new User("alice", 30, "alice@acme.com", new Address("NYC", "10001"));
    final var moved = cityPath.set(alice, "BOSTON");
    System.out.println("[of/field/field] move city  : " + moved);
  }

  // Telescope.ofBean(Class) + .field(getter) on a POJO. set/update rebuild via the no-arg
  // constructor + setters strategy auto-detected from the bean's shape.
  private static void beanFieldUpdate() {
    final var emailPath = Telescope.ofBean(UserBean.class).field(UserBean::getEmail);
    final var before = new UserBean("Alice", "ALICE@ACME.COM");
    System.out.println("[ofBean] before              : " + before);

    final var lowered = emailPath.update(before, String::toLowerCase);
    System.out.println("[ofBean] update -> lower     : " + lowered);
    System.out.println("[ofBean] original untouched  : " + before);
  }

  // .fieldByName(String) — runtime escape hatch when the field name is data, not code.
  private static void fieldByNameRuntimePath() {
    final var name = Telescope.of(User.class).<String>fieldByName("name");
    final var alice = new User("alice", 30, "a@x", new Address("NYC", "10001"));
    System.out.println("[fieldByName] read name      : " + name.read(alice));

    final var loud = name.update(alice, String::toUpperCase);
    System.out.println("[fieldByName] update -> upper: " + loud);
  }

  // .fieldByName(String, Class<B>) — same runtime path, with an inline type witness so the
  // var-typed local infers correctly. (The Class<B> is inference sugar — it is NOT validated.)
  private static void fieldByNameWithTypeWitness() {
    final var emailPath = Telescope.of(User.class).fieldByName("email", String.class);
    final var alice = new User("alice", 30, "ALICE@ACME.COM", new Address("NYC", "10001"));
    final var lowered = emailPath.update(alice, String::toLowerCase);
    System.out.println("[fieldByName(typed)] lower   : " + lowered);
  }
}
