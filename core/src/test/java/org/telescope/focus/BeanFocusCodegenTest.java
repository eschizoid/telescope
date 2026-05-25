package org.telescope.focus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code @BeanFocus} processor generates working, reflection-free POJO lenses. The
 * {@code <Pojo>Focus} classes are generated at test-compile time; if generation failed this would
 * not compile.
 */
class BeanFocusCodegenTest {

  @Test
  @DisplayName("setters-strategy bean: generated lens updates immutably")
  void setters() {
    final var bean = new FocusSetterBean();
    bean.setId("u1");
    bean.setEmail("A@X");

    final var updated = FocusSetterBeanFocus.email.update(bean, String::toLowerCase);
    assertEquals("a@x", updated.getEmail());
    assertEquals("u1", updated.getId());
    assertEquals("A@X", bean.getEmail()); // original untouched
  }

  @Test
  @DisplayName("builder-strategy bean: generated lens updates immutably")
  void builder() {
    final var bean = FocusBuilderBean.builder().id("u1").email("A@X").build();

    final var updated = FocusBuilderBeanFocus.email.update(bean, String::toLowerCase);
    assertEquals("a@x", updated.getEmail());
    assertEquals("u1", updated.getId());
  }
}
