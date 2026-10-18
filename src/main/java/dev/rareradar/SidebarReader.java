package dev.rareradar;

import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.Team;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the text of the scoreboard sidebar the server sends (what vanilla draws on the right of the screen), as
 * InGameHud does: each entry's team-decorated name. Read-only; nothing is sent to the server.
 */
public final class SidebarReader {
    private static final int SIDEBAR_ID = 1; // ScoreboardDisplaySlot: LIST 0, SIDEBAR 1, BELOW_NAME 2
    private static volatile RecordComponent ownerComponent;
    private static boolean failed;

    private SidebarReader() {}

    /** Sidebar lines (title first), or an empty list when there is no sidebar or it can't be read. */
    public static List<String> lines(MinecraftClient client) {
        List<String> out = new ArrayList<>();
        if (failed || client.world == null) return out;
        try {
            var board = client.world.getScoreboard();
            var objective = board.getObjectiveForSlot(ScoreboardDisplaySlot.FROM_ID.apply(SIDEBAR_ID));
            if (objective == null) return out;
            out.add(objective.getDisplayName().getString());
            for (ScoreboardEntry e : board.getScoreboardEntries(objective)) {
                if (e.hidden()) continue;
                String owner = owner(e);
                var team = owner == null ? null : board.getScoreHolderTeam(owner);
                out.add(Team.decorateName(team, e.name()).getString());
            }
        } catch (LinkageError | RuntimeException | ReflectiveOperationException t) {
            failed = true;
            CobblemonCompat.warnOnce("sidebar", t);
        }
        return out;
    }

    /** The entry's score-holder name: ScoreboardEntry is a record whose accessors Yarn leaves unmapped. */
    private static String owner(ScoreboardEntry e) throws ReflectiveOperationException {
        RecordComponent c = ownerComponent;
        if (c == null) {
            for (RecordComponent rc : e.getClass().getRecordComponents()) if (rc.getType() == String.class) { c = rc; break; }
            if (c == null) return null;
            ownerComponent = c;
        }
        return (String) c.getAccessor().invoke(e);
    }
}
