package kz.asyltas.model;

import java.util.*;

/** Баға, салмақ және мөлдірлік — оқу деректері. Алғашқы 12 ID өзгермейді. */
public final class Catalog {
  private static final List<Stone> STONES = Collections.unmodifiableList(Arrays.asList(
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
      new SemiPreciousStone(12, "Цитрин", "1.15", "7000", 70, "#C09A38"),
      new SemiPreciousStone(13, "Гранат", "1.15", "18000", 82, "#983C48"),
      new SemiPreciousStone(14, "Опал", "1.40", "30000", 45, "#B7D7DB"),
      new SemiPreciousStone(15, "Хризолит", "1.10", "22000", 86, "#90AA52"),
      new SemiPreciousStone(16, "Турмалин", "1.30", "28000", 84, "#C37798"),
      new SemiPreciousStone(17, "Шпинель", "0.95", "46000", 88, "#AF527E"),
      new SemiPreciousStone(18, "Танзанит", "1.05", "65000", 90, "#6964A8"),
      new SemiPreciousStone(19, "Циркон", "1.20", "24000", 91, "#68B6BF"),
      new SemiPreciousStone(20, "Ай тасы", "1.65", "12000", 55, "#D1D7DB"),
      new SemiPreciousStone(21, "Күн тасы", "1.50", "11000", 60, "#C37C50"),
      new SemiPreciousStone(22, "Морганит", "1.25", "32000", 89, "#D6A2A3"),
      new SemiPreciousStone(23, "Кунцит", "1.45", "20000", 85, "#C998BC"),
      new PreciousStone(24, "Александрит", "0.60", "240000", 88, "#718873"),
      new SemiPreciousStone(25, "Раушан кварцы", "1.80", "5000", 50, "#DEA6B2"),
      new SemiPreciousStone(26, "Тау хрусталі", "1.70", "4000", 96, "#BCCAD1"),
      new SemiPreciousStone(27, "Түтінді кварц", "1.60", "5500", 70, "#8A7565"),
      new SemiPreciousStone(28, "Аметрин", "1.35", "12000", 88, "#A98C9C"),
      new SemiPreciousStone(29, "Халцедон", "1.85", "6000", 48, "#9BBCCA"),
      new SemiPreciousStone(30, "Сердолик", "1.60", "5500", 42, "#C16D4A"),
      new SemiPreciousStone(31, "Хризопраз", "1.75", "9000", 45, "#8AB795"),
      new SemiPreciousStone(32, "Лазурит", "2.10", "8500", 10, "#4365A0"),
      new SemiPreciousStone(33, "Нефрит", "2.20", "7000", 35, "#698D73"),
      new SemiPreciousStone(34, "Жадеит", "1.90", "19000", 42, "#57A37A"),
      new SemiPreciousStone(35, "Кәріптас", "1.35", "6000", 65, "#D39A4D"),
      new SemiPreciousStone(36, "Інжу", "1.50", "14000", 5, "#DDD5C5"),
      new SemiPreciousStone(37, "Малахит", "2.10", "6500", 5, "#48866C"),
      new SemiPreciousStone(38, "Жолбарыс көзі", "1.80", "5000", 15, "#AD874F"),
      new SemiPreciousStone(39, "Авантюрин", "1.90", "4500", 20, "#7E9E85"),
      new SemiPreciousStone(40, "Лабрадорит", "2.00", "8000", 30, "#5F899A"),
      new SemiPreciousStone(41, "Амазонит", "1.85", "6000", 15, "#73B0A1"),
      new SemiPreciousStone(42, "Иолит", "1.20", "17000", 85, "#7477A2"),
      new SemiPreciousStone(43, "Родонит", "1.95", "5500", 10, "#BC8191"),
      new SemiPreciousStone(44, "Родохрозит", "1.60", "12000", 35, "#D395A3"),
      new SemiPreciousStone(45, "Содалит", "1.85", "5000", 10, "#647DA5"),
      new SemiPreciousStone(46, "Флюорит", "1.75", "4500", 78, "#9694B8"),
      new SemiPreciousStone(47, "Апатит", "1.10", "13000", 80, "#65A3B1"),
      new SemiPreciousStone(48, "Гематит", "2.30", "4000", 0, "#666C75")
  ));

  private Catalog() {}
  public static List<Stone> all() { return STONES; }
  public static Stone get(int id) {
    for (Stone stone : STONES) if (stone.getId() == id) return stone;
    throw new IllegalArgumentException("Каталогта мұндай тас жоқ.");
  }
}
