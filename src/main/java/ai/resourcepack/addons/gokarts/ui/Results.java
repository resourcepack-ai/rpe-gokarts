package ai.resourcepack.addons.gokarts.ui;

import ai.resourcepack.addons.gokarts.GoKartsPlugin;
import ai.resourcepack.addons.gokarts.race.Race;
import ai.resourcepack.addons.gokarts.race.Records;
import ai.resourcepack.addons.gokarts.text.Text;
import ai.resourcepack.engine.api.Dialogs;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The results screen: the content folder's {@code dialogs/results.json}, a
 * Dialog-style document made for ResourcePack AI Studio
 * ({@code dialogs-src/dialogs.json}) and exported by its
 * {@code scripts/export-dialog-content.mjs}, filled in for whoever opens it.
 * Everybody in the race gets it at the end, and {@code /kart results} opens
 * the last one again.
 *
 * <p>The table is a Studio list called {@code all}: row k reads
 * {@code {all_k}} (the name, and the face beside it), {@code {all_k_place}},
 * {@code {all_k_time}} and {@code {all_k_best}}, and a row with no name is
 * hidden. Every word on it is from config.yml.
 */
public final class Results {
    private final GoKartsPlugin plugin;

    public Results(GoKartsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean open(Player p, Race race) {
        List<Race.Result> results = race.lastResults();
        if (results.isEmpty()) return false;
        Dialogs dialogs = plugin.content().engine().dialogs();
        var m = plugin.messages();
        Map<String, String> values = new HashMap<>();
        values.put("gk_r_title", m.plain("results-title"));
        values.put("gk_r_track", race.track().name());
        values.put("gk_r_laps", m.plain("results-laps", "laps", String.valueOf(race.laps())));
        Records.Entry lap = plugin.records().lap(race.track().id());
        values.put("gk_r_fastest", lap == null ? "" : m.plain("results-record", "time", Text.raceClock(lap.ticks()), "name", lap.name()));
        values.put("gk_h_place", m.plain("results-place"));
        values.put("gk_h_driver", m.plain("results-driver"));
        values.put("gk_h_time", m.plain("results-time"));
        values.put("gk_h_best", m.plain("results-best"));
        List<Map<String, String>> rows = new ArrayList<>();
        for (Race.Result r : results) {
            if (rows.size() == 8) break;
            Map<String, String> row = new HashMap<>();
            row.put("", r.name());
            row.put("place", r.dnf() ? "-" : String.valueOf(r.place()));
            row.put("time", r.dnf() ? m.plain("results-dnf") : Text.raceClock(r.ticks()));
            row.put("best", r.bestLap() < 0 ? "-" : Text.raceClock(r.bestLap()));
            rows.add(row);
        }
        dialogs.listRows(p, "all", rows);
        String id = plugin.settings().results;
        if (!dialogs.show(p, id, values)) {
            plugin.getLogger().warning("Could not open the results dialog " + id + " for " + p.getName()
                    + ". Is it in RP Engine's content (/rp reload)?");
            // Said in chat instead, so a race on an older client still has an ending.
            for (Race.Result r : results) {
                Text.send(p, Text.mm("<#9AA0A6>" + (r.dnf() ? "-" : r.place()) + ".</#9AA0A6> <#E8EAED>"
                        + Text.MM.escapeTags(r.name()) + "</#E8EAED> <#FFD23F>"
                        + (r.dnf() ? m.plain("results-dnf") : Text.raceClock(r.ticks())) + "</#FFD23F>"));
            }
            return false;
        }
        return true;
    }
}
