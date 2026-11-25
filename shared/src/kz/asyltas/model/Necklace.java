package kz.asyltas.model;

import java.math.BigDecimal;
import java.util.*;

/** Екі қосымшада да осы бір есептеу класы қолданылады. */
public final class Necklace {
  private final Map<Integer, NecklaceItem> items = new LinkedHashMap<>();

  public void add(Stone stone, int quantity) {
    if (quantity < 1 || quantity > 99)
      throw new IllegalArgumentException("Саны 1–99 аралығында болуы керек.");
    NecklaceItem old = items.get(stone.getId());
    setQuantity(stone.getId(), quantity + (old == null ? 0 : old.getQuantity()));
  }

  public void setQuantity(int id, int quantity) {
    items.put(id, new NecklaceItem(Catalog.get(id), quantity));
  }

  public void remove(int id) {
    items.remove(id);
  }

  public void clear() {
    items.clear();
  }

  public List<NecklaceItem> all() {
    return new ArrayList<>(items.values());
  }

  public int count() {
    int sum = 0;
    for (NecklaceItem item : items.values()) sum += item.getQuantity();
    return sum;
  }

  public BigDecimal totalWeight() {
    BigDecimal sum = BigDecimal.ZERO;
    for (NecklaceItem item : items.values()) sum = sum.add(item.getWeight());
    return sum;
  }

  public BigDecimal totalCost() {
    BigDecimal sum = BigDecimal.ZERO;
    for (NecklaceItem item : items.values()) sum = sum.add(item.getCost());
    return sum;
  }

  /** Екі шек те іздеу аралығына кіреді. Негізгі коллекция өзгермейді. */
  public List<NecklaceItem> find(int min, int max, boolean expensiveFirst) {
    if (min < 0 || max > 100 || min > max)
      throw new IllegalArgumentException(
          "Мөлдірлік 0–100% аралығында, төменгі шек жоғарғы шектен аспауы керек.");
    List<NecklaceItem> result = new ArrayList<>();
    for (NecklaceItem item : items.values()) {
      int value = item.getStone().getTransparency();
      if (value >= min && value <= max) result.add(item);
    }
    Comparator<NecklaceItem> comparator =
        Comparator.comparing(i -> i.getStone().getPricePerCarat());
    if (expensiveFirst) comparator = comparator.reversed();
    result.sort(comparator.thenComparingInt(i -> i.getStone().getId()));
    return result;
  }

  public void demo() {
    clear();
    add(Catalog.get(2), 2);
    add(Catalog.get(3), 1);
    add(Catalog.get(5), 3);
  }

  public String encode() {
    StringBuilder text = new StringBuilder();
    for (NecklaceItem item : items.values())
      text.append(item.getStone().getId()).append(':').append(item.getQuantity()).append(';');
    return text.toString();
  }

  /** Алдымен тексеріледі: бүлінген файл ағымдағы алқаны өшірмейді. */
  public void restore(String text) {
    Necklace restored = new Necklace();
    if (text == null) throw new IllegalArgumentException("Сақталған дерек оқылмады.");
    if (!text.isEmpty())
      for (String line : text.split(";")) {
        String[] pair = line.split(":");
        if (pair.length != 2) throw new IllegalArgumentException("Сақталған дерек дұрыс емес.");
        restored.add(Catalog.get(Integer.parseInt(pair[0])), Integer.parseInt(pair[1]));
      }
    items.clear();
    items.putAll(restored.items);
  }
}
