package io.mosip.vivid.bundle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command-line tool for bundle operators.
 *
 * <pre>
 *   keygen  --out-dir DIR [--rsa]                       write bundle-signing.key (PKCS#8 PEM) and bundle-signing.pub (X.509 PEM)
 *   create  --dir DIR --manifest FILE --key KEY --out FILE.mlb
 *                                                        hash every listed file, sign and zip
 *   verify  --bundle FILE.mlb --pubkey PUB [--engine-version V --platform P]
 *   list    --store DIR                                  installed bundles and the active one
 *   install --store DIR --bundle FILE.mlb --pubkey PUB [--activate] [--keep N]
 *   import  --store DIR --from PATH --pubkey PUB [--recursive] [--activate] [--keep N]
 *   activate --store DIR --pubkey PUB --id ID --version V
 *   rollback --store DIR --pubkey PUB
 * </pre>
 * Output goes to the supplied {@link PrintStream} (stdout in {@link #main}); exit code 0 on success, 1 on failure,
 * 2 on usage error. The class is also usable programmatically through {@link #run}.
 */
public final class BundleTool {
    private static final Logger LOG = LoggerFactory.getLogger(BundleTool.class);

    private final PrintStream out;

    public BundleTool(PrintStream out) { this.out = out; }

    public static void main(String[] args) {
        System.exit(new BundleTool(System.out).run(args));
    }

    /** Runs a command; returns the process exit code instead of exiting. */
    public int run(String... args) {
        if (args.length == 0) {
            usage();
            return 2;
        }
        Map<String, String> opts = parseOptions(Arrays.copyOfRange(args, 1, args.length));
        try {
            switch (args[0]) {
                case "keygen": return keygen(opts);
                case "create": return create(opts);
                case "verify": return verify(opts);
                case "list": return list(opts);
                case "install": return install(opts);
                case "import": return importCmd(opts);
                case "activate": return activate(opts);
                case "rollback": return rollback(opts);
                case "help": case "--help": case "-h": usage(); return 0;
                default: out.println("unknown command " + args[0]); usage(); return 2;
            }
        } catch (BundleRejectedException e) {
            out.println("REJECTED " + e.getMessage());
            return 1;
        } catch (IllegalArgumentException e) {
            out.println("error: " + e.getMessage());
            usage();
            return 2;
        } catch (Exception e) {
            LOG.error("command failed", e);
            out.println("error: " + e);
            return 1;
        }
    }

    private int keygen(Map<String, String> o) throws Exception {
        Path dir = Path.of(require(o, "out-dir"));
        Files.createDirectories(dir);
        KeyPair kp = o.containsKey("rsa") ? KeyIo.generateRsaKeyPair(3072) : KeyIo.generateEcKeyPair();
        Path priv = dir.resolve("bundle-signing.key"), pub = dir.resolve("bundle-signing.pub");
        Files.writeString(priv, KeyIo.toPem(kp.getPrivate()), StandardCharsets.US_ASCII);
        Files.writeString(pub, KeyIo.toPem(kp.getPublic()), StandardCharsets.US_ASCII);
        out.println("wrote " + priv + " and " + pub + " (" + kp.getPublic().getAlgorithm() + ")");
        return 0;
    }

    private int create(Map<String, String> o) throws Exception {
        Path dir = Path.of(require(o, "dir"));
        Path manifestFile = Path.of(require(o, "manifest"));
        PrivateKey key = KeyIo.readPrivateKey(Path.of(require(o, "key")));
        Path target = Path.of(require(o, "out"));
        BundleManifest manifest = BundleManifest.parse(Files.readAllBytes(manifestFile));
        new BundleWriter(key).write(manifest, dir, target);
        out.println("created " + target + " : " + manifest + ", " + Files.size(target) + " bytes");
        for (BundleManifest.ModelEntry m : manifest.getModels()) out.println("  " + m.getName() + " " + m.getFile() + " sha256=" + m.getSha256());
        return 0;
    }

    private int verify(Map<String, String> o) throws Exception {
        Path mlb = Path.of(require(o, "bundle"));
        BundleVerifier verifier = new BundleVerifier(KeyIo.readPublicKey(Path.of(require(o, "pubkey"))));
        VerifiedBundle v = verifier.verify(mlb);
        out.println("OK " + v.manifest() + " minEngineVersion=" + v.manifest().getMinEngineVersion());
        if (o.containsKey("engine-version")) {
            new BundleCompatibility(o.get("engine-version"), o.getOrDefault("platform", BundleManifest.PLATFORM_JVM)).check(v.manifest());
            out.println("COMPATIBLE with engine " + o.get("engine-version"));
        }
        for (Map.Entry<String, Long> e : BundleVerifier.entrySizes(mlb).entrySet()) out.println("  " + e.getKey() + " " + e.getValue() + " bytes");
        return 0;
    }

    private int list(Map<String, String> o) throws Exception {
        Path root = Path.of(require(o, "store"));
        BundleStore store = openStore(o, root, false);
        BundleStore.State state = store.state();
        List<InstalledBundle> all = store.list();
        if (all.isEmpty()) out.println("no bundles installed in " + root);
        for (InstalledBundle b : all) {
            boolean active = b.bundleId().equals(state.activeBundleId) && b.version().equals(state.activeVersion);
            out.println((active ? "* " : "  ") + b.bundleId() + "@" + b.version() + " " + b.manifest().getPlatform()
                    + " minEngine=" + b.manifest().getMinEngineVersion() + " models=" + b.manifest().getModels().size());
        }
        if (!state.history.isEmpty()) out.println("rollback targets: " + state.history);
        return 0;
    }

    private int install(Map<String, String> o) throws Exception {
        BundleStore store = openStore(o, Path.of(require(o, "store")), true);
        InstalledBundle b = store.install(Path.of(require(o, "bundle")));
        out.println("installed " + b);
        if (o.containsKey("activate")) {
            store.activate(b.bundleId(), b.version());
            out.println("activated " + b);
        }
        return 0;
    }

    private int importCmd(Map<String, String> o) throws Exception {
        BundleStore store = openStore(o, Path.of(require(o, "store")), true);
        BundleImporter.Report r = new BundleImporter(store).importFrom(Path.of(require(o, "from")), o.containsKey("recursive"), o.containsKey("activate"));
        for (BundleImporter.Outcome oc : r.outcomes()) out.println(oc);
        out.println(r.installedCount() + " installed, " + r.rejectedCount() + " rejected");
        return r.rejectedCount() == 0 ? 0 : 1;
    }

    private int activate(Map<String, String> o) throws Exception {
        BundleStore store = openStore(o, Path.of(require(o, "store")), true);
        out.println("activated " + store.activate(require(o, "id"), require(o, "version")));
        return 0;
    }

    private int rollback(Map<String, String> o) throws Exception {
        BundleStore store = openStore(o, Path.of(require(o, "store")), true);
        var restored = store.rollback();
        out.println(restored.map(b -> "rolled back to " + b).orElse("nothing to roll back to"));
        return restored.isPresent() ? 0 : 1;
    }

    private static BundleStore openStore(Map<String, String> o, Path root, boolean needKey) throws Exception {
        PublicKey key;
        if (o.containsKey("pubkey")) {
            key = KeyIo.readPublicKey(Path.of(o.get("pubkey")));
        } else if (needKey) {
            throw new IllegalArgumentException("--pubkey is required");
        } else {
            key = KeyIo.generateEcKeyPair().getPublic(); // listing does not verify, any key will do
        }
        BundleCompatibility compat = o.containsKey("engine-version")
                ? new BundleCompatibility(o.get("engine-version"), o.getOrDefault("platform", BundleManifest.PLATFORM_JVM)) : null;
        int keep = Integer.parseInt(o.getOrDefault("keep", "2"));
        return new BundleStore(root, new BundleVerifier(key), compat, keep);
    }

    private static String require(Map<String, String> o, String name) {
        String v = o.get(name);
        if (v == null) throw new IllegalArgumentException("--" + name + " is required");
        return v;
    }

    /** {@code --name value} and {@code --flag} (value "true"). */
    static Map<String, String> parseOptions(String[] args) {
        Map<String, String> m = new HashMap<>();
        List<String> a = new ArrayList<>(Arrays.asList(args));
        for (int i = 0; i < a.size(); i++) {
            String s = a.get(i);
            if (!s.startsWith("--")) throw new IllegalArgumentException("unexpected argument " + s);
            String name = s.substring(2);
            if (i + 1 < a.size() && !a.get(i + 1).startsWith("--")) {
                m.put(name, a.get(++i));
            } else {
                m.put(name, "true");
            }
        }
        return m;
    }

    private void usage() {
        out.println("usage: bundle-tool <command> [options]");
        out.println("  keygen   --out-dir DIR [--rsa]");
        out.println("  create   --dir DIR --manifest FILE --key KEY --out FILE.mlb");
        out.println("  verify   --bundle FILE.mlb --pubkey PUB [--engine-version V] [--platform jvm|android]");
        out.println("  list     --store DIR");
        out.println("  install  --store DIR --bundle FILE.mlb --pubkey PUB [--activate] [--keep N] [--engine-version V]");
        out.println("  import   --store DIR --from PATH --pubkey PUB [--recursive] [--activate] [--keep N]");
        out.println("  activate --store DIR --pubkey PUB --id ID --version V");
        out.println("  rollback --store DIR --pubkey PUB");
    }
}
