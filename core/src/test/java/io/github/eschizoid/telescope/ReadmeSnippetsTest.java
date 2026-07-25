package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.constant;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.Mapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The README's primary snippets, compiled and executed. If a README example stops compiling or its
 * shown output drifts from reality, this file fails before a reader does. Keep each test's shape
 * aligned with the README section it pins — the point is that the docs are runnable, not merely
 * plausible.
 */
class ReadmeSnippetsTest {

  @Nested
  @DisplayName("Quick start — the complete pasteable example")
  class QuickStart {

    record Address(String city, String zip) {}

    record User(String name, Address address) {}

    @Test
    @DisplayName("build a typed path once; read and update through it")
    void quickStart() {
      final var userCity = Telescope.of(User.class).field(User::address).field(Address::city);

      final var alice = new User("Alice", new Address("Springfield", "49007"));

      final var city = userCity.read(alice);
      final var shouted = userCity.update(alice, String::toUpperCase);

      assertEquals("Springfield", city);
      assertEquals("SPRINGFIELD", shouted.address().city());
      assertEquals("Springfield", alice.address().city()); // alice is untouched
    }
  }

  @Nested
  @DisplayName("The tour — records")
  class TourRecords {

    record Address(String city, String zip) {}

    record User(String name, int age, String email, Address address) {}

    record Team(String name, List<User> users) {}

    record Department(String name, List<Team> teams) {}

    record Company(String name, List<Department> departments) {}

    static final Company COMPANY = new Company(
      "acme",
      List.of(
        new Department(
          "eng",
          List.of(new Team("core", List.of(new User("Ann", 30, "ANN@ACME.io", new Address("nyc", "10001")))))
        )
      )
    );

    @Test
    @DisplayName("one reusable path lowercases every email in the tree, and reads through it too")
    void deepUpdateAndReads() {
      final var emails = Telescope.of(Company.class)
        .each(Company::departments)
        .each(Department::teams)
        .each(Team::users)
        .field(User::email);

      final var lowered = emails.update(COMPANY, String::toLowerCase);
      assertEquals("ann@acme.io", lowered.departments().get(0).teams().get(0).users().get(0).email());

      assertEquals(List.of("ANN@ACME.io"), emails.toList(COMPANY));
      assertEquals(1L, emails.count(COMPANY));
    }
  }

  @Nested
  @DisplayName("The tour — mapping")
  class TourMapping {

    record Address(String city, String zip) {}

    record User(String name, int age, String email, Address address) {}

    record Company(String name, List<User> users) {}

    record AddressDto(String town, String postalCode) {}

    record UserDto(String fullName, int age, String email, AddressDto address) {}

    record CompanyDto(String name, List<UserDto> users) {}

    @Test
    @DisplayName("renames apply recursively; the same row list runs backward")
    void mapperForwardAndBackward() {
      final Mapper<Company, CompanyDto> dtoMapper = Telescope.mapper(
        Company.class,
        CompanyDto.class,
        to(User::name, UserDto::fullName),
        to(Address::city, AddressDto::town),
        to(Address::zip, AddressDto::postalCode)
      );

      final var company = new Company("acme", List.of(new User("Ann", 30, "ann@acme.io", new Address("nyc", "10001"))));

      final var dto = dtoMapper.forward(company);
      assertEquals("Ann", dto.users().get(0).fullName());
      assertEquals("nyc", dto.users().get(0).address().town());

      final var restored = dtoMapper.backward(dto);
      assertEquals(company, restored);

      assertNotNull(dtoMapper.explain());
    }

    record Order(String id) {}

    record OrderDto(String id, String tenant, Instant createdAt) {}

    @Test
    @DisplayName("constant stamps once, compute stamps per call; both forward-only")
    void constantAndCompute() {
      final var mapper = Telescope.mapper(
        Order.class,
        OrderDto.class,
        to(Order::id, OrderDto::id),
        constant(OrderDto::tenant, "production"),
        compute(OrderDto::createdAt, Instant::now)
      );

      final var dto = mapper.forward(new Order("o-1"));
      assertEquals("production", dto.tenant());
      assertNotNull(dto.createdAt());

      final var back = mapper.backward(dto); // forward-only slots leave the source rebuild
      assertEquals("o-1", back.id());
    }
  }

  @Nested
  @DisplayName("The tour — beans")
  class TourBeans {

    public static class Address {

      private String city;
      private String zip;

      public Address() {}

      public String getCity() {
        return city;
      }

      public void setCity(final String city) {
        this.city = city;
      }

      public String getZip() {
        return zip;
      }

      public void setZip(final String zip) {
        this.zip = zip;
      }
    }

    public static class User {

      private String name;
      private Address address;

      public User() {}

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }

      public Address getAddress() {
        return address;
      }

      public void setAddress(final Address address) {
        this.address = address;
      }
    }

    @Test
    @DisplayName("ofBean updates build a new root; the original is never mutated")
    void beanUpdateLeavesOriginalUntouched() {
      final var address = new Address();
      address.setCity("springfield");
      final var user = new User();
      user.setName("Ann");
      user.setAddress(address);

      final var moved = Telescope.ofBean(User.class)
        .field(User::getAddress)
        .field(Address::getCity)
        .update(user, String::toUpperCase);

      assertEquals("SPRINGFIELD", moved.getAddress().getCity());
      assertEquals("springfield", user.getAddress().getCity()); // original untouched
      assertTrue(moved != user);
    }
  }
}
