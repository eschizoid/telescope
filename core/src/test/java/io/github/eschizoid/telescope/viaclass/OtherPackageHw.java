package io.github.eschizoid.telescope.viaclass;

/**
 * A class outside the package of the rows naming it, whose {@code forward} taking a {@link Coin} is
 * package-private: code in another package, the generated bridge among it, calls only the public
 * {@code forward} taking any value.
 */
public final class OtherPackageHw {

  private OtherPackageHw() {}

  public record Coin(String name) {}

  public record CoinDto(String name) {}

  public record Purse(Coin coin) {}

  public record PurseDto(CoinDto coin) {}

  static CoinDto forward(final Coin coin) {
    return new CoinDto("coin:" + coin.name());
  }

  public static CoinDto forward(final Object value) {
    return new CoinDto("object:" + ((Coin) value).name());
  }

  public static Coin backward(final CoinDto dto) {
    return new Coin(dto.name());
  }
}
