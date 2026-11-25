import java.math.BigDecimal;
import java.nio.file.*;
import kz.asyltas.desktop.LocalStore;
import kz.asyltas.model.*;

public class ModelTest {
  static int checks;

  static void check(boolean ok, String name) {
    checks++;
    if (!ok) throw new AssertionError(name);
  }

  static void rejects(Runnable action, String name) {
    try {
      action.run();
      throw new AssertionError(name);
    } catch (IllegalArgumentException expected) {
      checks++;
    }
  }

  static boolean equal(BigDecimal a, String b) {
    return a.compareTo(new BigDecimal(b)) == 0;
  }

  public static void main(String[] args) throws Exception {
    check(Catalog.all().size() == 48, "catalog size");
    check(
        Catalog.all().stream().filter(s -> s instanceof PreciousStone).count() == 5,
        "precious count");
    check(
        Catalog.all().stream().filter(s -> s instanceof SemiPreciousStone).count() == 43,
        "semi count");
    check(Catalog.get(1).getCategory().equals("Асыл тас"), "polymorphism precious");
    check(Catalog.get(5).getCategory().equals("Жартылай асыл тас"), "polymorphism semi");
    rejects(() -> Catalog.get(999), "unknown stone");
    rejects(() -> new PreciousStone(99, "Test", "-1", "10", 50, "#000000"), "negative weight");
    rejects(() -> new SemiPreciousStone(99, "Test", "1", "-10", 50, "#000000"), "negative price");
    rejects(() -> new PreciousStone(99, "Test", "1", "10", 101, "#000000"), "bad transparency");
    Necklace n = new Necklace();
    check(n.count() == 0 && equal(n.totalCost(), "0") && equal(n.totalWeight(), "0"), "empty");
    n.demo();
    check(n.count() == 6, "demo count");
    check(equal(n.totalWeight(), "5.90"), "demo carats");
    check(equal(n.totalCost(), "616200"), "demo cost");
    check(n.find(80, 95, true).size() == 2, "range kinds");
    check(
        n.find(80, 95, true).stream().mapToInt(NecklaceItem::getQuantity).sum() == 3,
        "range physical count");
    check(n.find(80, 80, true).get(0).getStone().getId() == 3, "inclusive same bounds");
    check(n.find(92, 92, true).get(0).getStone().getId() == 2, "upper boundary");
    check(n.find(0, 10, true).isEmpty(), "no matches");
    check(n.find(0, 100, true).get(0).getStone().getId() == 2, "descending");
    check(n.find(0, 100, false).get(0).getStone().getId() == 5, "ascending");
    check(n.count() == 6 && equal(n.totalCost(), "616200"), "search does not mutate");
    rejects(() -> n.find(90, 80, true), "reverse range");
    rejects(() -> n.find(-1, 80, true), "negative bound");
    rejects(() -> n.find(0, 101, true), "high bound");
    n.add(Catalog.get(2), 1);
    check(n.count() == 7 && n.all().size() == 3, "merge same stone");
    n.setQuantity(2, 1);
    check(n.count() == 5, "edit quantity");
    rejects(() -> n.setQuantity(2, 0), "zero count");
    rejects(() -> n.add(Catalog.get(2), 100), "too many");
    rejects(() -> n.add(Catalog.get(2), -1), "negative count");
    n.setQuantity(2, 99);
    rejects(() -> n.add(Catalog.get(2), 1), "cumulative limit");
    check(n.all().get(0).getQuantity() == 99, "invalid add atomic");
    n.remove(2);
    check(n.count() == 4, "remove kind");
    n.demo();
    String data = n.encode();
    Necklace copy = new Necklace();
    copy.restore(data);
    check(copy.encode().equals(data), "roundtrip");
    rejects(() -> n.restore("999:2;"), "unknown saved ID");
    check(n.encode().equals(data), "invalid restore atomic");
    rejects(() -> n.restore("2:x;"), "bad saved quantity");
    rejects(() -> n.restore("2:0;"), "zero saved quantity");
    Path dir = Files.createTempDirectory("asyl-tas-test-");
    Path file = dir.resolve("necklace.txt");
    LocalStore store = new LocalStore(file);
    store.save(n);
    Necklace loaded = new Necklace();
    store.load(loaded);
    check(loaded.encode().equals(data), "file persistence");
    loaded.clear();
    store.save(loaded);
    store.load(copy);
    check(copy.count() == 0, "save empty");
    Files.delete(file);
    Files.delete(dir);
    n.clear();
    check(n.count() == 0 && n.all().isEmpty(), "clear");
    n.add(Catalog.get(1), 3);
    check(equal(n.totalCost(), "945000") && equal(n.totalWeight(), "2.25"), "decimal exactness");
    check(Format.money(new BigDecimal("616200")).equals("616 200 ₸"), "money display");
    check(Format.weight(new BigDecimal("5.9")).equals("5.90"), "weight display");
    System.out.println("PASS: " + checks + " checks; sample 6 stones / 5.90 ct / 616200 KZT.");
  }
}
