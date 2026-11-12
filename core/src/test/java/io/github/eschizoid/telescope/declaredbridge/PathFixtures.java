package io.github.eschizoid.telescope.declaredbridge;

import static io.github.eschizoid.telescope.Telescope.path;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.zip;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.BridgeFn;
import io.github.eschizoid.telescope.conversion.BridgeProvider;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.List;

/**
 * Stand-ins for what the processor writes for {@code @Bridge} fields whose rows are paths: a row
 * with a path on each side, a source property into a target path, and a source path into a target
 * property. The providers record the rows the bridge was generated from, named by the paths they
 * step through, which the runtime compares with the field's own rows.
 */
public final class PathFixtures {

  private PathFixtures() {}

  public record Mid(String name, int age) {}

  public record Src(Mid customer, String nick) {}

  public record Dock(String label, String nick) {}

  public record Tgt(Dock ship, String name) {}

  /** A bridge that writes only the name, so a test can tell it from the runtime mapper. */
  public static final Telescope<Src, Tgt> BRIDGE = Telescope.bridge(
    new BridgeFn<Src, Tgt>() {
      @Override
      public Tgt forward(final Src s) {
        return s == null ? null : new Tgt(null, "generated");
      }

      @Override
      public Src backward(final Tgt t) {
        return t == null ? null : new Src(null, "generated");
      }
    }
  );

  /** The class whose field's rows are the ones the bridge was generated from. */
  public static final class Declared {

    private Declared() {}

    public static final Mapper<Src, Tgt> PATHS = Telescope.mapper(
      Src.class,
      Tgt.class,
      to(path(Src::customer, Mid::name), path(Tgt::ship, Dock::label)),
      to(Src::nick, path(Tgt::ship, Dock::nick)),
      to(path(Src::customer, Mid::name), Tgt::name)
    );
  }

  /** A class whose first row zips the two paths rather than writing one through the other. */
  public static final class Zipped {

    private Zipped() {}

    public static final Mapper<Src, Tgt> PATHS = Telescope.mapper(
      Src.class,
      Tgt.class,
      zip(path(Src::customer, Mid::name), path(Tgt::ship, Dock::label)),
      to(Src::nick, path(Tgt::ship, Dock::nick)),
      to(path(Src::customer, Mid::name), Tgt::name)
    );
  }

  /**
   * The provider of a path field declared by a class, generated from the declaring class's rows.
   */
  private abstract static class Provider implements BridgeProvider {

    @Override
    public Class<?> sourceType() {
      return Src.class;
    }

    @Override
    public Class<?> targetType() {
      return Tgt.class;
    }

    @Override
    public List<String> rowKinds() {
      return List.of("TelescopeToTelescope", "TelescopeTo", "FromTelescopeTo");
    }

    @Override
    public List<String> rowAccessors() {
      return List.of("customer.name>ship.label", "nick>ship.nick", "customer.name>name");
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

  public static final class ZippedProvider extends Provider {

    @Override
    public Class<?> declaringType() {
      return Zipped.class;
    }
  }
}
