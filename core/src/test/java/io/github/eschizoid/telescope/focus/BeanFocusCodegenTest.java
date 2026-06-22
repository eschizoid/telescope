package io.github.eschizoid.telescope.focus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code @BeanFocus} processor generates a working fluent navigator for POJOs. The
 * {@code <Pojo>Telescope<R>} classes are generated at test-compile time; if generation failed this
 * would not compile.
 */
class BeanFocusCodegenTest {

  @Test
  @DisplayName("setters-strategy bean: navigator updates the focused property immutably")
  void setters() {
    final var bean = new FocusSetterBean();
    bean.setId("u1");
    bean.setEmail("A@X");

    final var email = FocusSetterBeanTelescope.of().email();
    final var updated = email.update(bean, String::toLowerCase);
    assertEquals("a@x", updated.getEmail());
    assertEquals("u1", updated.getId());
    assertEquals("A@X", bean.getEmail()); // original untouched
  }

  @Test
  @DisplayName("builder-strategy bean: navigator updates via the static builder()")
  void builder() {
    final var bean = FocusBuilderBean.builder().id("u1").email("A@X").build();

    final var updated = FocusBuilderBeanTelescope.of().email().update(bean, String::toLowerCase);
    assertEquals("a@x", updated.getEmail());
    assertEquals("u1", updated.getId());
  }

  @Test
  @DisplayName(
    "navigator write through an N-hop chain whose multi-property intermediate is null constructs every hop and defaults off-path properties"
  )
  void navigatorWriteThroughNullMultiPropertyIntermediate() {
    // Pure codegen path: the generated <X>Telescope navigator (not the runtime ofBean().field()
    // factory), driving Telescope.set through a composed lens chain rather than DeepMap. Writing
    // the
    // leaf into a freshly-built root — every intermediate null — must construct each hop through
    // the
    // null-tolerant generated lenses, including the multi-property address whose off-path
    // countryName / zipCode default instead of NPE-ing.
    final var built = MultiPropWriteOuterTelescope.of()
      .mid()
      .address()
      .cityName()
      .set(new MultiPropWriteOuter(), "navtown");
    assertNotNull(built.getMid(), "hop-1 intermediate constructed");
    assertNotNull(built.getMid().getAddress(), "null multi-property hop-2 intermediate constructed");
    assertEquals("navtown", built.getMid().getAddress().getCityName(), "focused leaf written through the navigator");
    assertNull(built.getMid().getAddress().getCountryName(), "off-path reference defaulted to null");
    assertEquals(0, built.getMid().getAddress().getZipCode(), "off-path primitive defaulted to 0");
  }
}
