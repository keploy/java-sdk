package io.keploy.dedup;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The build a test's coverage record names (its last field) is the build of
 * the INDEX report sent before it: the test receivers check that here, take
 * the field off, and hand the rows the rest to compare as they always did.
 * A receiver checks each record as it takes it, so a row fails on any record
 * it took, read or not, once it reads the next or closes the receiver.
 */
final class RecordBuilds {

    private static final Pattern RECORD_BUILD = Pattern.compile(",\"build\":\"([^\"]*)\"\\}$");
    private static final Pattern INDEX_BUILD = Pattern.compile("\"build\":\"([^\"]*)\"");

    private RecordBuilds() {
    }

    // indexBuild is the build an INDEX report names, or "" for none.
    static String indexBuild(String index) {
        Matcher m = INDEX_BUILD.matcher(index);
        return m.find() ? m.group(1) : "";
    }

    // mismatch is what is wrong with a test record's build, null when it is
    // indexBuild's (or the payload is no test record): a receiver keeps the
    // first and fails the row with it (failIfMismatched), whatever the row
    // compares of the record.
    static String mismatch(String payload, String indexBuild) {
        String c = checked(payload, indexBuild);
        int note = c == null ? -1 : c.lastIndexOf(" <names ");
        return note < 0 || !c.endsWith(">") ? null : "a test record " + c.substring(note + 1) + ": " + payload;
    }

    // failIfMismatched fails the row with a receiver's first mismatch.
    static void failIfMismatched(String mismatch) {
        if (mismatch != null) {
            throw new AssertionError(mismatch);
        }
    }

    // checked is a test record with its build taken off when it is
    // indexBuild (the last INDEX this receiver took; "" for none). A record
    // that names another build, or none after an INDEX that names one, is
    // kept whole with a note, so the row's comparison fails and shows it.
    static String checked(String payload, String indexBuild) {
        if (payload == null || !payload.startsWith("{\"id\":")) {
            return payload;
        }
        Matcher m = RECORD_BUILD.matcher(payload);
        if (m.find()) {
            String build = m.group(1);
            if (!build.equals(indexBuild)) {
                return payload + " <names build " + build + ", the INDEX's is "
                        + (indexBuild.isEmpty() ? "none" : indexBuild) + ">";
            }
            return payload.substring(0, m.start()) + "}";
        }
        if (!indexBuild.isEmpty()) {
            return payload + " <names no build, the INDEX's is " + indexBuild + ">";
        }
        return payload;
    }
}
