import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SESEmitter {

    // ----------------------------
    // Usage:
    //   javac -cp jackson-databind-2.17.2.jar SESEmitter.java
    //   java  -cp .;jackson-databind-2.17.2.jar SESEmitter input.json output.ses
    //
    // (Adjust classpath separator for your OS: ';' on Windows, ':' on Linux/macOS)
    // ----------------------------

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java SESEmitter <input.json> <output.ses> [--overwrite]");
            System.exit(2);
        }

        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        boolean overwrite = Arrays.asList(args).contains("--overwrite");

        String raw = Files.readString(in, StandardCharsets.UTF_8);

        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(raw);

        CanonicalGraph graph = extractCanonicalGraph(root);

        String ses = emitSes(graph);

        writeFile(out, ses, overwrite);
        System.out.println("Wrote SES: " + out.toAbsolutePath());
    }

    // ============================================================
    // 1) Canonical internal representation (schema-independent)
    // ============================================================

    static final class NodeDef {
        final String id;
        final String name;
        final String parentId; // null means root-level

        NodeDef(String id, String name, String parentId) {
            this.id = id;
            this.name = name;
            this.parentId = parentId;
        }
    }

    static final class EdgeDef {
        final String fromId;
        final String toId;
        final String label; // optional

        EdgeDef(String fromId, String toId, String label) {
            this.fromId = fromId;
            this.toId = toId;
            this.label = label;
        }
    }

    static final class CanonicalGraph {
        final List<NodeDef> nodes;
        final List<EdgeDef> edges;

        CanonicalGraph(List<NodeDef> nodes, List<EdgeDef> edges) {
            this.nodes = nodes;
            this.edges = edges;
        }
    }

    // ============================================================
    // 2) Heuristic extraction from unknown JSON schema
    //    - looks for arrays: nodes/models/elements and edges/links/connections
    //    - tries common field names for id/name/parent
    // ============================================================

    static CanonicalGraph extractCanonicalGraph(JsonNode root) {
        List<NodeDef> nodes = new ArrayList<>();
        List<EdgeDef> edges = new ArrayList<>();

        JsonNode nodesArr = firstArray(root, "nodes", "models", "elements");
        if (nodesArr != null) {
            for (JsonNode n : nodesArr) {
                String id = firstText(n, "id", "_id", "uuid", "key", "identifier");
                String name = firstText(n, "name", "label", "title");
                String parentId = firstText(n, "parentId", "parent", "containerId", "ownerId", "belongsTo");

                // Sometimes "parent" is an object: { parent: { id: "..." } }
                if (parentId == null) parentId = nestedText(n, "parent", "id", "_id", "uuid");

                if (id == null) id = UUID.randomUUID().toString();
                if (name == null) name = "node_" + shortId(id);

                // Sanitize name to be a nice token in SES sentences
                name = sanitizeSesToken(name);

                nodes.add(new NodeDef(id, name, parentId));
            }
        }

        JsonNode edgesArr = firstArray(root, "edges", "links", "connections");
        if (edgesArr != null) {
            for (JsonNode e : edgesArr) {
                String from = firstText(e, "from", "source", "src", "fromId", "sourceId");
                String to = firstText(e, "to", "target", "dst", "toId", "targetId");
                String label = firstText(e, "label", "type", "kind", "name");

                // Nested: {source:{id:"..."}, target:{id:"..."}}
                if (from == null) from = nestedText(e, "source", "id", "_id", "uuid", "key");
                if (to == null) to = nestedText(e, "target", "id", "_id", "uuid", "key");

                if (from != null && to != null) {
                    edges.add(new EdgeDef(from, to, label));
                }
            }
        }

        return new CanonicalGraph(nodes, edges);
    }

    static JsonNode firstArray(JsonNode root, String... keys) {
        for (String k : keys) {
            JsonNode n = root.get(k);
            if (n != null && n.isArray()) return n;
        }
        // If root itself is an array, accept it as "nodes" (rare but happens)
        if (root != null && root.isArray()) return root;
        return null;
    }

    static String firstText(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && v.isValueNode()) return v.asText();
        }
        return null;
    }

    static String nestedText(JsonNode node, String containerKey, String... keys) {
        JsonNode c = node.get(containerKey);
        if (c == null) return null;
        return firstText(c, keys);
    }

    static String shortId(String id) {
        return (id == null) ? "xxxxxx" : (id.length() <= 6 ? id : id.substring(0, 6));
    }

    // Keep tokens clean for your SES line style (spaces -> underscores, etc.)
    static String sanitizeSesToken(String s) {
        if (s == null || s.isBlank()) return "Generated";
        String t = s.trim();
        // Replace whitespace with underscore
        t = t.replaceAll("\\s+", "_");
        // Remove weird punctuation but keep underscores/dashes
        t = t.replaceAll("[^A-Za-z0-9_\\-]", "_");
        // Avoid starting with a digit
        if (Character.isDigit(t.charAt(0))) t = "_" + t;
        return t;
    }

    // ============================================================
    // Rule used :
    // - For each parent P that has direct children:
    //     composition line enumerating direct children
    // - For each connection u -> v:
    //     emit it under the perspective of the *lowest scope* where u and v
    //     are siblings (i.e., their direct parent is the same).
    // ============================================================

    static String emitSes(CanonicalGraph g) {
        Map<String, NodeDef> byId = g.nodes.stream().collect(Collectors.toMap(n -> n.id, n -> n, (a, b) -> a));

        // Build children lists
        Map<String, List<NodeDef>> childrenByParent = new HashMap<>();
        for (NodeDef n : g.nodes) {
            childrenByParent.computeIfAbsent(n.parentId, k -> new ArrayList<>()).add(n);
        }

        // Sort children for stable output
        for (List<NodeDef> kids : childrenByParent.values()) {
            kids.sort(Comparator.comparing(a -> a.name));
        }

        // Pre-group edges by "scope parentId" where endpoints are siblings under same parent
        Map<String, List<EdgeDef>> edgesByScopeParent = new HashMap<>();
        for (EdgeDef e : g.edges) {
            NodeDef src = byId.get(e.fromId);
            NodeDef dst = byId.get(e.toId);
            if (src == null || dst == null) continue;

            // sibling scope requires same parentId (could be null root)
            if (Objects.equals(src.parentId, dst.parentId)) {
                edgesByScopeParent.computeIfAbsent(src.parentId, k -> new ArrayList<>()).add(e);
            }
        }

        // For each scope, generate flow counters
        Map<String, Integer> flowCounter = new HashMap<>();

        StringBuilder sb = new StringBuilder();

        // Emit scopes in a stable order: root first, then by parent name
        List<String> scopeIds = new ArrayList<>(childrenByParent.keySet());
        scopeIds.sort((a, b) -> {
            if (a == null && b == null) return 0;
            if (a == null) return -1;
            if (b == null) return 1;
            NodeDef pa = byId.get(a);
            NodeDef pb = byId.get(b);
            String na = pa == null ? a : pa.name;
            String nb = pb == null ? b : pb.name;
            return na.compareTo(nb);
        });

        for (String parentId : scopeIds) {
            List<NodeDef> kids = childrenByParent.getOrDefault(parentId, List.of());
            if (kids.isEmpty()) continue;

            // parent (system) naming
            String parentName = (parentId == null) ? "ROOT" : (byId.containsKey(parentId) ? byId.get(parentId).name : parentId);
            String perspective = parentName + "Sys";

            // 1) composition
            String parts = joinWithAnd(kids.stream().map(k -> k.name).collect(Collectors.toList()));
            sb.append("From the ").append(perspective).append(" perspective, ")
              .append(parentName).append(" is made of ").append(parts).append("!\n\n");

            // 2) flows among siblings in this scope
            List<EdgeDef> scopedEdges = edgesByScopeParent.getOrDefault(parentId, List.of());
            // stable order
            List<EdgeDef> sortedEdges = new ArrayList<>(scopedEdges);
            sortedEdges.sort(Comparator
                    .comparing((EdgeDef e) -> safeName(byId.get(e.fromId)))
                    .thenComparing(e -> safeName(byId.get(e.toId)))
            );

            for (EdgeDef e : sortedEdges) {
                NodeDef src = byId.get(e.fromId);
                NodeDef dst = byId.get(e.toId);
                if (src == null || dst == null) continue;

                int k = flowCounter.merge(scopeKey(parentId), 1, Integer::sum);

                sb.append("From the ").append(perspective).append(" perspective, ")
                  .append(src.name).append(" sends outPort").append(k)
                  .append(" to ").append(dst.name)
                  .append(" as inPort").append(k).append("!\n");
            }

            sb.append("\n");
        }

        return sb.toString();
    }

    static String safeName(NodeDef n) {
        return n == null ? "" : n.name;
    }

    static String scopeKey(String parentId) {
        return parentId == null ? "__ROOT__" : parentId;
    }

    static String joinWithAnd(List<String> items) {
        if (items.isEmpty()) return "";
        if (items.size() == 1) return items.get(0);
        if (items.size() == 2) return items.get(0) + " and " + items.get(1);
        return String.join(", ", items.subList(0, items.size() - 1)) + ", and " + items.get(items.size() - 1);
    }

    // ============================================================
    // 3) File write helper
    // ============================================================

    static void writeFile(Path path, String content, boolean overwrite) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);

        // Always overwrite if file exists (ignore the overwrite flag for now, make it default behavior)
        OpenOption[] options = new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};

        Files.writeString(path, content, StandardCharsets.UTF_8, options);
    }
}
