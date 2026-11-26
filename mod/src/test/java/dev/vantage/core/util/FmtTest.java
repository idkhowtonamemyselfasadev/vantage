package dev.vantage.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FmtTest {
    private Locale saved;

    @BeforeEach
    void germanDefault() {
        saved = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY); // a German install must still get a point
    }

    @AfterEach
    void restore() {
        Locale.setDefault(saved);
    }

    @Test
    void distanceSwitchesToKilometresOnTheRoundedValue() {
        assertEquals("0 m", Fmt.distance(0));
        assertEquals("350 m", Fmt.distance(350));
        assertEquals("13 m", Fmt.distance(12.6));
        assertEquals("999 m", Fmt.distance(999));
        assertEquals("999 m", Fmt.distance(999.4));
        assertEquals("1.0 km", Fmt.distance(999.5));
        assertEquals("1.0 km", Fmt.distance(1000));
        assertEquals("1.2 km", Fmt.distance(1234));
        assertEquals("12.3 km", Fmt.distance(12_345));
        assertEquals("-350 m", Fmt.distance(-350));
        assertEquals("-1.2 km", Fmt.distance(-1234));
        assertEquals("0 m", Fmt.distance(-0.2));
        assertEquals("NaN", Fmt.distance(Double.NaN));
        assertEquals("Infinity", Fmt.distance(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", Fmt.distance(Double.NEGATIVE_INFINITY));
    }

    @Test
    void countSwitchesAtEachThousand() {
        assertEquals("0", Fmt.count(0));
        assertEquals("999", Fmt.count(999));
        assertEquals("999", Fmt.count(999.4));
        assertEquals("1.0k", Fmt.count(999.5));
        assertEquals("1.0k", Fmt.count(1000));
        assertEquals("1.2k", Fmt.count(1234));
        assertEquals("999.9k", Fmt.count(999_949));
        assertEquals("1.0M", Fmt.count(999_950));
        assertEquals("1.0M", Fmt.count(1_000_000));
        assertEquals("3.4M", Fmt.count(3_400_000));
        assertEquals("1.0B", Fmt.count(999_950_000d));
        assertEquals("2.5B", Fmt.count(2.5e9));
        assertEquals("1.0T", Fmt.count(1e12));
        assertEquals("1000.0T", Fmt.count(1e15)); // nothing beyond T
        assertEquals("-1.2k", Fmt.count(-1234));
        assertEquals("-999", Fmt.count(-999));
        assertEquals("0", Fmt.count(-0.2));
        assertEquals("NaN", Fmt.count(Double.NaN));
        assertEquals("-Infinity", Fmt.count(Double.NEGATIVE_INFINITY));
    }

    @Test
    void moneyHasThreeStyles() {
        assertEquals("$0", Fmt.money(0));
        assertEquals("$999", Fmt.money(999));
        assertEquals("$1,234", Fmt.money(1234));
        assertEquals("$9,999", Fmt.money(9999));
        assertEquals("$10.0K", Fmt.money(10_000));
        assertEquals("$12.3K", Fmt.money(12_345));
        assertEquals("$999.9K", Fmt.money(999_949));
        assertEquals("$1.00M", Fmt.money(999_950));
        assertEquals("$1.20M", Fmt.money(1_200_000));
        assertEquals("$3.40B", Fmt.money(3.4e9));
        assertEquals("$1.00B", Fmt.money(999_995_000d));
        assertEquals("-$1,234", Fmt.money(-1234));
        assertEquals("-$1.50M", Fmt.money(-1_500_000));
        assertEquals("$NaN", Fmt.money(Double.NaN));
        assertEquals("$Infinity", Fmt.money(Double.POSITIVE_INFINITY));
    }

    @Test
    void durationUsesTheTwoLargestUnits() {
        assertEquals("0s", Fmt.duration(0));
        assertEquals("0s", Fmt.duration(999));
        assertEquals("1s", Fmt.duration(1000));
        assertEquals("59s", Fmt.duration(59_999));
        assertEquals("1m 0s", Fmt.duration(60_000));
        assertEquals("3m 4s", Fmt.duration(184_000));
        assertEquals("59m 59s", Fmt.duration(3_599_000));
        assertEquals("1h 0m", Fmt.duration(3_600_000));
        assertEquals("2h 5m", Fmt.duration(7_500_000));
        assertEquals("23h 59m", Fmt.duration(86_399_000));
        assertEquals("1d 0h", Fmt.duration(86_400_000));
        assertEquals("3d 4h", Fmt.duration(3 * 86_400_000L + 4 * 3_600_000L));
        assertEquals("0s", Fmt.duration(-5000));
    }

    @Test
    void fixedUsesAPoint() {
        assertEquals("1.50", Fmt.fixed(1.5, 2));
        assertEquals("-0.3", Fmt.fixed(-0.25, 1)); // half up
        assertEquals("3", Fmt.fixed(2.5, 0));
        assertEquals("NaN", Fmt.fixed(Double.NaN, 1));
    }

    @Test
    void moneyShortIsTheCompactLabelForm() {
        assertEquals("$750", Fmt.moneyShort(750));
        assertEquals("$0.5", Fmt.moneyShort(0.5));
        assertEquals("$1.5K", Fmt.moneyShort(1500));
        assertEquals("$2M", Fmt.moneyShort(2_000_000));
        assertEquals("$1.2B", Fmt.moneyShort(1_200_000_000d));
        assertEquals("-$1.5K", Fmt.moneyShort(-1500));
        assertEquals("$NaN", Fmt.moneyShort(Double.NaN));
    }
}
