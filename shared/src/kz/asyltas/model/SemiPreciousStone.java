package kz.asyltas.model;

public final class SemiPreciousStone extends Stone {
  public SemiPreciousStone(
      int id, String name, String carats, String price, int transparency, String color) {
    super(id, name, carats, price, transparency, color);
  }

  @Override
  public String getCategory() {
    return "Жартылай асыл тас";
  }
}
