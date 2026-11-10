package io.github.telescopeorganization.telescope.sealedroots;

import io.github.telescopeorganization.telescope.conversion.BridgeFn;

public final class SvUpper implements BridgeFn<String, String> {

  @Override
  public String forward(final String s) {
    return s == null ? null : s.toUpperCase();
  }

  @Override
  public String backward(final String s) {
    return s == null ? null : s.toLowerCase();
  }
}
