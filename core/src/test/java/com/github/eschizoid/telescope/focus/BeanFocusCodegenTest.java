package com.github.eschizoid.telescope.focus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code @BeanFocus} processor generates a working fluent navigator for POJOs. The
 * {@code <Pojo>Path<R>} classes are generated at test-compile time; if generation failed this would
 * not compile.
 */
class BeanFocusCodegenTest {

  @Test
  @DisplayName("setters-strategy bean: navigator updates the focused property immutably")
  void setters() {
    final var bean = new FocusSetterBean();
    bean.setId("u1");
    bean.setEmail("A@X");

    final var email = FocusSetterBeanPath.start().email();
    final var updated = email.update(bean, String::toLowerCase);
    assertEquals("a@x", updated.getEmail());
    assertEquals("u1", updated.getId());
    assertEquals("A@X", bean.getEmail()); // original untouched
  }

  @Test
  @DisplayName("builder-strategy bean: navigator updates via the static builder()")
  void builder() {
    final var bean = FocusBuilderBean.builder().id("u1").email("A@X").build();

    final var updated = FocusBuilderBeanPath.start().email().update(bean, String::toLowerCase);
    assertEquals("a@x", updated.getEmail());
    assertEquals("u1", updated.getId());
  }
}
