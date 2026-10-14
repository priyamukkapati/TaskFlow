import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Single-process task store. Writes complete snapshots before publishing changes. */
public final class TaskStore {
    public enum Status { TODO, IN_PROGRESS, DONE }
    public record Task(String id, String title, String assignee, Status status, int version,
                       String updatedAt, List<String> comments) {
        public Task { comments = List.copyOf(comments); }
    }
    public static final class Conflict extends IllegalStateException {
        Conflict(String message) { super(message); }
    }
    private final Path file;
    private Map<String, Task> tasks = new LinkedHashMap<>();
    public TaskStore(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                try {
                    if (f.length < 7) throw new IllegalArgumentException();
                    List<String> comments = new ArrayList<>();
                    for (int i = 7; i < f.length; i++) comments.add(decode(f[i]));
                    Task t = new Task(f[0], decode(f[1]), decode(f[2]), Status.valueOf(f[3]),
                                      Integer.parseInt(f[4]), f[5], comments);
                    if (!f[6].equals("v1") || tasks.putIfAbsent(t.id(), t) != null) throw new IllegalArgumentException();
                } catch (RuntimeException e) { throw new IOException("Invalid task snapshot", e); }
            }
        }
    }
    public synchronized List<Task> list() { return List.copyOf(tasks.values()); }
    public synchronized Task create(String title, String assignee) throws IOException {
        validate(title, 200); validate(assignee, 100);
        Task t = new Task(UUID.randomUUID().toString(), title.strip(), assignee.strip(),
                          Status.TODO, 1, Instant.now().toString(), List.of());
        commit(t); return t;
    }
    public synchronized Task move(String id, int expectedVersion, Status status) throws IOException {
        Task t = get(id, expectedVersion);
        if (status == t.status()) return t;
        if (Math.abs(status.ordinal() - t.status().ordinal()) != 1)
            throw new IllegalArgumentException("Move between adjacent board columns");
        Task next = new Task(id, t.title(), t.assignee(), status, Math.addExact(t.version(), 1),
                             Instant.now().toString(), t.comments());
        commit(next); return next;
    }
    public synchronized Task comment(String id, int expectedVersion, String text) throws IOException {
        validate(text, 500); Task t = get(id, expectedVersion);
        if (t.comments().size() >= 100) throw new IllegalArgumentException("Comment limit reached");
        List<String> comments = new ArrayList<>(t.comments()); comments.add(text.strip());
        Task next = new Task(id, t.title(), t.assignee(), t.status(), Math.addExact(t.version(), 1),
                             Instant.now().toString(), comments);
        commit(next); return next;
    }
    private Task get(String id, int version) {
        Task t = tasks.get(id);
        if (t == null) throw new NoSuchElementException("Task not found");
        if (t.version() != version) throw new Conflict("Stale version; reload the task");
        return t;
    }
    private void commit(Task task) throws IOException {
        Map<String, Task> next = new LinkedHashMap<>(tasks); next.put(task.id(), task);
        Path absolute = file.toAbsolutePath(); Files.createDirectories(absolute.getParent());
        Path temp = Files.createTempFile(absolute.getParent(), "taskflow-", ".tmp");
        try {
            StringBuilder out = new StringBuilder();
            for (Task t : next.values()) {
                out.append(t.id()).append('\t').append(encode(t.title())).append('\t')
                   .append(encode(t.assignee())).append('\t').append(t.status()).append('\t')
                   .append(t.version()).append('\t').append(t.updatedAt()).append("\tv1");
                for (String c : t.comments()) out.append('\t').append(encode(c));
                out.append('\n');
            }
            Files.writeString(temp, out, StandardCharsets.UTF_8);
            try { Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING); }
            tasks = next;
        } finally { Files.deleteIfExists(temp); }
    }
    private static void validate(String s, int max) {
        if (s == null || s.isBlank() || s.length() > max) throw new IllegalArgumentException("Invalid text length");
    }
    private static String encode(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String s) { return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8); }
}
