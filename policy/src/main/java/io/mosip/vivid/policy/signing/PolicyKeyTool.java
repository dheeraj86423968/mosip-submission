package io.mosip.vivid.policy.signing;

import io.mosip.vivid.policy.PolicyException;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

/**
 * Small command-line tool for operations and tests: generates signing key pairs, signs policy files and
 * verifies signatures. Keys are stored as Base64 text files (PKCS#8 private, X.509 public).
 *
 * <pre>
 *   PolicyKeyTool gen    [ec|rsa] &lt;private.key&gt; &lt;public.key&gt;
 *   PolicyKeyTool sign   &lt;private.key&gt; &lt;policy.json&gt; &lt;signature.out&gt;
 *   PolicyKeyTool verify &lt;public.key&gt;  &lt;policy.json&gt; &lt;signature.file&gt;
 * </pre>
 *
 * Output is written to the stream passed to {@link #run}; {@link #main} uses standard output. Exit code is
 * 0 on success, 1 on a usage error and 2 when verification fails.
 */
public final class PolicyKeyTool {

    private PolicyKeyTool() {}

    /** Entry point; delegates to {@link #run} and exits with its status. */
    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    /** Runs the tool against the given arguments, writing human-readable output to {@code out}. */
    public static int run(String[] args, PrintStream out) {
        if (args == null || args.length == 0) {
            usage(out);
            return 1;
        }
        try {
            switch (args[0]) {
                case "gen":
                    return gen(Arrays.copyOfRange(args, 1, args.length), out);
                case "sign":
                    return sign(Arrays.copyOfRange(args, 1, args.length), out);
                case "verify":
                    return verify(Arrays.copyOfRange(args, 1, args.length), out);
                default:
                    usage(out);
                    return 1;
            }
        } catch (IOException | PolicyException e) {
            out.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static int gen(String[] a, PrintStream out) throws IOException {
        String type = "ec";
        int idx = 0;
        if (a.length == 3) {
            type = a[0].toLowerCase(java.util.Locale.ROOT);
            idx = 1;
        } else if (a.length != 2) {
            usage(out);
            return 1;
        }
        KeyPair kp;
        if (type.equals("ec")) {
            kp = PolicyKeys.generateEc();
        } else if (type.equals("rsa")) {
            kp = PolicyKeys.generateRsa(2048);
        } else {
            usage(out);
            return 1;
        }
        Files.write(Paths.get(a[idx]), PolicyKeys.encodePrivate(kp.getPrivate()).getBytes(StandardCharsets.UTF_8));
        Files.write(Paths.get(a[idx + 1]), PolicyKeys.encodePublic(kp.getPublic()).getBytes(StandardCharsets.UTF_8));
        out.println("generated " + type.toUpperCase(java.util.Locale.ROOT) + " key pair: " + a[idx] + " (private), " + a[idx + 1] + " (public)");
        return 0;
    }

    private static int sign(String[] a, PrintStream out) throws IOException {
        if (a.length != 3) {
            usage(out);
            return 1;
        }
        PrivateKey key = PolicyKeys.decodePrivateAny(new String(Files.readAllBytes(Paths.get(a[0])), StandardCharsets.UTF_8));
        String json = new String(Files.readAllBytes(Paths.get(a[1])), StandardCharsets.UTF_8);
        String signature = PolicySigner.sign(json, key);
        Files.write(Paths.get(a[2]), signature.getBytes(StandardCharsets.UTF_8));
        out.println("signed " + a[1] + " with " + PolicySigner.algorithmFor(key) + " -> " + a[2]);
        return 0;
    }

    private static int verify(String[] a, PrintStream out) throws IOException {
        if (a.length != 3) {
            usage(out);
            return 1;
        }
        PublicKey key = PolicyKeys.decodePublicAny(new String(Files.readAllBytes(Paths.get(a[0])), StandardCharsets.UTF_8));
        String json = new String(Files.readAllBytes(Paths.get(a[1])), StandardCharsets.UTF_8);
        String signature = new String(Files.readAllBytes(Paths.get(a[2])), StandardCharsets.UTF_8);
        boolean ok = PolicyVerifier.verify(json, signature, key);
        out.println(ok ? "signature VALID" : "signature INVALID");
        return ok ? 0 : 2;
    }

    private static void usage(PrintStream out) {
        out.println("usage:");
        out.println("  PolicyKeyTool gen    [ec|rsa] <private.key> <public.key>");
        out.println("  PolicyKeyTool sign   <private.key> <policy.json> <signature.out>");
        out.println("  PolicyKeyTool verify <public.key>  <policy.json> <signature.file>");
    }
}
