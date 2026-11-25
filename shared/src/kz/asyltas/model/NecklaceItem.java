package kz.asyltas.model;

import java.math.BigDecimal;

/** Бір жолда бір тас түрі мен оның саны сақталады. */
public final class NecklaceItem {
  private final Stone stone;
  private final int quantity;

  public NecklaceItem(Stone stone, int quantity) {
    if (stone == null || quantity < 1 || quantity > 99)
      throw new IllegalArgumentException("Тас саны 1–99 аралығында болуы керек.");
    this.stone = stone;
    this.quantity = quantity;
  }

  public Stone getStone() {
    return stone;
  }

  public int getQuantity() {
    return quantity;
  }

  public BigDecimal getWeight() {
    return stone.getCarats().multiply(BigDecimal.valueOf(quantity));
  }

  public BigDecimal getCost() {
    return stone.getCost().multiply(BigDecimal.valueOf(quantity));
  }
}
