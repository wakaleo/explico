/**
 * Real-world corpus benchmark (NOT part of the Gradle build): renders every non-test .rego file
 * under a directory through the public Explico API, ONE FILE AT A TIME in its own temp directory
 * so a crash or an opa rejection in one file never hides the rest, and records per-file status and
 * rendering coverage. See README.md in this directory for how to run it and how to read the output.
 *
 *   java -cp "build/install/explico/lib/*" scripts/corpus-benchmark/Bench.java <corpus-dir> <out-dir> [file|dir|all] [v1|v0]
 *
 * Granularity (third argument, default "file"): "file" loads each file alone; "dir" loads each
 * directory's files together (same-package helpers in sibling files resolve); "all" loads the whole
 * corpus at once, relative layout kept (cross-package calls and imports resolve too -- but one
 * opa rejection fails everything, so use it on corpora that are entirely Rego v1).
 */
import io.explico.Explico;
import io.explico.RenderedDocs;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/** Throwaway corpus benchmark: one temp dir per .rego file, in-process, never aborting on a crash. */
public class Bench {
    public static void main(String[] a) throws Exception {
        if (a.length < 2 || a.length > 4) { System.err.println("usage: Bench.java <corpus-dir> <out-dir> [file|dir|all] [v1|v0]"); System.exit(2); }
        io.explico.model.RegoVersion version = a.length == 4 && a[3].equals("v0") ? io.explico.model.RegoVersion.V0 : io.explico.model.RegoVersion.V1;
        if (a.length == 4 && !List.of("v1", "v0").contains(a[3])) { System.err.println("version must be v1 or v0"); System.exit(2); }
        String mode = a.length >= 3 ? a[2] : "file";
        if (!List.of("file", "dir", "all").contains(mode)) { System.err.println("mode must be file, dir or all"); System.exit(2); }
        Path root = Path.of(a[0]);
        Path out = Path.of(a[1]);
        List<Path> files;
        try (Stream<Path> s = Files.walk(root)) {
            files = s.filter(p -> p.toString().endsWith(".rego") && !p.getFileName().toString().endsWith("_test.rego")
                    && !p.toString().contains("/.git/")).sorted().collect(Collectors.toList());
        }
        // A unit is the set of files loaded together: one file, one directory's files, or everything.
        Map<String, List<Path>> units = new TreeMap<>();
        for (Path f : files) {
            String key = mode.equals("file") ? f.toString() : mode.equals("dir") ? f.getParent().toString() : root.toString();
            units.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
        }
        Pattern fence = Pattern.compile("not rendered[^\\n]*\\n\\n\\s*```rego\\n(.*?)\\n\\s*```", Pattern.DOTALL);
        try (PrintWriter tsv = new PrintWriter(out.resolve("results.tsv").toFile());
             PrintWriter fb = new PrintWriter(out.resolve("fallbacks.txt").toFile());
             PrintWriter wr = new PrintWriter(out.resolve("warnings.txt").toFile())) {
            tsv.println("file\tstatus\trendered\ttotal\tclass\tmessage");
            for (Map.Entry<String, List<Path>> unit : units.entrySet()) {
                Path f = Path.of(unit.getKey());
                Path tmp = Files.createTempDirectory("bench-");
                PrintStream origErr = System.err; ByteArrayOutputStream eb = new ByteArrayOutputStream(); System.setErr(new PrintStream(eb, true));
                try {
                    for (Path member : unit.getValue()) {
                        Path dest = mode.equals("all") ? tmp.resolve(root.relativize(member).toString())
                                : mode.equals("dir") ? tmp.resolve(member.getFileName().toString()) : tmp.resolve("p.rego");
                        Files.createDirectories(dest.getParent());
                        Files.copy(member, dest);
                    }
                    String status; int r = 0, t = 0; String cls = "", msg = "";
                    try {
                        var ps = Explico.load(tmp, version);
                        try {
                            RenderedDocs d = Explico.render(ps, tmp);
                            status = "OK"; r = d.getCoverage().getRendered(); t = d.getCoverage().getTotal();
                            for (String md : d.getFiles().values()) {
                                Matcher m = fence.matcher(md);
                                while (m.find()) fb.println(f + "\t" + m.group(1).strip().lines().findFirst().orElse(""));
                            }
                        } catch (Throwable e) { status = "RENDER_CRASH"; cls = e.getClass().getSimpleName(); msg = where(e); }
                    } catch (Throwable e) { status = "LOAD_FAIL"; cls = e.getClass().getSimpleName(); msg = where(e); }
                    tsv.println(f + "\t" + status + "\t" + r + "\t" + t + "\t" + cls + "\t" + msg.replace('\n', ' '));
                } finally {
                    System.setErr(origErr);
                    for (String l : eb.toString().split("\n")) if (l.contains("could not map rule")) wr.println(f + "\t" + l.replaceAll("^.*\\): ", ""));
                    try (Stream<Path> s = Files.walk(tmp)) { s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
                }
            }
        }
    }
    static String where(Throwable e) {
        String top = Arrays.stream(e.getStackTrace()).filter(x -> x.getClassName().startsWith("io.explico")).findFirst().map(StackTraceElement::toString).orElse("?");
        String m = String.valueOf(e.getMessage()); if (m.length() > 160) m = m.substring(0, 160);
        return m.lines().findFirst().orElse("") + " @ " + top;
    }
}
