package kz.asyltas.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class Format {
  private Format() {}

  public static String weight(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  public static String money(BigDecimal value) {
    DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
    symbols.setGroupingSeparator(' ');
    return new DecimalFormat("#,##0.##", symbols).format(value) + " ₸";
  }
}
