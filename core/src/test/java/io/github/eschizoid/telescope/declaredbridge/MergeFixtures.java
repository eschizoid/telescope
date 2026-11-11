package io.github.eschizoid.telescope.declaredbridge;

import static io.github.eschizoid.telescope.mapping.MergeStep.auto;
import static io.github.eschizoid.telescope.mapping.MergeStep.from;

import io.github.eschizoid.telescope.Sources;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.BridgeFn;
import io.github.eschizoid.telescope.conversion.BridgeProvider;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stand-ins for what the processor writes for a {@code @Bridge} field initialised by {@code
 * Telescope.merge}: a bridge from the bag of sources, and providers registered through {@code
 * META-INF/services} for the classes declaring the fields, one generated from the field's own rows
 * and two from rows the field no longer has. The bridge counts its calls, so a test can tell the
 * generated path from the runtime one.
 */
public final class MergeFixtures {

  private MergeFixtures() {}

  public record Customer(String id, String name) {}

  public record Audit(String by) {}

  public record Profile(String id, String name, String by) {}

  /** Calls into the stand-in bridge. */
  public static final AtomicInteger CALLS = new AtomicInteger();

  /** The bridge a merge field declares: the customer backfilled by name, the audit's {@code by}. */
  public static final Telescope<Sources, Profile> BRIDGE = Telescope.bridge(
    new BridgeFn<Sources, Profile>() {
      @Override
      public Profile forward(final Sources sources) {
        CALLS.incrementAndGet();
        final var customer = sources.byClass(Customer.class);
        if (customer == null) throw Telescope.mergeSourceMissing(sources.classes(), Customer.class, "id");
        final var audit = sources.byClass(Audit.class);
        if (audit == null) throw Telescope.mergeSourceMissing(sources.classes(), Audit.class, "by");
        return new Profile(customer.id(), customer.name(), audit.by());
      }

      @Override
      public Sources backward(final Profile target) {
        throw Telescope.mergeHasNoInverse();
      }
    }
  );

  /** The rows the bridge was generated from: {@code auto(Customer.class)}, then {@code from(Audit::by, ...)}. */
  private static final List<String> KINDS = List.of("AutoSameName", "FromInferred");

  private static final List<String> ACCESSORS = List.of(Customer.class.getName() + ">", "by>by");

  /** The class whose field's rows are the ones the bridge was generated from. */
  public static final class Declared {

    private Declared() {}

    public static final Mapper<Sources, Profile> PROFILE = Telescope.merge(
      Profile.class,
      auto(Customer.class),
      from(Audit::by, Profile::by)
    );
  }

  /** A class whose field backfills the audit too, which the bridge's rows did not. */
  public static final class OtherKinds {

    private OtherKinds() {}

    public static final Mapper<Sources, Profile> PROFILE = Telescope.merge(
      Profile.class,
      auto(Customer.class),
      auto(Audit.class)
    );
  }

  /** A class whose field's rows are of the same kinds as the bridge's, naming another accessor. */
  public static final class OtherAccessors {

    private OtherAccessors() {}

    public static final Mapper<Sources, Profile> PROFILE = Telescope.merge(
      Profile.class,
      auto(Customer.class),
      from(Customer::name, Profile::by)
    );
  }

  /** The provider of a merge field declared by {@code declaring}, generated from the bridge's rows. */
  private abstract static class Provider implements BridgeProvider {

    @Override
    public Class<?> sourceType() {
      return Sources.class;
    }

    @Override
    public Class<?> targetType() {
      return Profile.class;
    }

    @Override
    public List<String> rowKinds() {
      return KINDS;
    }

    @Override
    public List<String> rowAccessors() {
      return ACCESSORS;
    }

    @Override
    public Object bridge() {
      return BRIDGE;
    }
  }

  public static final class DeclaredProvider extends Provider {

    @Override
    public Class<?> declaringType() {
      return Declared.class;
    }
  }

  public static final class OtherKindsProvider extends Provider {

    @Override
    public Class<?> declaringType() {
      return OtherKinds.class;
    }
  }

  public static final class OtherAccessorsProvider extends Provider {

    @Override
    public Class<?> declaringType() {
      return OtherAccessors.class;
    }
  }
}
