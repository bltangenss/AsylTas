package kz.asyltas.model;

import java.util.*;

/** Каталогтағы баға мен мөлдірлік — нарықтық бағалау емес, оқу деректері. */
public final class Catalog {
  private static final List<Stone> STONES =
      Collections.unmodifiableList(
          Arrays.asList(
              new PreciousStone(1, "Гауһар", "0.75", "420000", 98, "#76B7C4"),
              new PreciousStone(2, "Лағыл", "1.20", "180000", 92, "#BE4662"),
              new PreciousStone(3, "Зүбәржат", "1.10", "150000", 80, "#26896D"),
              new PreciousStone(4, "Жақұт", "1.30", "140000", 90, "#416BA9"),
              new SemiPreciousStone(5, "Аметист", "0.80", "8000", 75, "#9374B6"),
              new SemiPreciousStone(6, "Топаз", "1.50", "12000", 85, "#CA9A40"),
              new SemiPreciousStone(7, "Бирюза", "2.00", "6500", 40, "#48A9A4"),
              new SemiPreciousStone(8, "Яшма", "1.80", "3500", 20, "#B9674A"),
              new SemiPreciousStone(9, "Агат", "1.60", "4500", 55, "#A18069"),
              new SemiPreciousStone(10, "Аквамарин", "1.25", "18000", 88, "#65A9C6"),
              new SemiPreciousStone(11, "Оникс", "1.40", "5000", 65, "#52606C"),
              new SemiPreciousStone(12, "Цитрин", "1.15", "7000", 70, "#C09A38")));

  private Catalog() {}

  public static List<Stone> all() {
    return STONES;
  }

  public static Stone get(int id) {
    for (Stone stone : STONES) if (stone.getId() == id) return stone;
    throw new IllegalArgumentException("Каталогта мұндай тас жоқ.");
  }
}
