package io.github.eschizoid.telescope.jackson;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The records and beans the binder tests read into. */
public final class Fixtures {

  private Fixtures() {}

  /** An order status. */
  public enum Status {
    PLACED,
    PAID,
  }

  /** One line of an order. */
  public record Line(String sku, int quantity, BigDecimal price) {}

  /** An order holding every kind of property the binder reads. */
  public record Order(
    String number,
    List<Line> lines,
    Status status,
    Optional<String> note,
    Map<String, Integer> counts,
    Set<String> tags,
    Instant placedOn,
    boolean gift,
    long version,
    double weight,
    char grade,
    BigInteger serial,
    Object extra
  ) {}

  /** One property of each scalar kind. */
  public record Scalars(
    int i,
    long l,
    short s,
    byte b,
    double d,
    float f,
    boolean z,
    char c,
    Integer boxed,
    BigDecimal decimal,
    BigInteger big,
    String text,
    CharSequence chars,
    UUID id
  ) {}

  /** A type that holds itself. */
  public record Tree(String name, List<Tree> children) {}

  /** Maps keyed by each kind of key a JSON object's text can be read as. */
  public record Keyed(Map<Status, String> byStatus, Map<UUID, Integer> byId, Map<Integer, String> byNumber) {}

  /** Containers whose elements are containers or optional. */
  public record Nesting(List<List<Integer>> grid, List<Optional<String>> maybe, Map<String, List<String>> groups) {}

  /** A record with an id at two levels, to show each level has its own keys. */
  public record Outer(String id, Inner inner) {}

  /** The inner level of {@link Outer}. */
  public record Inner(String id) {}

  /** A record whose JSON keys differ from its component names. */
  public record Booking(String bookingType, int priority, Address address) {}

  /** An address held by {@link Booking}. */
  public record Address(String street, String zip) {}

  /** Two components a row can point at the same key. */
  public record Pair(String left, String right) {}

  /** A type variable, which no JSON value can be read into. */
  public record Holder<T>(T value) {}

  /** A collection subtype, which the binder refuses as {@code fromMap} does. */
  public record Listy(ArrayList<String> values) {}

  /** A JDK type with no reading from JSON. */
  public record WithThread(Thread thread) {}

  /** An interface property, which nothing can build. */
  public record WithShape(Shape shape) {}

  /** An interface. */
  public interface Shape {}

  /** A map whose key type is not read from text. */
  public record BadKeys(Map<Line, String> byLine) {}

  /** A bean built through its no-argument constructor and setters. */
  public static final class Customer {

    private String name;
    private int visits;
    private List<String> tags;

    /** Creates an empty customer. */
    public Customer() {}

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public int getVisits() {
      return visits;
    }

    public void setVisits(final int visits) {
      this.visits = visits;
    }

    public List<String> getTags() {
      return tags;
    }

    public void setTags(final List<String> tags) {
      this.tags = tags;
    }

    /** A derived value with no setter, which the binder never writes. */
    public String getLabel() {
      return name + "#" + visits;
    }
  }
}
