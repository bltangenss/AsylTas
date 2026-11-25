package kz.asyltas.model;

import java.math.BigDecimal;

/** Тастардың ортақ ата-аналық класы. Сандар оқу мысалы ретінде берілген. */
public abstract class Stone {
  private final int id, transparency;
  private final String name, color;
  private final BigDecimal carats, pricePerCarat;

  protected Stone(
      int id, String name, String carats, String price, int transparency, String color) {
    if (id <= 0 || name == null || name.trim().isEmpty())
      throw new IllegalArgumentException("Тастың атауы мен нөмірі дұрыс болуы керек.");
    this.carats = new BigDecimal(carats);
    this.pricePerCarat = new BigDecimal(price);
    if (this.carats.signum() <= 0
        || this.pricePerCarat.signum() < 0
        || transparency < 0
        || transparency > 100)
      throw new IllegalArgumentException("Салмақ, баға немесе мөлдірлік дұрыс емес.");
    this.id = id;
    this.name = name;
    this.transparency = transparency;
    this.color = color;
  }

  public abstract String getCategory();

  public int getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public BigDecimal getCarats() {
    return carats;
  }

  public BigDecimal getPricePerCarat() {
    return pricePerCarat;
  }

  public int getTransparency() {
    return transparency;
  }

  public String getColor() {
    return color;
  }

  public BigDecimal getCost() {
    return carats.multiply(pricePerCarat);
  }
}
