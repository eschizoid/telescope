package io.github.eschizoid.telescope.beans;

import java.io.Serial;
import java.util.HashMap;

/** Target-side custom map wrapper; value record is distinct (bridgeable). */
public class RawMapDstWrap extends HashMap<String, RawColDstElem> {

  @Serial
  private static final long serialVersionUID = 1L;
}
