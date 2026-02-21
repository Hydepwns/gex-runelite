package foo.droo.gex;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GpFormatterTest {

    @Test
    public void testZero() {
        assertEquals("+0", GpFormatter.format(0));
    }

    @Test
    public void testSmallPositive() {
        assertEquals("+500", GpFormatter.format(500));
    }

    @Test
    public void testSmallNegative() {
        assertEquals("-999", GpFormatter.format(-999));
    }

    @Test
    public void testThousands() {
        assertEquals("+1.0K", GpFormatter.format(1_000));
        assertEquals("+52.3K", GpFormatter.format(52_300));
        assertEquals("-10.5K", GpFormatter.format(-10_500));
    }

    @Test
    public void testMillions() {
        assertEquals("+1.0M", GpFormatter.format(1_000_000));
        assertEquals("+3.5M", GpFormatter.format(3_500_000));
        assertEquals("-2.1M", GpFormatter.format(-2_100_000));
    }

    @Test
    public void testBoundaries() {
        assertEquals("+999", GpFormatter.format(999));
        assertEquals("+1.0K", GpFormatter.format(1_000));
        assertEquals("+1000.0K", GpFormatter.format(999_999));
        assertEquals("+1.0M", GpFormatter.format(1_000_000));
    }
}
