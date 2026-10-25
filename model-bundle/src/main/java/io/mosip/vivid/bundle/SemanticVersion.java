package io.mosip.vivid.bundle;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal semantic version (major.minor.patch with optional {@code -prerelease} and {@code +build}).
 * Comparison follows semver 2.0: numeric fields first, a pre-release sorts before the plain release,
 * build metadata is ignored.
 */
public final class SemanticVersion implements Comparable<SemanticVersion> {
    private static final Pattern PATTERN = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?$");

    private final int major;
    private final int minor;
    private final int patch;
    private final String preRelease; // nullable

    private SemanticVersion(int major, int minor, int patch, String preRelease) {
        this.major = major; this.minor = minor; this.patch = patch; this.preRelease = preRelease;
    }

    public static boolean isValid(String s) {
        return s != null && PATTERN.matcher(s.trim()).matches();
    }

    /** Parses {@code 1.2.3}, {@code 1.2.3-rc.1}, {@code 1.2.3+build}; a leading {@code v} and {@code -SNAPSHOT} are tolerated. */
    public static SemanticVersion parse(String s) {
        Objects.requireNonNull(s, "version");
        String t = s.trim();
        if (t.startsWith("v") || t.startsWith("V")) t = t.substring(1);
        Matcher m = PATTERN.matcher(t);
        if (!m.matches()) throw new IllegalArgumentException("not a semantic version: " + s);
        return new SemanticVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), m.group(4));
    }

    public int major() { return major; }
    public int minor() { return minor; }
    public int patch() { return patch; }
    public String preRelease() { return preRelease; }

    @Override public int compareTo(SemanticVersion o) {
        if (major != o.major) return Integer.compare(major, o.major);
        if (minor != o.minor) return Integer.compare(minor, o.minor);
        if (patch != o.patch) return Integer.compare(patch, o.patch);
        if (preRelease == null && o.preRelease == null) return 0;
        if (preRelease == null) return 1;   // release > pre-release
        if (o.preRelease == null) return -1;
        return comparePreRelease(preRelease, o.preRelease);
    }

    private static int comparePreRelease(String a, String b) {
        String[] as = a.split("\\."), bs = b.split("\\.");
        for (int i = 0; i < Math.min(as.length, bs.length); i++) {
            boolean an = as[i].chars().allMatch(Character::isDigit), bn = bs[i].chars().allMatch(Character::isDigit);
            int c;
            if (an && bn) c = Long.compare(Long.parseLong(as[i]), Long.parseLong(bs[i]));
            else if (an) c = -1;
            else if (bn) c = 1;
            else c = as[i].compareTo(bs[i]);
            if (c != 0) return c;
        }
        return Integer.compare(as.length, bs.length);
    }

    public boolean isAtLeast(SemanticVersion other) { return compareTo(other) >= 0; }

    @Override public boolean equals(Object o) {
        return o instanceof SemanticVersion && compareTo((SemanticVersion) o) == 0;
    }

    @Override public int hashCode() { return Objects.hash(major, minor, patch, preRelease); }

    @Override public String toString() {
        return major + "." + minor + "." + patch + (preRelease == null ? "" : "-" + preRelease);
    }
}
