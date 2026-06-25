package io.github.eschizoid.telescope.bidirmapper;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/**
 * Source annotated with {@code @BeanFocus}: routes the mapper construct through generated
 * FieldOptics.
 */
@BeanFocus
public class BfDocSrc {

  private Integer docUpdateAttempts;

  public Integer getDocUpdateAttempts() {
    return docUpdateAttempts;
  }

  public void setDocUpdateAttempts(final Integer v) {
    this.docUpdateAttempts = v;
  }
}
