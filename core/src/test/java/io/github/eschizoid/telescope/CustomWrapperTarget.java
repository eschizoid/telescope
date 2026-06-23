package io.github.eschizoid.telescope;

/** target bean: distinct wrapper, renamed scalar, plus an extra field leniency defaults. */
public class CustomWrapperTarget {

  private CustomWrapperDstUrls imageUrls;
  private String vendorExtendedResult;
  private String extra;

  public CustomWrapperDstUrls getImageUrls() {
    return imageUrls;
  }

  public void setImageUrls(final CustomWrapperDstUrls imageUrls) {
    this.imageUrls = imageUrls;
  }

  public String getVendorExtendedResult() {
    return vendorExtendedResult;
  }

  public void setVendorExtendedResult(final String vendorExtendedResult) {
    this.vendorExtendedResult = vendorExtendedResult;
  }

  public String getExtra() {
    return extra;
  }

  public void setExtra(final String extra) {
    this.extra = extra;
  }
}
