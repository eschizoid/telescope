package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Telescope.mapperForward;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.ForwardMapper;

/**
 * A bean whose nested property is a custom collection wrapper ({@code class CustomWrapperSrcUrls
 * extends ArrayList<CustomWrapperSrcUrl>}), bridged forward-only with a rename on a scalar sibling.
 * The generated bridge converts the wrapper element by element rather than reading it as a bean,
 * whose inherited {@code isEmpty()} has no setter.
 */
public class CustomWrapperSource {

  @Bridge
  public static final ForwardMapper<CustomWrapperSource, CustomWrapperTarget> TARGET = mapperForward(
    CustomWrapperSource.class,
    CustomWrapperTarget.class,
    to(CustomWrapperSource::getIcVerificationExt, CustomWrapperTarget::getVendorExtendedResult)
  );

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
