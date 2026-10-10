package io.github.eschizoid.telescope.frommapparity;

import io.github.eschizoid.telescope.annotations.FromMap;
import java.util.List;
import java.util.Optional;

/** The bean shape: a no-argument constructor and a setter per property. */
@FromMap
public class BackfillBean {

  private int count;
  private Boolean flag;
  private String text;
  private BackfillTone tone;
  private BackfillLeaf leaf;
  private List<Integer> numbers;
  private Optional<String> maybe;

  public int getCount() {
    return count;
  }

  public void setCount(final int count) {
    this.count = count;
  }

  public Boolean getFlag() {
    return flag;
  }

  public void setFlag(final Boolean flag) {
    this.flag = flag;
  }

  public String getText() {
    return text;
  }

  public void setText(final String text) {
    this.text = text;
  }

  public BackfillTone getTone() {
    return tone;
  }

  public void setTone(final BackfillTone tone) {
    this.tone = tone;
  }

  public BackfillLeaf getLeaf() {
    return leaf;
  }

  public void setLeaf(final BackfillLeaf leaf) {
    this.leaf = leaf;
  }

  public List<Integer> getNumbers() {
    return numbers;
  }

  public void setNumbers(final List<Integer> numbers) {
    this.numbers = numbers;
  }

  public Optional<String> getMaybe() {
    return maybe;
  }

  public void setMaybe(final Optional<String> maybe) {
    this.maybe = maybe;
  }
}
