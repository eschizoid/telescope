package io.github.eschizoid.telescope.quarkus.tests.services;

/** Prices a line; each application declares the beans of it it means to have. */
public class Catalog {

  private final int perLetter;

  public Catalog(final int perLetter) {
    this.perLetter = perLetter;
  }

  public int price(final LineChanges line) {
    return line.sku().length() * perLetter;
  }
}
