package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.invoke.MethodHandles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the native-image accessor closures. On the JVM these run directly (the {@code
 * MhAccessors} builders are not {@code NativeImage.IN_IMAGE}-gated — only their callers are), so
 * this pins the {@code asType}/{@code invokeExact} behaviour — primitive boxing/unboxing, fluent
 * return identity, and raw-exception propagation — that the native-image path relies on and that
 * must stay value-equivalent to the {@code LambdaMetafactory} path it replaces inside an image.
 */
class MhAccessorsTest {

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

  @Test
  @DisplayName("function reads a getter and boxes a primitive return to Integer")
  void functionReadsAndBoxesPrimitiveReturn() throws Throwable {
    final var handle = LOOKUP.unreflect(Box.class.getMethod("getCount"));
    final var reader = MhAccessors.function(handle);
    final var box = new Box(7);
    assertEquals(7, reader.apply(box));
    assertInstanceOf(Integer.class, reader.apply(box));
  }

  @Test
  @DisplayName("supplier invokes a no-arg static factory")
  void supplierInvokesStaticFactory() throws Throwable {
    final var handle = LOOKUP.unreflect(Box.class.getMethod("create"));
    assertInstanceOf(Box.class, MhAccessors.supplier(handle).get());
  }

  @Test
  @DisplayName("supplier invokes a no-arg constructor")
  void supplierInvokesConstructor() throws Throwable {
    final var handle = LOOKUP.unreflectConstructor(Box.class.getConstructor());
    assertInstanceOf(Box.class, MhAccessors.supplier(handle).get());
  }

  @Test
  @DisplayName("biConsumer writes a void setter, unboxing the boxed primitive argument")
  void biConsumerUnboxesPrimitiveArgument() throws Throwable {
    final var handle = LOOKUP.unreflect(Box.class.getMethod("setCount", int.class));
    final var writer = MhAccessors.biConsumer(handle);
    final var box = new Box(0);
    writer.accept(box, 42);
    assertEquals(42, box.getCount());
  }

  @Test
  @DisplayName("biConsumer writes a reference-typed void setter")
  void biConsumerWritesReferenceSetter() throws Throwable {
    final var handle = LOOKUP.unreflect(Box.class.getMethod("setName", String.class));
    final var writer = MhAccessors.biConsumer(handle);
    final var box = new Box(0);
    writer.accept(box, "hi");
    assertEquals("hi", box.getName());
  }

  @Test
  @DisplayName("biFunction invokes a fluent setter and returns the receiver")
  void biFunctionReturnsFluentReceiver() throws Throwable {
    final var handle = LOOKUP.unreflect(Box.class.getMethod("withName", String.class));
    final var fluent = MhAccessors.biFunction(handle);
    final var box = new Box(0);
    final var returned = fluent.apply(box, "fluent");
    assertSame(box, returned);
    assertEquals("fluent", box.getName());
  }

  @Test
  @DisplayName("an unchecked exception from the accessor propagates raw, not wrapped")
  void rawUncheckedExceptionPropagates() throws Throwable {
    final var handle = LOOKUP.unreflect(Box.class.getMethod("boom"));
    final var reader = MhAccessors.function(handle);
    final var thrown = assertThrows(IllegalStateException.class, () -> reader.apply(new Box(0)));
    assertEquals("boom", thrown.getMessage());
  }

  @Test
  @DisplayName("null into a primitive setter surfaces a NullPointerException")
  void nullIntoPrimitiveSetterThrows() throws Throwable {
    final var handle = LOOKUP.unreflect(Box.class.getMethod("setCount", int.class));
    final var writer = MhAccessors.biConsumer(handle);
    assertThrows(NullPointerException.class, () -> writer.accept(new Box(0), null));
  }

  @Test
  @DisplayName("a checked exception from the accessor is wrapped in IllegalStateException")
  void checkedExceptionIsWrapped() throws Throwable {
    final var handle = LOOKUP.unreflect(Box.class.getMethod("checkedBoom"));
    final var reader = MhAccessors.function(handle);
    final var thrown = assertThrows(IllegalStateException.class, () -> reader.apply(new Box(0)));
    assertInstanceOf(Exception.class, thrown.getCause());
  }

  @Test
  @DisplayName("an Error from the accessor propagates raw, never wrapped")
  void errorPropagatesRaw() throws Throwable {
    final var handle = LOOKUP.unreflect(Box.class.getMethod("throwsError"));
    final var reader = MhAccessors.function(handle);
    final var thrown = assertThrows(AssertionError.class, () -> reader.apply(new Box(0)));
    assertEquals("err", thrown.getMessage());
  }

  /** Test fixture exposing a getter, void + fluent setters, a factory, and throwing methods. */
  public static final class Box {

    private int count;
    private String name;

    public Box() {}

    public Box(final int count) {
      this.count = count;
    }

    public static Box create() {
      return new Box();
    }

    public int getCount() {
      return count;
    }

    public String getName() {
      return name;
    }

    public void setCount(final int count) {
      this.count = count;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public Box withName(final String name) {
      this.name = name;
      return this;
    }

    public void boom() {
      throw new IllegalStateException("boom");
    }

    public void checkedBoom() throws Exception {
      throw new Exception("checked");
    }

    public void throwsError() {
      throw new AssertionError("err");
    }
  }
}
