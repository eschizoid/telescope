package io.github.eschizoid.telescope.containerparity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A container component whose declared type carries no type arguments never yields a container
 * view, so it never reaches the allocators the deep-map route uses. Its only route is the raw
 * same-kind element copy, which resolves its allocator separately — and a type one route can build
 * and the other cannot is the same parity gap, on a route the parity gate does not reach.
 */
class CopyRouteAllocatorTest {

  /** A raw subclass of a {@code java.base} container, which is how adopters name one. */
  public static final class MyProps extends Properties {

    private static final long serialVersionUID = 1L;

    public MyProps() {}
  }

  public static final class RawA extends ArrayList<String> {

    private static final long serialVersionUID = 1L;

    public RawA() {}
  }

  public static final class RawB extends ArrayList<String> {

    private static final long serialVersionUID = 1L;

    public RawB() {}
  }

  /** Abstract, with a public no-argument constructor — binds, then cannot be instantiated. */
  public abstract static class AbsRaw extends ArrayList<String> {

    private static final long serialVersionUID = 1L;

    public AbsRaw() {}
  }

  record PropsSrc(Properties props) {}

  record MyPropsDst(MyProps props) {}

  record ASrc(RawA items) {}

  record BDst(RawB items) {}

  record AbsDst(AbsRaw items) {}

  private static RawA oneElement() {
    final var a = new RawA();
    a.add("x");
    return a;
  }

  @Test
  @DisplayName("a component declared as a java.base container copies into an adopter's subclass of it")
  void javaBaseSourceCopiesIntoAdopterSubclass() {
    // The allocator behind this route binds constructors through privateLookupIn, which java.base
    // refuses. The refusal surfaced as advice to open java.util — a module the adopter does not
    // control, for a constructor that was public and callable the whole time.
    final var src = new Properties();
    src.setProperty("k", "v");

    final var out = Telescope.mapper(PropsSrc.class, MyPropsDst.class).forward(new PropsSrc(src));

    assertEquals("v", out.props().get("k"), "the entries come across");
    assertInstanceOf(MyProps.class, out.props(), "and land in the declared class");
  }

  @Test
  @DisplayName("a target that cannot be instantiated is refused as a value, not raised as a linkage error")
  void abstractTargetIsRefusedRatherThanRaisingLinkageError() {
    // An abstract class's constructor binds like any other, so the allocator builds and raises
    // InstantiationError the first time it is called. That is an Error rather than an exception,
    // so it travels straight through the fail-fast registries both starters build at startup --
    // they catch a refusal and report which mapper is unbuildable, and an Error is neither.
    final var thrown = assertThrows(RuntimeException.class, () ->
      Telescope.mapper(ASrc.class, AbsDst.class).forward(new ASrc(oneElement()))
    );

    assertTrue(
      thrown.getMessage().contains("AbsRaw"),
      () -> "the refusal should name the type that cannot be built: " + thrown.getMessage()
    );
  }

  @Test
  @DisplayName("two concrete subclasses of the same container still copy, as they always did")
  void concreteSubclassesStillCopy() {
    // The control. Both rows above changed behaviour, so without a pair that was working before and
    // works after, a change that simply allocated something for every declared type would satisfy
    // them both.
    final var out = Telescope.mapper(ASrc.class, BDst.class).forward(new ASrc(oneElement()));

    assertEquals(List.of("x"), out.items());
    assertInstanceOf(RawB.class, out.items());
  }
}
