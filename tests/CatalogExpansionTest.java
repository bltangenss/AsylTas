import java.math.BigDecimal;
import java.util.*;
import kz.asyltas.model.*;

/** Compatibility and exact arithmetic for newly selectable catalogue IDs. */
public class CatalogExpansionTest {
  private static int checks;
  private static void check(boolean value, String label) {
    checks++;
    if (!value) throw new AssertionError(label);
  }

  public static void main(String[] args) {
    Set<Integer> ids = new HashSet<>();
    Set<String> names = new HashSet<>();
    Set<String> colors = new HashSet<>();
    for (Stone stone : Catalog.all()) {
      check(ids.add(stone.getId()), "unique ID " + stone.getId());
      check(names.add(stone.getName()), "distinct material " + stone.getId());
      check(colors.add(stone.getColor()), "unique legacy photo key " + stone.getId());
      check(Catalog.get(stone.getId()) == stone, "stable lookup " + stone.getId());
      Necklace necklace = new Necklace();
      necklace.add(stone, 99);
      check(necklace.totalWeight().compareTo(stone.getCarats().multiply(new BigDecimal("99"))) == 0, "exact weight " + stone.getId());
      check(necklace.totalCost().compareTo(stone.getCost().multiply(new BigDecimal("99"))) == 0, "exact cost " + stone.getId());
      Necklace restored = new Necklace();
      restored.restore(necklace.encode());
      check(restored.encode().equals(necklace.encode()), "new ID roundtrip " + stone.getId());
    }
    check(ids.size() == 48 && Collections.min(ids) == 1 && Collections.max(ids) == 48, "continuous 48 IDs");
    Necklace old = new Necklace();
    old.restore("2:2;3:1;5:3;");
    check(old.count() == 6, "old project count");
    check(old.totalWeight().compareTo(new BigDecimal("5.90")) == 0, "old project weight");
    check(old.totalCost().compareTo(new BigDecimal("616200")) == 0, "old project cost");
    old.add(Catalog.get(48), 3);
    check(old.totalWeight().compareTo(new BigDecimal("12.80")) == 0, "mixed old/new weight");
    check(old.totalCost().compareTo(new BigDecimal("643800")) == 0, "mixed old/new cost");
    String before = old.encode();
    try { old.restore("49:1;"); throw new AssertionError("unknown ID accepted"); }
    catch (IllegalArgumentException expected) { check(before.equals(old.encode()), "invalid restore preserves project"); }
    try { Catalog.all().clear(); throw new AssertionError("mutable catalogue"); }
    catch (UnsupportedOperationException expected) { checks++; }
    System.out.println("PASS: " + checks + " catalogue expansion checks; 48 stable IDs.");
  }
}
