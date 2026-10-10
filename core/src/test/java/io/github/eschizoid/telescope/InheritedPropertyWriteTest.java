package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Edit.over;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.eschizoid.telescope.introspection.OpticNode.Focus;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A runtime {@code field(...)} write through a property whose getter is declared on an abstract
 * superclass rebuilds the class the path navigates, which is the only one that can be constructed,
 * and records that class as the hop's fusion owner, as a generated navigator does.
 */
class InheritedPropertyWriteTest {

  @SafeVarargs
  @SuppressWarnings("varargs")
  private static <S> Function<S, S> fuse(final Edit<S>... edits) {
    return Fusion.fuse(edits);
  }

  private static InheritChainNode chain(final String label, final String tag) {
    final var node = new InheritChainNode();
    node.setLabel(label);
    node.setTag(tag);
    return node;
  }

  private static InheritNamedLeaf leaf(final String name, final int size) {
    final var leaf = new InheritNamedLeaf();
    leaf.setName(name);
    leaf.setSize(size);
    return leaf;
  }

  @Test
  @DisplayName("a setter bean writes a property declared on an abstract generic base")
  void setterBeanWritesThroughAnAbstractGenericBase() {
    final var node = chain("a", "t");
    final var other = chain("b", "u");
    final var written = Telescope.ofBean(InheritChainNode.class).field(InheritChainNode::getNext).set(node, other);
    assertSame(InheritChainNode.class, written.getClass());
    assertSame(other, written.getNext());
    assertEquals("a", written.getLabel());
    assertEquals("t", written.getTag());
    assertNull(node.getNext());
  }

  @Test
  @DisplayName("a builder bean writes a property declared on an abstract generic base")
  void builderBeanWritesThroughAnAbstractGenericBase() {
    final var node = InheritBuiltNode.builder().label("a").tag("t").build();
    final var other = InheritBuiltNode.builder().label("b").build();
    final var written = Telescope.ofBean(InheritBuiltNode.class).field(InheritBuiltNode::getNext).set(node, other);
    assertSame(InheritBuiltNode.class, written.getClass());
    assertSame(other, written.getNext());
    assertEquals("a", written.getLabel());
    assertEquals("t", written.getTag());
  }

  @Test
  @DisplayName("a path continuing below an inherited property rebuilds each level as the class it navigates")
  void nestedWriteThroughInheritedProperties() {
    final var node = chain("a", "t");
    node.setNext(chain("b", "u"));
    final var written = Telescope.ofBean(InheritChainNode.class)
      .field(InheritChainNode::getNext)
      .field(InheritChainNode::getLabel)
      .update(node, String::toUpperCase);
    assertEquals("B", written.getNext().getLabel());
    assertEquals("u", written.getNext().getTag());
    assertEquals("a", written.getLabel());
    assertEquals("t", written.getTag());
  }

  @Test
  @DisplayName("a setter bean without a holder writes a property declared on an abstract non-generic base")
  void reflectiveWriteThroughAnAbstractNonGenericBase() {
    final var source = new InheritPlainLeaf();
    source.setName("ann");
    source.setSize(3);
    final var written = Telescope.ofBean(InheritPlainLeaf.class).field(InheritPlainLeaf::getName).set(source, "bo");
    assertSame(InheritPlainLeaf.class, written.getClass());
    assertEquals("bo", written.getName());
    assertEquals(3, written.getSize());
    assertEquals("ann", source.getName());
  }

  @Test
  @DisplayName("a @BeanFocus subclass resolves an inherited property on itself, where its holder lives")
  void holderBackedWriteThroughAnAbstractNonGenericBase() {
    final var source = leaf("ann", 3);
    final var runtime = Telescope.ofBean(InheritNamedLeaf.class).field(InheritNamedLeaf::getName).set(source, "bo");
    final var navigator = InheritNamedLeafTelescope.of().name().set(source, "bo");
    assertSame(InheritNamedLeaf.class, runtime.getClass());
    assertEquals(navigator.getName(), runtime.getName());
    assertEquals(navigator.getSize(), runtime.getSize());
    assertEquals(3, runtime.getSize());
  }

  @Test
  @DisplayName("an inherited property's hop is owned by the class the path navigates, on both paths")
  void inheritedHopIsOwnedByTheReceiver() {
    final var runtime = Telescope.ofBean(InheritNamedLeaf.class).field(InheritNamedLeaf::getName);
    final var navigator = InheritNamedLeafTelescope.of().name();
    assertEquals(InheritNamedLeaf.class, runtime.hops.get(0).owner());
    assertEquals(
      runtime.hops.stream().map(Fusion.Hop::key).toList(),
      navigator.hops.stream().map(Fusion.Hop::key).toList()
    );
    assertEquals(List.of(new Focus("name")), runtime.explain().nodes());
    assertEquals(runtime.explain().nodes(), navigator.explain().nodes());
  }

  @Test
  @DisplayName("Telescope.all fuses a runtime path through an inherited property with a navigator path")
  void fusesARuntimeInheritedPathWithANavigatorPath() {
    final var runtimeName = over(
      Telescope.ofBean(InheritNamedLeaf.class).field(InheritNamedLeaf::getName),
      String::toUpperCase
    );
    final var navigatorName = over(InheritNamedLeafTelescope.of().name(), (final String s) -> s + "!");
    final var size = over(InheritNamedLeafTelescope.of().size(), (final Integer n) -> n + 1);
    assertNotNull(fuse(runtimeName, navigatorName, size));
    final var source = leaf("ann", 3);
    final var fused = Telescope.all(runtimeName, navigatorName, size).apply(source);
    final var sequential = size.apply(navigatorName.apply(runtimeName.apply(source)));
    assertSame(InheritNamedLeaf.class, fused.getClass());
    assertEquals(sequential.getName(), fused.getName());
    assertEquals(sequential.getSize(), fused.getSize());
    assertEquals("ANN!", fused.getName());
    assertEquals(4, fused.getSize());
  }
}
