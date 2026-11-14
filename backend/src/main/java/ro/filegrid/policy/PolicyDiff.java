package ro.filegrid.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

/**
 * Structural difference between two policy versions: one entry per changed
 * value, addressed by its path (for example
 * {@code categories.DRONE_VIDEO.constraints.maxSizeBytes.FTP}). Comments and
 * formatting of the YAML do not count as changes.
 */
public final class PolicyDiff {

    private PolicyDiff() {
    }

    /**
     * @param path   dotted path of the value
     * @param before previous value, {@code null} when the value was added
     * @param after  new value, {@code null} when the value was removed
     */
    public record Change(String path, JsonNode before, JsonNode after) {
    }

    public static List<Change> between(JsonNode before, JsonNode after) {
        List<Change> changes = new ArrayList<>();
        compare("", before == null ? MissingNode.getInstance() : before, after, changes);
        return changes;
    }

    private static void compare(String path, JsonNode before, JsonNode after, List<Change> changes) {
        if (before.isObject() && after.isObject()) {
            TreeSet<String> names = new TreeSet<>();
            for (Iterator<String> it = before.fieldNames(); it.hasNext(); ) {
                names.add(it.next());
            }
            for (Iterator<String> it = after.fieldNames(); it.hasNext(); ) {
                names.add(it.next());
            }
            for (String name : names) {
                compare(path.isEmpty() ? name : path + "." + name, before.path(name), after.path(name), changes);
            }
        } else if (!before.equals(after)) {
            // Lists (extensions, fallbacks) are compared as a whole: their order matters.
            changes.add(new Change(path, before.isMissingNode() ? null : before,
                    after.isMissingNode() ? null : after));
        }
    }
}
