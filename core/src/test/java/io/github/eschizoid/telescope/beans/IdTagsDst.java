package io.github.eschizoid.telescope.beans;

import java.io.Serial;
import java.util.ArrayList;

/** Distinct target wrapper, same String element → identity copy via addAll. */
public class IdTagsDst extends ArrayList<String> {

  @Serial
  private static final long serialVersionUID = 1L;
}
