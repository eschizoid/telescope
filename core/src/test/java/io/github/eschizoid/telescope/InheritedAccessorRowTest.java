package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.drop;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.toOneWay;
import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static io.github.eschizoid.telescope.mapping.MergeStep.from;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A mapping row whose accessor is a getter inherited from an abstract base binds to the class the
 * row names, which is the class the mapper visits, not to the base that declares the getter. Each
 * row kind is driven with the inherited accessor on the side it reads its class from, and through
 * the mapper both ways where the row is bidirectional.
 */
class InheritedAccessorRowTest {

  private static RowNamedSub sub(final String name, final String code, final String tag) {
    final var sub = new RowNamedSub();
    sub.setName(name);
    sub.setCode(code);
    sub.setTag(tag);
    return sub;
  }

  @Test
  @DisplayName("to(src, tgt) with an inherited target getter maps both ways")
  void sameTypedRowWithAnInheritedTargetGetter() {
    final var mapper = Telescope.mapper(
      RowRenamed.class,
      RowNamedSub.class,
      to(RowRenamed::label, RowNamedSub::getName),
      to(RowRenamed::key, RowNamedSub::getCode)
    );
    final var forward = mapper.forward(new RowRenamed("ann", "k1", "t"));
    assertSame(RowNamedSub.class, forward.getClass());
    assertEquals("ann", forward.getName());
    assertEquals("k1", forward.getCode());
    assertEquals("t", forward.getTag());
    assertEquals(new RowRenamed("bo", "k2", "u"), mapper.backward(sub("bo", "k2", "u")));
  }

  @Test
  @DisplayName("to(src, tgt) with an inherited source getter maps both ways")
  void sameTypedRowWithAnInheritedSourceGetter() {
    final var mapper = Telescope.mapper(
      RowNamedSub.class,
      RowRenamed.class,
      to(RowNamedSub::getName, RowRenamed::label),
      to(RowNamedSub::getCode, RowRenamed::key)
    );
    assertEquals(new RowRenamed("ann", "k1", "t"), mapper.forward(sub("ann", "k1", "t")));
    final var backward = mapper.backward(new RowRenamed("bo", "k2", "u"));
    assertSame(RowNamedSub.class, backward.getClass());
    assertEquals("bo", backward.getName());
    assertEquals("k2", backward.getCode());
  }

  @Test
  @DisplayName("to(src, tgt, forward, backward) with an inherited target getter converts both ways")
  void typedTransformRowWithAnInheritedTargetGetter() {
    final var mapper = Telescope.mapper(
      RowFlat.class,
      RowNamedSub.class,
      to(RowFlat::code, RowNamedSub::getCode, String::toUpperCase, String::toLowerCase)
    );
    assertEquals("K1", mapper.forward(new RowFlat("ann", "k1", "t")).getCode());
    assertEquals("k2", mapper.backward(sub("bo", "K2", "u")).code());
  }

  @Test
  @DisplayName("toOneWay with an inherited source getter converts forward")
  void forwardOnlyRowWithAnInheritedSourceGetter() {
    final var mapper = Telescope.mapperForward(
      RowNamedSub.class,
      RowRenamed.class,
      toOneWay(RowNamedSub::getName, RowRenamed::label, (final String s) -> s + "!")
    );
    assertEquals("ann!", mapper.forward(sub("ann", "k1", "t")).label());
  }

  @Test
  @DisplayName("via with an inherited target getter maps the nested value through its mapper")
  void viaRowWithAnInheritedTargetGetter() {
    final var entryMapper = Telescope.mapper(RowFlat.class, RowNamedSub.class);
    final var mapper = Telescope.mapper(
      RowHolder.class,
      RowHolderSub.class,
      via(RowHolder::item, RowHolderSub::getEntry, entryMapper)
    );
    final var forward = mapper.forward(new RowHolder(new RowFlat("ann", "k1", "t")));
    assertSame(RowHolderSub.class, forward.getClass());
    assertEquals("ann", forward.getEntry().getName());
    assertEquals("k1", forward.getEntry().getCode());
    final var holder = new RowHolderSub();
    holder.setEntry(sub("bo", "k2", "u"));
    assertEquals(new RowHolder(new RowFlat("bo", "k2", "u")), mapper.backward(holder));
  }

  @Test
  @DisplayName("to(src, telescope) with an inherited source getter maps forward")
  void telescopeTargetRowWithAnInheritedSourceGetter() {
    final var mapper = Telescope.mapperForward(
      RowNamedSub.class,
      RowRenamed.class,
      to(RowNamedSub::getName, Telescope.of(RowRenamed.class).field(RowRenamed::label)),
      to(RowNamedSub::getCode, Telescope.of(RowRenamed.class).field(RowRenamed::key))
    );
    assertEquals(new RowRenamed("ann", "k1", "t"), mapper.forward(sub("ann", "k1", "t")));
  }

  @Test
  @DisplayName("to(telescope, tgt) with an inherited target getter maps forward")
  void telescopeSourceRowWithAnInheritedTargetGetter() {
    final var mapper = Telescope.mapperForward(
      RowRenamed.class,
      RowNamedSub.class,
      to(Telescope.of(RowRenamed.class).field(RowRenamed::label), RowNamedSub::getName),
      to(Telescope.of(RowRenamed.class).field(RowRenamed::key), RowNamedSub::getCode)
    );
    final var forward = mapper.forward(new RowRenamed("ann", "k1", "t"));
    assertEquals("ann", forward.getName());
    assertEquals("k1", forward.getCode());
    assertEquals("t", forward.getTag());
  }

  @Test
  @DisplayName("drop with an inherited source getter lets a strict mapper leave that property out")
  void dropRowWithAnInheritedSourceGetter() {
    final var mapper = Telescope.mapper(RowNamedSub.class, RowUncoded.class, drop(RowNamedSub::getCode));
    assertEquals(new RowUncoded("ann", "t"), mapper.forward(sub("ann", "k1", "t")));
  }

  @Test
  @DisplayName("merge from(src, tgt) with an inherited source getter reads the source bound under its own class")
  void mergeStepWithAnInheritedSourceGetter() {
    final var mapper = Telescope.merge(
      RowFlat.class,
      from(RowNamedSub::getName, RowFlat::name),
      from(RowNamedSub::getCode, RowFlat::code),
      from(RowRenamed::tag, RowFlat::tag)
    );
    assertEquals(
      new RowFlat("ann", "k1", "u"),
      mapper.forward(Sources.of(sub("ann", "k1", "t"), new RowRenamed("x", "y", "u")))
    );
  }
}
