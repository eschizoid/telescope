package io.github.eschizoid.telescope.codegen.lombok;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.OrderedLog;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.OrderedLombokSource;
import io.github.eschizoid.telescope.codegen.lombok.fixtures.OrderedLombokTarget;
import io.github.eschizoid.telescope.mapping.Mapping;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code @Data} bean's synthesized properties convert in the order of the fields that back them,
 * on the generated bridge and on the runtime mapper, even after a class naming the bean's methods
 * the other way round has been loaded. Lombok writes no getter into the source, so the order has to
 * come from the fields on both paths.
 */
class LombokPropertyOrderTest {

  private static final String FIXTURES = "io.github.eschizoid.telescope.codegen.lombok.fixtures.";

  @Test
  @DisplayName("a @Data bean's properties convert in field order on both paths")
  void aDataBeansPropertiesConvertInFieldOrder() throws ReflectiveOperationException {
    // Loaded by name first, so the bean's method names are first seen in the other order.
    Class.forName(FIXTURES + "OrderedLombokNames");
    final var source = Class.forName(FIXTURES + "OrderedLombokSource");
    final var target = Class.forName(FIXTURES + "OrderedLombokTarget");
    final var sample = source.getConstructors()[0].newInstance("1", "2");

    OrderedLog.LOG.setLength(0);
    final Method forward = Class.forName(FIXTURES + "OrderedLombokSourceBridge").getMethod("forward", source);
    forward.invoke(null, sample);
    final var generated = OrderedLog.LOG.toString();

    OrderedLog.LOG.setLength(0);
    Class.forName(LombokPropertyOrderTest.class.getName() + "$Runtime")
      .getMethod("forward", Object.class)
      .invoke(null, sample);
    final var reflective = OrderedLog.LOG.toString();

    assertEquals("1;2; and 1;2;", generated + " and " + reflective, () -> "target " + target.getName());
  }

  /**
   * The runtime mapper for the pair. Its method references name the bean's getters, so it is loaded
   * only after {@code OrderedLombokNames}.
   */
  public static final class Runtime {

    public static Object forward(final Object source) {
      final var fn = new OrderedLog();
      return Telescope.mapperForward(
        OrderedLombokSource.class,
        OrderedLombokTarget.class,
        Mapping.toOneWay(OrderedLombokSource::firstLo, OrderedLombokTarget::getFirstLo, fn::forward),
        Mapping.toOneWay(OrderedLombokSource::secondLo, OrderedLombokTarget::getSecondLo, fn::forward)
      ).forward((OrderedLombokSource) source);
    }
  }
}
