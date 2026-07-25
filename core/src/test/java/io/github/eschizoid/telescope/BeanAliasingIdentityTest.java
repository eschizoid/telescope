package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the documented aliasing contract of bean updates: a persistent-style rebuild, not a deep
 * clone. The root and every object on the modified path are fresh instances; untouched sibling
 * subtrees — including mutable collections — are shared by reference between old and new. The
 * original is never mutated, on or off the modified path.
 */
class BeanAliasingIdentityTest {

  public static class Address {

    private String city;

    public Address() {}

    public String getCity() {
      return city;
    }

    public void setCity(final String city) {
      this.city = city;
    }
  }

  public static class Profile {

    private List<String> tags;

    public Profile() {}

    public List<String> getTags() {
      return tags;
    }

    public void setTags(final List<String> tags) {
      this.tags = tags;
    }
  }

  public static class User {

    private String name;
    private Address address;
    private Profile profile;

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

    public Profile getProfile() {
      return profile;
    }

    public void setProfile(final Profile profile) {
      this.profile = profile;
    }
  }

  private static User sample() {
    final var address = new Address();
    address.setCity("nyc");
    final var profile = new Profile();
    profile.setTags(new ArrayList<>(List.of("a", "b")));
    final var user = new User();
    user.setName("Ann");
    user.setAddress(address);
    user.setProfile(profile);
    return user;
  }

  @Test
  @DisplayName("the root and every object on the modified path are fresh; the original keeps its values")
  void modifiedPathIsFresh() {
    final var user = sample();
    final var moved = Telescope.ofBean(User.class)
      .field(User::getAddress)
      .field(Address::getCity)
      .update(user, String::toUpperCase);

    assertNotSame(user, moved, "root is a new instance");
    assertNotSame(user.getAddress(), moved.getAddress(), "the modified-path Address is a new instance");
    assertEquals("NYC", moved.getAddress().getCity());
    assertEquals("nyc", user.getAddress().getCity(), "the original Address is not mutated");
    assertEquals("Ann", user.getName(), "the original root is not mutated");
  }

  @Test
  @DisplayName("untouched sibling subtrees are SHARED by reference — persistent update, not deep clone")
  void untouchedSiblingsAreShared() {
    final var user = sample();
    final var moved = Telescope.ofBean(User.class)
      .field(User::getAddress)
      .field(Address::getCity)
      .update(user, String::toUpperCase);

    // The Profile sibling was not on the modified path: same instance on both roots — the
    // documented aliasing. Mutating it later would be visible through BOTH roots; that is the
    // contract the docs state, pinned here so it cannot drift silently.
    assertSame(user.getProfile(), moved.getProfile(), "off-path sibling is shared by reference");
    assertSame(user.getProfile().getTags(), moved.getProfile().getTags(), "mutable collection inside it too");
  }

  @Test
  @DisplayName("a scalar sibling on the rebuilt level is copied by value; the rebuilt bean is independent")
  void rebuiltLevelCopiesScalars() {
    final var user = sample();
    final var renamed = Telescope.ofBean(User.class).field(User::getName).update(user, String::toUpperCase);

    assertEquals("ANN", renamed.getName());
    assertEquals("Ann", user.getName());
    // the untouched Address/Profile hang off the new root as the same instances:
    assertSame(user.getAddress(), renamed.getAddress());
    assertSame(user.getProfile(), renamed.getProfile());
  }
}
