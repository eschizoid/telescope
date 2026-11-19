package io.github.eschizoid.telescope.jpa.fixtures.edges;

import static io.github.eschizoid.telescope.mapping.Mapping.via;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

public final class CartMappers {

  private CartMappers() {}

  @Bridge
  public static final Mapper<CartForm, Cart> GEN = Telescope.mapper(CartForm.class, Cart.class);

  @Bridge
  public static final Mapper<Cart, CartForm> GEN_PATCH = Telescope.mapper(Cart.class, CartForm.class);

  public static final Mapper<CartEdit, Cart> RT = Telescope.mapper(CartEdit.class, Cart.class);

  public static final Mapper<Cart, CartEdit> RT_PATCH = Telescope.mapper(Cart.class, CartEdit.class);

  @Bridge
  public static final Mapper<NoteForm, Note> NOTE_GEN = Telescope.mapper(NoteForm.class, Note.class);

  @Bridge
  public static final Mapper<BoxForm, Box> BOX_GEN = Telescope.mapper(
    BoxForm.class,
    Box.class,
    via(BoxForm::notes, Box::getNotes, NOTE_GEN)
  );

  public static final Mapper<NoteEdit, Note> NOTE_RT = Telescope.mapper(NoteEdit.class, Note.class);

  public static final Mapper<BoxEdit, Box> BOX_RT = Telescope.mapper(
    BoxEdit.class,
    Box.class,
    via(BoxEdit::notes, Box::getNotes, NOTE_RT)
  );

  public static final Mapper<BoxEdit, Box> BOX_RT_METHODS = Telescope.mapper(
    BoxEdit.class,
    Box.class,
    via(BoxEdit::notes, Box::getNotes, Notes::forward, Notes::backward)
  );
}
