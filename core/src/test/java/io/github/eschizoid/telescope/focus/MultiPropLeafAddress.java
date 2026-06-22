package io.github.eschizoid.telescope.focus;

import io.github.eschizoid.telescope.annotations.BeanFocus;

/**
 * Multi-property {@code @BeanFocus} POJO (no-arg ctor + setters strategy) used as a nullable nested
 * intermediate on a write path. The focused property ({@link #cityName}) is what a write targets;
 * the off-path {@link #countryName} (reference) and {@link #zipCode} (primitive) are read from the
 * previous instance during the generated lens rebuild. When the intermediate is {@code null} those
 * off-path reads must not NPE — the reference stays at its JLS default ({@code null}) and the
 * primitive at its JLS default ({@code 0}).
 */
@BeanFocus
public class MultiPropLeafAddress {

  private String cityName;
  private String countryName;
  private int zipCode;
  private boolean active;
  private char grade;

  public MultiPropLeafAddress() {}

  public String getCityName() {
    return cityName;
  }

  public void setCityName(final String cityName) {
    this.cityName = cityName;
  }

  public String getCountryName() {
    return countryName;
  }

  public void setCountryName(final String countryName) {
    this.countryName = countryName;
  }

  public int getZipCode() {
    return zipCode;
  }

  public void setZipCode(final int zipCode) {
    this.zipCode = zipCode;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(final boolean active) {
    this.active = active;
  }

  public char getGrade() {
    return grade;
  }

  public void setGrade(final char grade) {
    this.grade = grade;
  }
}
