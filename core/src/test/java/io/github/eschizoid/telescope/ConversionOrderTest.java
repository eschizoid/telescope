package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.conversion.Mapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The order the runtime mapper runs a pair's conversions in. A conversion with side effects sees
 * it, so it has to be the same in every JVM: a bean's properties convert in the order of the fields
 * that back them, and patch converts in the source's component order.
 */
class ConversionOrderTest {

  private static final StringBuilder LOG = new StringBuilder();

  private static String logged(final String value) {
    LOG.append(value).append(';');
    return value;
  }

  public record OrderSource(String firstCo, String secondCo) {}

  /**
   * Declares the bean's getters and setters the other way round. HotSpot lists a class's methods in
   * the order their names were first seen in the JVM, so once this class is loaded, {@link
   * OrderBean}'s methods come back second first, whatever ran before.
   */
  public static final class OrderNames {

    public String getSecondCo() {
      return null;
    }

    public void setSecondCo(final String v) {}

    public String getFirstCo() {
      return null;
    }

    public void setFirstCo(final String v) {}
  }

  public static final class OrderBean {

    private String firstCo;
    private String secondCo;

    public String getFirstCo() {
      return firstCo;
    }

    public void setFirstCo(final String v) {
      this.firstCo = v;
    }

    public String getSecondCo() {
      return secondCo;
    }

    public void setSecondCo(final String v) {
      this.secondCo = v;
    }
  }

  /**
   * Builds the mapper for {@link OrderBean}. Its method references name the bean's getters, so this
   * class is loaded only after {@link OrderNames}, which has to be the first to name them.
   */
  public static final class OrderMapper {

    public static Mapper<OrderSource, OrderBean> build() {
      return Telescope.mapper(
        OrderSource.class,
        OrderBean.class,
        to(OrderSource::firstCo, OrderBean::getFirstCo, ConversionOrderTest::logged, ConversionOrderTest::logged),
        to(OrderSource::secondCo, OrderBean::getSecondCo, ConversionOrderTest::logged, ConversionOrderTest::logged)
      );
    }
  }

  @Test
  @DisplayName(
    "a bean's properties convert in the order of the fields that back them, whatever its methods are listed in"
  )
  @SuppressWarnings("unchecked")
  void aBeansPropertiesConvertInFieldOrder() throws ReflectiveOperationException {
    // Each class is loaded by name, so this test names neither the bean's getters nor its setters,
    // and the first class to name them is OrderNames.
    Class.forName(ConversionOrderTest.class.getName() + "$OrderNames");
    final var mapper = (Mapper<Object, Object>) Class.forName(ConversionOrderTest.class.getName() + "$OrderMapper")
      .getMethod("build")
      .invoke(null);
    LOG.setLength(0);
    mapper.forward(new OrderSource("1", "2"));
    assertEquals("1;2;", LOG.toString());
  }

  public record XyFirst(String xCo, String yCo) {}

  public record XyFirstTarget(String xCo, String yCo) {}

  public record YxFirst(String yCo, String xCo) {}

  public record YxFirstTarget(String yCo, String xCo) {}

  @Test
  @DisplayName(
    "patch converts in the source's component order, for two sources holding the same fields the other way round"
  )
  void patchConvertsInTheSourcesComponentOrder() {
    // Both patch tables hold the keys xCo and yCo, entered in opposite orders. Their hash codes
    // differ in the lowest bit, so an immutable copy of either table never puts the two in one
    // probe slot, and iterates them in one order whatever order they were entered in: a copy that
    // drops the entry order gets one of these two wrong in every JVM.
    // Each mapper's rows are given in the other order from its source's components, which is the
    // order patch has to follow.
    final var xy = Telescope.mapper(
      XyFirst.class,
      XyFirstTarget.class,
      to(XyFirst::yCo, XyFirstTarget::yCo, ConversionOrderTest::logged, ConversionOrderTest::logged),
      to(XyFirst::xCo, XyFirstTarget::xCo, ConversionOrderTest::logged, ConversionOrderTest::logged)
    );
    final var yx = Telescope.mapper(
      YxFirst.class,
      YxFirstTarget.class,
      to(YxFirst::xCo, YxFirstTarget::xCo, ConversionOrderTest::logged, ConversionOrderTest::logged),
      to(YxFirst::yCo, YxFirstTarget::yCo, ConversionOrderTest::logged, ConversionOrderTest::logged)
    );
    LOG.setLength(0);
    xy.patch(new XyFirst("a", "b"), new XyFirstTarget("x", "y"));
    final var xyOrder = LOG.toString();
    LOG.setLength(0);
    yx.patch(new YxFirst("b", "a"), new YxFirstTarget("y", "x"));
    final var yxOrder = LOG.toString();
    assertEquals("x;y; and y;x;", xyOrder + " and " + yxOrder);
  }
}
