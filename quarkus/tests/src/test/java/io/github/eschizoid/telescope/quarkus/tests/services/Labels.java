package io.github.eschizoid.telescope.quarkus.tests.services;

import jakarta.enterprise.context.ApplicationScoped;

/** Labels a line the client sees, as a bean of the application. */
@ApplicationScoped
public class Labels {

  public String label(final Line line) {
    return "L-" + line.getSku();
  }
}
