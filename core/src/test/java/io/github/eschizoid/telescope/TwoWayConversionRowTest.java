package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.BridgeFn;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code to} row converting through one {@link BridgeFn}, given as an instance or as its class.
 */
class TwoWayConversionRowTest {

  record Contact(String email) {}

  record ContactDto(String address) {}

  static final AtomicInteger BUILT = new AtomicInteger();

  public static final class Lower implements BridgeFn<String, String> {

    public Lower() {
      BUILT.incrementAndGet();
    }

    @Override
    public String forward(final String s) {
      return s.toLowerCase();
    }

    @Override
    public String backward(final String s) {
      return s.toUpperCase();
    }
  }

  public static final class Unbuildable implements BridgeFn<String, String> {

    public Unbuildable(final String unused) {}

    @Override
    public String forward(final String s) {
      return s;
    }

    @Override
    public String backward(final String s) {
      return s;
    }
  }

  public static final class Throwing implements BridgeFn<String, String> {

    public Throwing() {
      throw new IllegalStateException("cannot be built");
    }

    @Override
    public String forward(final String s) {
      return s;
    }

    @Override
    public String backward(final String s) {
      return s;
    }
  }

  @Test
  @DisplayName(
    "a class whose constructor throws fails where the row is built, as the generated bridge fails where it is"
  )
  void aThrowingConstructorFailsWithTheRow() {
    final var thrown = assertThrows(IllegalStateException.class, () ->
      to(Contact::email, ContactDto::address, Throwing.class)
    );
    assertEquals("cannot be built", thrown.getMessage());
  }

  @Test
  @DisplayName("an instance converts forward through forward and backward through backward")
  void anInstanceConvertsBothWays() {
    final var mapper = Telescope.mapper(
      Contact.class,
      ContactDto.class,
      to(Contact::email, ContactDto::address, new Lower())
    );
    assertEquals(new ContactDto("a@b.com"), mapper.forward(new Contact("A@B.com")));
    assertEquals(new Contact("A@B.COM"), mapper.backward(new ContactDto("a@b.com")));
  }

  @Test
  @DisplayName("a class is built once, when the row is, and converts both ways")
  void aClassIsBuiltWithTheRow() {
    final var before = BUILT.get();
    final var row = to(Contact::email, ContactDto::address, Lower.class);
    assertEquals(before + 1, BUILT.get());
    final var mapper = Telescope.mapper(Contact.class, ContactDto.class, row);
    assertEquals(new ContactDto("x@y.com"), mapper.forward(new Contact("X@Y.com")));
    assertEquals(new Contact("X@Y.COM"), mapper.backward(new ContactDto("x@y.com")));
    assertEquals(before + 1, BUILT.get());
  }

  @Test
  @DisplayName("a class with no no-argument constructor is refused by name when the row is built")
  void aClassWithNoConstructorIsRefused() {
    final var thrown = assertThrows(IllegalArgumentException.class, () ->
      to(Contact::email, ContactDto::address, Unbuildable.class)
    );
    assertTrue(thrown.getMessage().contains("declares no constructor taking no arguments"), thrown.getMessage());
  }
}
