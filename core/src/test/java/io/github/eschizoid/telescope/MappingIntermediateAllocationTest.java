package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for intermediate target allocation — closes the v1.0 limitation that previously
 * documented in {@code Mapping.to(Accessor, Telescope)}'s javadoc.
 *
 * <p>When a flat source field is routed through a multi-hop target telescope, the engine now
 * synthesizes a recursive default-tree instance of the intermediate at the first hop (and every
 * subsequent record-typed hop) instead of registering a {@code null} placeholder. The post-fixup
 * overlay then descends into the allocated structure and writes the leaf — no per-hop allocation
 * glue from the user, no fall-back to {@code Mapping.via(...)}.
 *
 * <p>Both records and beans are supported. Records recurse through their canonical constructors
 * with default component values at every hop. Beans are allocated from either their public no-arg
 * constructor or, when no no-arg ctor is available, a static {@code builder()} method whose
 * resulting builder exposes a no-arg {@code build()} — the Lombok {@code @Builder} / Immutables
 * shape. The resulting instance has default-initialised fields that the subsequent telescope-row
 * write overwrites via the bean's setters. Beans without either construction path fall back to
 * {@code null}.
 */
class MappingIntermediateAllocationTest {

  // --- One-hop intermediate (the original v1.0 wart) ---
  record OneHopAddress(String city, String zip) {}

  record OneHopPerson(String name, int age, OneHopAddress address) {}

  // --- Multi-hop intermediate (the case the type-system-driven allocator handles for free) ---
  record Geocode(double lat, double lon) {}

  record DeepAddress(String city, String zip, Geocode geocode) {}

  record DeepPerson(String name, int age, DeepAddress address) {}

  // --- Even deeper, for the "type-driven recursion" claim ---
  record Inner(String value) {}

  record Mid(Inner inner) {}

  record Outer(Mid mid) {}

  record DeepTarget(Outer outer) {}

  @Nested
  @DisplayName("one-hop intermediate — Slim source → Person target with allocated Address")
  class OneHopAllocation {

    @Test
    @DisplayName("flat source with no `address` field still routes through Person.address.city")
    void flatSourceRoutesIntoAllocatedAddress() {
      record Slim(String displayCity) {}

      final var mapper = Telescope.mapper(
        Slim.class,
        OneHopPerson.class,
        to(Slim::displayCity, Telescope.of(OneHopPerson.class).field(OneHopPerson::address).field(OneHopAddress::city))
      );

      final var out = mapper.forward(new Slim("Brooklyn"));
      assertNotNull(out.address(), "intermediate Address should be allocated, not null");
      assertEquals("Brooklyn", out.address().city());
      // Primitive default; default for non-claimed reference fields is null.
      assertEquals(0, out.age());
      assertNull(out.address().zip());
    }
  }

  @Nested
  @DisplayName("multi-hop intermediate — recursive default-tree handles arbitrary depth")
  class MultiHopAllocation {

    @Test
    @DisplayName("two-hop nested target (address.geocode.lat) from a flat source")
    void twoHopNestedTarget() {
      record Slim(double latitude) {}

      final var mapper = Telescope.mapper(
        Slim.class,
        DeepPerson.class,
        to(
          Slim::latitude,
          Telescope.of(DeepPerson.class).field(DeepPerson::address).field(DeepAddress::geocode).field(Geocode::lat)
        )
      );

      final var out = mapper.forward(new Slim(40.6782));
      assertNotNull(out.address(), "address should be allocated");
      assertNotNull(out.address().geocode(), "address.geocode should be allocated (recursive)");
      assertEquals(40.6782, out.address().geocode().lat());
      assertEquals(0.0, out.address().geocode().lon()); // primitive default
    }

    @Test
    @DisplayName("three-hop nested target (outer.mid.inner.value) — recursion depth is type-driven, not capped")
    void threeHopNestedTarget() {
      record Slim(String value) {}

      final var mapper = Telescope.mapper(
        Slim.class,
        DeepTarget.class,
        to(
          Slim::value,
          Telescope.of(DeepTarget.class)
            .field(DeepTarget::outer)
            .field(Outer::mid)
            .field(Mid::inner)
            .field(Inner::value)
        )
      );

      final var out = mapper.forward(new Slim("payload"));
      assertNotNull(out.outer());
      assertNotNull(out.outer().mid());
      assertNotNull(out.outer().mid().inner());
      assertEquals("payload", out.outer().mid().inner().value());
    }
  }

  @Nested
  @DisplayName("bean-intermediate allocation — synthesises a fresh bean from its no-arg ctor")
  class BeanIntermediate {

    static class AddressBean {

      private String city;
      private String zip;

      public AddressBean() {}

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

    static class PersonBean {

      private String name;
      private AddressBean address;

      public PersonBean() {}

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }

      public AddressBean getAddress() {
        return address;
      }

      public void setAddress(final AddressBean address) {
        this.address = address;
      }
    }

    // A bean whose only construction path is a static builder() — the no-arg ctor is
    // private. Mirrors the Lombok @Builder / Immutables shape: callers go through
    // Type.builder().build() to get a default-initialised instance.
    static final class BuilderOnlyAddress {

      private String city;

      private BuilderOnlyAddress() {}

      public static Builder builder() {
        return new Builder();
      }

      public String getCity() {
        return city;
      }

      public void setCity(final String city) {
        this.city = city;
      }

      public static final class Builder {

        private String city;

        public Builder city(final String city) {
          this.city = city;
          return this;
        }

        public BuilderOnlyAddress build() {
          final var out = new BuilderOnlyAddress();
          out.city = city;
          return out;
        }
      }
    }

    static class BuilderHolder {

      private BuilderOnlyAddress address;

      public BuilderHolder() {}

      public BuilderOnlyAddress getAddress() {
        return address;
      }

      public void setAddress(final BuilderOnlyAddress address) {
        this.address = address;
      }
    }

    @Test
    @DisplayName(
      "builder-only bean intermediate — allocator falls back to type.builder().build() when no public no-arg ctor"
    )
    void builderOnlyBeanIntermediate() {
      record Slim(String displayCity) {}

      final var mapper = Telescope.mapper(
        Slim.class,
        BuilderHolder.class,
        to(
          Slim::displayCity,
          Telescope.ofBean(BuilderHolder.class).field(BuilderHolder::getAddress).field(BuilderOnlyAddress::getCity)
        )
      );

      final var out = mapper.forward(new Slim("Brooklyn"));
      assertNotNull(out.getAddress(), "builder-only BuilderOnlyAddress should be allocated via builder().build()");
      assertEquals("Brooklyn", out.getAddress().getCity());
    }

    @Test
    @DisplayName("flat source routes into a freshly-allocated bean intermediate at the telescope-targeted slot")
    void flatSourceRoutesIntoAllocatedBean() {
      record Slim(String displayCity) {}

      final var mapper = Telescope.mapper(
        Slim.class,
        PersonBean.class,
        to(
          Slim::displayCity,
          Telescope.ofBean(PersonBean.class).field(PersonBean::getAddress).field(AddressBean::getCity)
        )
      );

      final var out = mapper.forward(new Slim("Brooklyn"));
      assertNotNull(out.getAddress(), "bean intermediate AddressBean should be allocated from its no-arg ctor");
      assertEquals("Brooklyn", out.getAddress().getCity());
      assertNull(out.getAddress().getZip());
    }
  }
}
