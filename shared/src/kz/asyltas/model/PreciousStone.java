package kz.asyltas.model;

/** Stone класынан мұра алады және санат әдісін қайта анықтайды. */
public final class PreciousStone extends Stone {
  public PreciousStone(
      int id, String name, String carats, String price, int transparency, String color) {
    super(id, name, carats, price, transparency, color);
  }

  @Override
  public String getCategory() {
    return "Асыл тас";
  }
}
