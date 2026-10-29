package ai.resourcepack.addons.gokarts.track;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four prefabs: that they load, that the Java spline agrees with
 * tools/prefabs.py (which draws the minimaps from the same files), and that no
 * two stretches of road share a wall unless one passes over the other.
 */
class PrefabTest {
    /** Lap lengths tools/prefabs.py measures; the minimaps are drawn to these. */
    private static final Map<String, Double> LAP = Map.of("meadow", 458.0, "desert", 528.0, "frost", 544.0, "neon", 742.0);

    @Test
    void everyPrefabLoadsAndMatchesThePythonGeometry() throws Exception {
        for (String id : Prefab.IDS) {
            Prefab p = Prefab.load(id);
            assertEquals(LAP.get(id), p.roads.get(0).length(), 1.0, id + " lap");
            assertEquals(1 + p.branches.size(), p.roads.size());
            assertTrue(p.roads.get(0).closed());
        }
    }

    @Test
    void branchesLeaveAndRejoinTheLoop() throws Exception {
        for (String id : Prefab.IDS) {
            Prefab p = Prefab.load(id);
            Road main = p.roads.get(0);
            for (int b = 0; b < p.branches.size(); b++) {
                Road r = p.roads.get(b + 1);
                Road.Sample from = main.at(p.branches.get(b).from() * main.length());
                Road.Sample to = main.at(p.branches.get(b).to() * main.length());
                Road.Sample first = r.samples().get(0), last = r.samples().get(r.samples().size() - 1);
                assertTrue(Math.hypot(first.u() - from.u(), first.v() - from.v()) < 1, id + " branch start");
                assertTrue(Math.hypot(last.u() - to.u(), last.v() - to.v()) < 1, id + " branch end");
            }
        }
    }

    @Test
    void roadsNeverShareAWallUnlessOnePassesOver() throws Exception {
        for (String id : Prefab.IDS) {
            Prefab p = Prefab.load(id);
            double outer = p.half + PrefabBuilder.KERB + PrefabBuilder.WALL;
            List<Road> roads = p.roads;
            for (int i = 0; i < roads.size(); i++) {
                for (int j = i; j < roads.size(); j++) {
                    Road a = roads.get(i), b = roads.get(j);
                    for (int x = 0; x < a.samples().size(); x += 8) {
                        Road.Sample s = a.samples().get(x);
                        for (int y = 0; y < b.samples().size(); y += 8) {
                            Road.Sample t = b.samples().get(y);
                            if (i == j && Math.abs(a.offset(s.s(), t.s())) < 40) continue;
                            if (i != j && i == 0) {
                                Road.Sample bs = b.samples().get(0), be = b.samples().get(b.samples().size() - 1);
                                if (Math.min(Math.hypot(t.u() - bs.u(), t.v() - bs.v()), Math.hypot(t.u() - be.u(), t.v() - be.v())) < 30) continue;
                            }
                            if (Math.abs(p.point(i, s).h() - p.point(j, t).h()) >= 6) continue;
                            double d = Math.hypot(s.u() - t.u(), s.v() - t.v());
                            assertTrue(d > 2 * outer + 1, id + ": roads " + i + " and " + j + " come " + d + " apart at "
                                    + Math.round(s.u()) + "," + Math.round(s.v()));
                        }
                    }
                }
            }
        }
    }

    @Test
    void aRampRisesToItsLipThenStops() throws Exception {
        Prefab p = Prefab.load("meadow");
        Prefab.Ramp ramp = p.ramps.get(0);
        Road r = p.roads.get(ramp.on());
        double lip = ramp.lip() * r.length();
        Road.Sample lipSample = r.at(lip - 0.25);
        Prefab.Point before = p.point(0, r.at(lip - ramp.length() - 2));
        Prefab.Point top = p.point(0, lipSample);
        Prefab.Point inGap = p.point(0, r.at(lip + ramp.gap() / 2));
        Prefab.Point after = p.point(0, r.at(lip + ramp.gap() + 2));
        // What the ramp adds, over whatever the road itself is doing there (this one runs downhill into it).
        assertEquals(ramp.rise(), top.h() - lipSample.h(), 0.05, "the lip is risen");
        assertEquals(0, before.h() - r.at(lip - ramp.length() - 2).h(), 1e-9, "nothing is added before the ramp");
        assertTrue(top.lip());
        assertTrue(inGap.gap());
        assertFalse(after.gap());
        assertTrue(top.h() - after.h() > ramp.rise() - 0.8, "the landing is lower than the lip");
    }
}
