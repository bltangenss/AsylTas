package kz.asyltas.android;

import java.math.BigDecimal;
import kz.asyltas.model.Stone;

/** Exact price/weight comparisons. Limits are inclusive; empty limits mean no bound. */
public final class CatalogFilters {
  public final String minPrice, maxPrice, minCarats, maxCarats;
  public final int minTransparency, maxTransparency;
  private final BigDecimal fromPrice, toPrice, fromCarats, toCarats;

  public CatalogFilters() { this("", "", "", "", 0, 100); }

  public CatalogFilters(String minPrice, String maxPrice, String minCarats, String maxCarats, int minTransparency, int maxTransparency) {
    this.minPrice = clean(minPrice); this.maxPrice = clean(maxPrice); this.minCarats = clean(minCarats); this.maxCarats = clean(maxCarats);
    fromPrice = decimal(this.minPrice); toPrice = decimal(this.maxPrice); fromCarats = decimal(this.minCarats); toCarats = decimal(this.maxCarats);
    if (fromPrice != null && toPrice != null && fromPrice.compareTo(toPrice) > 0) throw new IllegalArgumentException("Төменгі баға жоғарғы бағадан аспасын.");
    if (fromCarats != null && toCarats != null && fromCarats.compareTo(toCarats) > 0) throw new IllegalArgumentException("Төменгі салмақ жоғарғы салмақтан аспасын.");
    if (minTransparency < 0 || maxTransparency > 100 || minTransparency > maxTransparency) throw new IllegalArgumentException("Мөлдірлік шектері 0–100% аралығында болуы керек.");
    this.minTransparency = minTransparency; this.maxTransparency = maxTransparency;
  }

  private static String clean(String value) { return value == null ? "" : value.trim().replace(',', '.'); }
  private static BigDecimal decimal(String value) {
    if (value.isEmpty()) return null;
    if (!value.matches("\\d{1,12}(\\.\\d{1,2})?")) throw new IllegalArgumentException("Оң сан енгізіңіз: үтірден кейін 2 таңбаға дейін.");
    return new BigDecimal(value);
  }
  public boolean active() { return fromPrice != null || toPrice != null || fromCarats != null || toCarats != null || minTransparency > 0 || maxTransparency < 100; }
  private boolean within(BigDecimal value, BigDecimal min, BigDecimal max) { return (min == null || value.compareTo(min) >= 0) && (max == null || value.compareTo(max) <= 0); }
  public boolean matches(Stone stone) { return within(stone.getCost(), fromPrice, toPrice) && within(stone.getCarats(), fromCarats, toCarats) && stone.getTransparency() >= minTransparency && stone.getTransparency() <= maxTransparency; }
}
