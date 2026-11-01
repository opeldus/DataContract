package de.opeldus.datacontract.validation;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.regex.Pattern;

public final class SemanticVersion {

    private static final Pattern FORMAT = Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$");

    public static final Comparator<String> ORDER = SemanticVersion::compare;

    private SemanticVersion() {}

    public static boolean isValid(String version) {
        return version != null && FORMAT.matcher(version).matches();
    }

    /** Both arguments must be valid. */
    public static int compare(String left, String right) {
        var a = left.split("\\.");
        var b = right.split("\\.");
        for (int index = 0; index < 3; index++) {
            int result = new BigInteger(a[index]).compareTo(new BigInteger(b[index]));
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    /** Which part differs first; both arguments must be valid. */
    public static Bump bump(String from, String to) {
        var a = from.split("\\.");
        var b = to.split("\\.");
        if (!a[0].equals(b[0])) {
            return Bump.MAJOR;
        }
        if (!a[1].equals(b[1])) {
            return Bump.MINOR;
        }
        return a[2].equals(b[2]) ? Bump.NONE : Bump.PATCH;
    }

    public enum Bump { NONE, PATCH, MINOR, MAJOR }

    public static boolean sameMajor(String left, String right) {
        return left.split("\\.")[0].equals(right.split("\\.")[0]);
    }
}
