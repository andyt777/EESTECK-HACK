package ro.filegrid.text;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** File sizes as Romanian text: {@code 512 B}, {@code 64 MB}, {@code 1,2 GB}. */
public final class Sizes {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    private Sizes() {
    }

    public static String format(long bytes) {
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(unit == 0 ? 0 : 1, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return rounded.toPlainString().replace('.', ',') + " " + UNITS[unit];
    }
}
