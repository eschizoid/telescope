package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Telescope.edits;
import static io.github.eschizoid.telescope.Telescope.path;
import static io.github.eschizoid.telescope.mapping.Cases.cases;
import static io.github.eschizoid.telescope.mapping.Mapping.backReference;
import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.constant;
import static io.github.eschizoid.telescope.mapping.Mapping.keep;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static io.github.eschizoid.telescope.mapping.Mapping.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.BridgeFn;
import io.github.eschizoid.telescope.conversion.BridgeProvider;
import io.github.eschizoid.telescope.conversion.BridgeRegistry;
import io.github.eschizoid.telescope.conversion.EditMapper;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.introspection.OpticNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins what an edit mapper hands a generated bridge and what it runs on the runtime path instead:
 * the operations a bridge writes itself, the runtime fallback for the rest and for a subclass
 * target, the edit rows written through a path, a sealed pair written case by case, and the
 * refusals of a bridge with no edit operations.
 */
class EditMapperSeamsTest {

  public static class Note {

    private String text;
    private int size;

    public Note() {}

    public String getText() {
      return text;
    }

    public void setText(final String text) {
      this.text = text;
    }

    public int getSize() {
      return size;
    }

    public void setSize(final int size) {
      this.size = size;
    }
  }

  /** A note whose text setter marks what it takes, so a write through it shows. */
  public static class MarkedNote extends Note {

    @Override
    public void setText(final String text) {
      super.setText("marked:" + text);
    }
  }

  public record NoteChanges(String text) {}

  /** Counts each operation a hand-written edit bridge is asked for. */
  private static final class CountingBridge implements BridgeFn<NoteChanges, Note> {

    private final int writes;
    final AtomicInteger calls = new AtomicInteger();

    CountingBridge(final int writes) {
      this.writes = writes;
    }

    @Override
    public Note forward(final NoteChanges source) {
      final var note = new Note();
      note.setText(source.text());
      note.setSize(7);
      return note;
    }

    @Override
    public NoteChanges backward(final Note target) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int writesEdits() {
      return writes;
    }

    @Override
    public Note editInto(final Note target, final NoteChanges source) {
      calls.incrementAndGet();
      target.setText("bridge:" + source.text());
      return target;
    }

    @Override
    public Note editPatchInto(final Note target, final NoteChanges partial) {
      calls.incrementAndGet();
      if (partial != null && partial.text() != null) target.setText("bridge:" + partial.text());
      return target;
    }

    @Override
    public Note editPatch(final Note base, final NoteChanges partial) {
      calls.incrementAndGet();
      final var copy = new Note();
      copy.setText("bridge:" + partial.text());
      return copy;
    }
  }

  private static EditMapper<NoteChanges, Note> over(final CountingBridge bridge) {
    return EditMapper.ofGenerated(
      bridge,
      NoteChanges.class,
      Note.class,
      () -> edits(NoteChanges.class, Note.class, keep(Note::getSize)),
      List.of()
    );
  }

  @Test
  @DisplayName("a bridge writing every operation is called for each, and a subclass target runs on the runtime path")
  void callsTheBridgeForEachOperationItWrites() {
    final var bridge = new CountingBridge(BridgeFn.EDIT_INTO + BridgeFn.EDIT_PATCH_INTO + BridgeFn.EDIT_PATCH);
    final var mapper = over(bridge);
    final var note = new Note();
    mapper.into(note, new NoteChanges("a"));
    assertEquals("bridge:a", note.getText());
    mapper.patchInto(note, new NoteChanges("b"));
    assertEquals("bridge:b", note.getText());
    assertEquals("bridge:c", mapper.patch(note, new NoteChanges("c")).getText());
    assertEquals(3, bridge.calls.get());
    assertEquals(7, mapper.forward(new NoteChanges("d")).getSize(), "forward is the bridge's");

    final var marked = new MarkedNote();
    marked.setSize(4);
    mapper.into(marked, new NoteChanges("e"));
    assertEquals("marked:e", marked.getText(), "a subclass is written through its own setter");
    mapper.patchInto(marked, new NoteChanges("f"));
    assertEquals("marked:f", marked.getText());
    assertEquals(4, marked.getSize(), "the runtime path keeps a kept property");
    assertEquals(3, bridge.calls.get(), "the bridge wrote no subclass");
  }

  @Test
  @DisplayName("a bridge writing no edit operation runs every one on the runtime edit mapper over the same rows")
  void runsTheRuntimeOperationsABridgeLacks() {
    final var bridge = new CountingBridge(0);
    final var mapper = over(bridge);
    final var note = new Note();
    note.setSize(5);
    mapper.into(note, new NoteChanges("a"));
    assertEquals("a", note.getText());
    mapper.patchInto(note, new NoteChanges(null));
    assertEquals("a", note.getText());
    final var copy = mapper.patch(note, new NoteChanges("b"));
    assertEquals("b", copy.getText());
    assertEquals(5, copy.getSize());
    assertEquals(0, bridge.calls.get());
    assertEquals(NoteChanges.class, mapper.sourceClass());
    assertEquals(Note.class, mapper.targetClass());
    assertEquals("a", mapper.toForwardMapper().forward(new NoteChanges("a")).getText());
  }

  @Test
  @DisplayName("a bridge no edit mapper declares refuses each edit operation by name, and only the grant extends one")
  void refusesWhatABridgeDoesNotHave() {
    final BridgeFn<NoteChanges, Note> plain = new BridgeFn<>() {
      @Override
      public Note forward(final NoteChanges source) {
        return new Note();
      }

      @Override
      public NoteChanges backward(final Note target) {
        return new NoteChanges(target.getText());
      }
    };
    assertTrue(
      assertThrows(UnsupportedOperationException.class, () -> plain.editInto(new Note(), new NoteChanges("a")))
        .getMessage()
        .contains("no into of an edit mapper")
    );
    assertThrows(UnsupportedOperationException.class, () -> plain.editPatchInto(new Note(), new NoteChanges("a")));
    assertThrows(UnsupportedOperationException.class, () -> plain.editPatch(new Note(), new NoteChanges("a")));
    assertEquals(0, plain.writesEdits());

    final var runtime = edits(NoteChanges.class, Note.class, keep(Note::getSize));
    assertThrows(IllegalArgumentException.class, () -> new EditMapper<>(runtime, null) {});
    final var generated = over(new CountingBridge(0));
    assertThrows(IllegalArgumentException.class, () -> new EditMapper<>(generated, null) {});

    final BridgeProvider provider = new BridgeProvider() {
      @Override
      public Class<?> sourceType() {
        return NoteChanges.class;
      }

      @Override
      public Class<?> targetType() {
        return Note.class;
      }

      @Override
      public Object bridge() {
        return null;
      }
    };
    assertSame(
      generated,
      BridgeRegistry.specialise(provider, generated),
      "an unregistered provider specialises nothing"
    );
    assertSame(generated, provider.editMapper(generated, null));
  }

  public static class Box {

    private Note note;
    private String label;

    public Box() {}

    public Note getNote() {
      return note;
    }

    public void setNote(final Note note) {
      this.note = note;
    }

    public String getLabel() {
      return label;
    }

    public void setLabel(final String label) {
      this.label = label;
    }
  }

  public record BoxChanges(String text, String label) {
    public boolean hasLabel() {
      return label != null;
    }
  }

  public record Wrapped(Inner inner) {}

  public record Inner(String text) {}

  @Test
  @DisplayName("rows writing a path into a bean write into the objects it holds, gated and computed rows included")
  void writesPathsIntoTheHeldObjects() {
    final var mapper = edits(
      BoxChanges.class,
      Box.class,
      to(BoxChanges::text, path(Box::getNote, Note::getText)),
      when(BoxChanges::hasLabel, constant(path(Box::getNote, Note::getSize), 9)),
      compute(path(Box::getNote, Note::getSize), () -> 9)
    );
    final var box = new Box();
    final var note = new Note();
    note.setText("old");
    box.setNote(note);
    box.setLabel("kept");
    mapper.patchInto(box, new BoxChanges("new", null));
    assertSame(note, box.getNote(), "the note the box holds is written into");
    assertEquals("new", note.getText());
    assertEquals(9, note.getSize());
    assertEquals("kept", box.getLabel(), "a label the partial leaves out is kept");
    mapper.into(box, new BoxChanges(null, "x"));
    assertSame(note, box.getNote());
    assertNull(note.getText(), "into writes the null the partial gives through the path");
    assertEquals("x", box.getLabel());

    final var empty = new Box();
    mapper.patchInto(empty, new BoxChanges(null, null));
    assertEquals(9, empty.getNote().getSize(), "a computed value builds the object the path steps through");

    final var copy = mapper.patch(box, new BoxChanges("copied", null));
    assertEquals("copied", copy.getNote().getText());
    assertEquals("x", copy.getLabel());
  }

  @Test
  @DisplayName("a path reading the partial is sent only where no object on it is null")
  void readsAPathOfThePartial() {
    final var mapper = edits(
      Wrapped.class,
      Note.class,
      to(path(Wrapped::inner, Inner::text), Note::getText),
      keep(Note::getSize)
    );
    final var note = new Note();
    note.setText("old");
    mapper.patchInto(note, new Wrapped(null));
    assertEquals("old", note.getText());
    mapper.patchInto(note, new Wrapped(new Inner("deep")));
    assertEquals("deep", note.getText());
  }

  public sealed interface Shape permits Circle {}

  public static final class Circle implements Shape {

    private int radius;
    private String name;

    public Circle() {}

    public int getRadius() {
      return radius;
    }

    public void setRadius(final int radius) {
      this.radius = radius;
    }

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }
  }

  public sealed interface ShapeChanges permits CircleChanges {}

  public record CircleChanges(Integer radius, String name) implements ShapeChanges {}

  @Test
  @DisplayName("between two sealed interfaces, patchInto and patch write through the mapper of the target's case")
  void writesASealedPairCaseByCase() {
    final var mapper = edits(ShapeChanges.class, Shape.class, cases(CircleChanges.class, Circle.class));
    final var circle = new Circle();
    circle.setRadius(2);
    circle.setName("c");
    mapper.patchInto(circle, new CircleChanges(5, null));
    assertEquals(5, circle.getRadius());
    assertEquals("c", circle.getName());
    final var copy = (Circle) mapper.patch(circle, new CircleChanges(null, "d"));
    assertEquals(5, copy.getRadius());
    assertEquals("d", copy.getName());
  }

  public static class Owner {

    private List<Item> items = new ArrayList<>();

    public Owner() {}

    public List<Item> getItems() {
      return items;
    }

    public void setItems(final List<Item> items) {
      this.items = items;
    }
  }

  public static class Item {

    private String label;
    private Owner owner;

    public Item() {}

    public String getLabel() {
      return label;
    }

    public void setLabel(final String label) {
      this.label = label;
    }

    public Owner getOwner() {
      return owner;
    }

    public void setOwner(final Owner owner) {
      this.owner = owner;
    }
  }

  public record ItemChanges(String label) {}

  public record OwnerChanges(List<ItemChanges> items) {}

  @Test
  @DisplayName("a backReference row given to an element's own edit mapper counts its property as accounted for")
  void countsAPinnedBackReference() {
    final var item = edits(ItemChanges.class, Item.class, backReference(Item::getOwner));
    assertTrue(item.explain().nodes().contains(new OpticNode.Skipped("owner", OpticNode.Reason.BACK_REFERENCE)), () ->
      item.explain().toString()
    );
    assertTrue(item.explain().toString().contains("back-reference"), () -> item.explain().toString());
    final var owner = edits(
      OwnerChanges.class,
      Owner.class,
      via(OwnerChanges::items, Owner::getItems, item),
      backReference(Item::getOwner)
    );
    final var held = new Owner();
    owner.patchInto(held, new OwnerChanges(List.of(new ItemChanges("a"))));
    assertEquals("a", held.getItems().getFirst().getLabel());
    assertSame(held, held.getItems().getFirst().getOwner());
    assertTrue(item.trace(new ItemChanges("t")).toString().contains("back-reference"), () ->
      item.trace(new ItemChanges("t")).toString()
    );
    final var kept = edits(NoteChanges.class, Note.class, keep(Note::getSize));
    assertTrue(kept.explain().toString().contains("kept, never written by an edit"), () -> kept.explain().toString());
  }

  @Test
  @DisplayName("a keep keyed to another pair, and a via naming an edit mapper on a forward mapper, are refused by name")
  void refusesEditRowsElsewhere() {
    final var item = edits(ItemChanges.class, Item.class, backReference(Item::getOwner));
    final var refused = assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapperForward(OwnerChanges.class, Owner.class, via(OwnerChanges::items, Owner::getItems, item))
    );
    assertEquals(
      PairingMessages.viaEditsOutsideEdits("Telescope.mapperForward", "OwnerChanges", "Owner", "items"),
      refused.getMessage()
    );
    assertThrows(IllegalArgumentException.class, () ->
      Telescope.mapper(NoteChanges.class, Note.class, keep(Note::getSize))
    );
  }
}
