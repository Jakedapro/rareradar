package dev.rareradar.core.stats;

import dev.rareradar.core.store.JsonlStore;
import dev.rareradar.core.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** CSV/JSON export plus zip backup/restore of the whole RareRadar data directory. */
public final class DataExport {
    private DataExport() {}

    public static String toJson(Collection<?> rows) { return JsonlStore.GSON.toJson(rows); }

    /** Generic CSV from public fields of the row type (arrays/collections joined with '|'). */
    public static String toCsv(Collection<?> rows, Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field f : type.getFields()) if (!Modifier.isStatic(f.getModifiers())) fields.add(f);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) { if (i > 0) sb.append(','); sb.append(fields.get(i).getName()); }
        sb.append('\n');
        for (Object r : rows) {
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) sb.append(',');
                try { sb.append(esc(cell(fields.get(i).get(r)))); } catch (IllegalAccessException e) { sb.append(""); }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String cell(Object v) {
        if (v == null) return "";
        if (v instanceof int[] a) { StringJoiner j = new StringJoiner("|"); for (int x : a) j.add(Integer.toString(x)); return j.toString(); }
        if (v instanceof Collection<?> c) { StringJoiner j = new StringJoiner("|"); for (Object x : c) j.add(String.valueOf(x)); return j.toString(); }
        if (v instanceof Double d && d.isNaN()) return "";
        return String.valueOf(v);
    }

    private static String esc(String s) {
        boolean formula = !s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+(\\.\\d+)?(E-?\\d+)?");
        if (formula) s = "'" + s; // neutralise spreadsheet formula injection
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) return '"' + s.replace("\"", "\"\"") + '"';
        return s;
    }

    /** Zips every regular file under {@code dataDir} (excluding the backup directory) into {@code zip}. */
    public static int backup(Path dataDir, Path zip) throws IOException {
        Files.createDirectories(zip.toAbsolutePath().getParent());
        int n = 0;
        Path backups = dataDir.resolve("backups");
        try (OutputStream os = Files.newOutputStream(zip); ZipOutputStream zos = new ZipOutputStream(os);
             Stream<Path> files = Files.walk(dataDir)) {
            for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile).filter(f -> !f.startsWith(backups))::iterator) {
                zos.putNextEntry(new ZipEntry(dataDir.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, zos);
                zos.closeEntry();
                n++;
            }
        }
        return n;
    }

    /**
     * Restores a backup zip into {@code dataDir}. The current contents are first backed up next to it; entries that
     * would escape the directory (zip-slip) are rejected.
     */
    public static int restore(Path zip, Path dataDir) throws IOException {
        Path safety = dataDir.resolve("backups").resolve("pre-restore-" + System.currentTimeMillis() + ".zip");
        if (Files.exists(dataDir)) backup(dataDir, safety);
        Path root = dataDir.toAbsolutePath().normalize();
        int n = 0;
        try (InputStream is = Files.newInputStream(zip); ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                Path target = root.resolve(e.getName()).normalize();
                if (!target.startsWith(root) || e.isDirectory()) { if (!e.isDirectory()) Log.warn("Skipped unsafe backup entry " + e.getName()); continue; }
                Files.createDirectories(target.getParent());
                Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                n++;
            }
        }
        return n;
    }
}
