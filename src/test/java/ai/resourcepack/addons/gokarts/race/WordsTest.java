package ai.resourcepack.addons.gokarts.race;

import ai.resourcepack.addons.gokarts.text.Text;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The few words the plugin works out rather than reads from config.yml. */
class WordsTest {
    @Test
    void places() {
        assertEquals("1ST", Hud.ordinal(1));
        assertEquals("2ND", Hud.ordinal(2));
        assertEquals("3RD", Hud.ordinal(3));
        assertEquals("4TH", Hud.ordinal(4));
        assertEquals("11TH", Hud.ordinal(11));
        assertEquals("12TH", Hud.ordinal(12));
        assertEquals("21ST", Hud.ordinal(21));
    }

    @Test
    void raceClockIsToTheHundredth() {
        assertEquals("0:00.00", Text.raceClock(0));
        assertEquals("0:00.05", Text.raceClock(1));
        assertEquals("0:01.00", Text.raceClock(20));
        assertEquals("1:23.45", Text.raceClock(1669));
    }

    @Test
    void colours() {
        assertEquals(KartColour.CYAN, KartColour.ofVehicle("kart_cyan"));
        assertNull(KartColour.ofVehicle("kart_cyan_item"));
        assertNull(KartColour.ofVehicle("item_box"));
        assertEquals("kart_red_item", KartColour.RED.item());
    }
}
