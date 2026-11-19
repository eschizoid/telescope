package io.github.eschizoid.telescope.jpa.fixtures.edges;

public final class Notes {

  private Notes() {}

  public static Note forward(final NoteEdit edit) {
    final var note = new Note(edit.text());
    note.setId(edit.id());
    return note;
  }

  public static NoteEdit backward(final Note note) {
    return new NoteEdit(note.getId(), note.getText());
  }
}
