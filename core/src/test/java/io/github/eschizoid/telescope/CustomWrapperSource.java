package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Rename;

/**
 * faithful reproduction of the reported scenario: a @Data-style bean whose nested field is a custom
 * collection wrapper ({@code class CustomWrapperSrcUrls extends ArrayList<CustomWrapperSrcUrl>}),
 * bridged with {@code lenient = true} and a {@code @Rename} on a scalar sibling. Before the fix the
 * processor bean-introspected the wrapper and failed with "no setter for 'empty'".
 */
@Bridge(
  value = CustomWrapperTarget.class,
  lenient = true,
  renames = { @Rename(source = "icVerificationExt", target = "vendorExtendedResult") }
)
public class CustomWrapperSource {

  private CustomWrapperSrcUrls imageUrls;
  private String icVerificationExt;

  public CustomWrapperSrcUrls getImageUrls() {
    return imageUrls;
  }

  public void setImageUrls(final CustomWrapperSrcUrls imageUrls) {
    this.imageUrls = imageUrls;
  }

  public String getIcVerificationExt() {
    return icVerificationExt;
  }

  public void setIcVerificationExt(final String icVerificationExt) {
    this.icVerificationExt = icVerificationExt;
  }
}
