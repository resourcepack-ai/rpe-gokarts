package ai.resourcepack.addons.gokarts.track;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The generated circuit: long enough to race on, and never so close to itself that two stretches share a wall. */
class CircuitTest {
    private final Circuit c = Circuit.through(Circuit.DEFAULT_POINTS, TrackBuilder.SCALE);

    @Test
    void aLapIsAboutThreeHundredBlocks() {
        assertTrue(c.length() > 280 && c.length() < 400, "lap " + c.length());
    }

    @Test
    void separateStretchesNeverShareAWall() {
        // Road, kerb and wall on both sides, and a block of grass between.
        double edge = TrackBuilder.HALF + TrackBuilder.KERB + TrackBuilder.WALL;
        double closest = c.closestApproach(40);
        assertTrue(closest > 2 * edge + 1, "closest approach " + closest);
    }

    @Test
    void samplesAreEvenAndDirectionsUnit() {
        List<Circuit.Sample> s = c.samples();
        for (int i = 1; i < s.size(); i++) {
            Circuit.Sample a = s.get(i - 1), b = s.get(i);
            assertEquals(Circuit.STEP, b.s() - a.s(), 1e-9);
            assertTrue(Math.hypot(b.u() - a.u(), b.v() - a.v()) < Circuit.STEP * 1.5);
            assertEquals(1, Math.hypot(a.du(), a.dv()), 1e-6);
        }
    }

    @Test
    void theStraightestPlacesAreStraightAndApart() {
        List<Double> pads = c.straightest(2, 30, List.of(0.0));
        assertEquals(2, pads.size());
        for (double p : pads) assertTrue(c.bend(p, 10) < 0.35, "bend at " + p + " is " + c.bend(p, 10));
        double gap = Math.min(c.ahead(pads.get(0), pads.get(1)), c.ahead(pads.get(1), pads.get(0)));
        assertTrue(gap >= 30);
    }

    @Test
    void aheadWrapsRoundTheLoop() {
        assertEquals(10, c.ahead(c.length() - 5, 5), 1e-9);
        assertEquals(c.length() - 10, c.ahead(5, c.length() - 5), 1e-9);
    }
}
