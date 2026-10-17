package dev.rareradar.core.market;

import java.util.*;

/**
 * Removes double-counted asking prices before statistics are computed. A chat announcement and the GTS screen card
 * for the same listing (or the same listing announced twice) would otherwise inflate listing counts and liquidity.
 * Rule: a chat-sourced LISTING is dropped when another LISTING with the same species/form/shiny/price exists within
 * 24 hours (GTS-screen cards are kept in preference because they carry IVs/nature). Separate GTS-screen cards are
 * never merged — they have distinct listing ids even at equal prices. Sales are never touched.
 */
public final class Listings {
    static final long WINDOW_MS = 24 * 3600_000L;

    private Listings() {}

    static boolean fromChat(PriceObservation p) { return p.note != null && p.note.startsWith("chat:"); }

    public static List<PriceObservation> distinct(List<PriceObservation> obs) {
        boolean anyChat = false;
        for (PriceObservation p : obs) if (p.kind == PriceObservation.Kind.LISTING && fromChat(p)) { anyChat = true; break; }
        if (!anyChat) return obs;
        Map<String, List<PriceObservation>> keep = new HashMap<>();
        List<PriceObservation> out = new ArrayList<>(obs.size());
        // Non-chat observations first so chat duplicates of a screen card are the ones dropped.
        List<PriceObservation> ordered = new ArrayList<>(obs);
        ordered.sort(Comparator.comparing(Listings::fromChat).thenComparingLong(p -> p.timestamp));
        for (PriceObservation p : ordered) {
            if (p.kind != PriceObservation.Kind.LISTING) { out.add(p); continue; }
            String k = p.species + "|" + p.form + "|" + Boolean.TRUE.equals(p.shiny) + "|" + Math.round(p.price);
            List<PriceObservation> same = keep.computeIfAbsent(k, x -> new ArrayList<>());
            if (fromChat(p)) {
                boolean dup = false;
                for (PriceObservation q : same) if (Math.abs(q.timestamp - p.timestamp) <= WINDOW_MS) { dup = true; break; }
                if (dup) continue;
            }
            same.add(p);
            out.add(p);
        }
        return out;
    }
}
