package io.github.eschizoid.telescope.ownbridge;

/** A bean built through its setters. */
public class ObNoteBean {

  private String text;

  public ObNoteBean() {}

  public String getText() {
    return text;
  }

  public void setText(final String text) {
    this.text = text;
  }
}
