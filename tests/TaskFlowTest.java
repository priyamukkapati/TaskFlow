import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class TaskFlowTest {
    static int checks;
    static void check(boolean value, String name) { if (!value) throw new AssertionError(name); checks++; }
    static void rejects(Runnable fn, Class<? extends Throwable> type) {
        try { fn.run(); } catch (Throwable e) { check(type.isInstance(e), "exception type"); return; }
        throw new AssertionError("Expected rejection");
    }
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("taskflow-test"); Path file = directory.resolve("tasks.tsv");
        TaskStore store = new TaskStore(file);
        TaskStore.Task t = store.create("Build Java API", "Priya");
        check(t.status() == TaskStore.Status.TODO, "initial status");
        store.move(t.id(), 1, TaskStore.Status.IN_PROGRESS);
        rejects(() -> { try { store.move(t.id(), 1, TaskStore.Status.DONE); } catch(java.io.IOException e) {throw new RuntimeException(e);} }, TaskStore.Conflict.class);
        store.comment(t.id(), 2, "Review\twith\nteam");
        check(new TaskStore(file).list().get(0).comments().get(0).equals("Review\twith\nteam"), "restart round trip");
        TaskStore.Task race = store.create("Race", "team"); AtomicInteger wins = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(8);
        var futures = new java.util.ArrayList<Future<?>>();
        for(int i=0;i<20;i++) futures.add(workers.submit(() -> { try { store.comment(race.id(), 1, "winner"); wins.incrementAndGet(); } catch(TaskStore.Conflict expected) {} catch(Exception e) {throw new RuntimeException(e);} }));
        for(var f:futures) f.get(); workers.shutdown();
        check(wins.get()==1, "one winner for stale version race");
        check(new TaskStore(file).list().get(1).version()==2, "race persisted");
        rejects(() -> { try {store.create(" ","user");} catch(java.io.IOException e){throw new RuntimeException(e);} }, IllegalArgumentException.class);
        TaskStore bad = new TaskStore(directory.resolve("blocked")); Files.createDirectory(directory.resolve("blocked"));
        try { bad.create("Lost write", "user"); throw new AssertionError("Expected IO failure"); } catch(java.io.IOException expected) {check(bad.list().isEmpty(), "write failure rolls back memory");}
        var server = TaskFlow.start(store, 0, Path.of("web/index.html"));
        ExecutorService executor = (ExecutorService)server.getExecutor();
        try {
            String base = "http://127.0.0.1:"+server.getAddress().getPort(); HttpClient client=HttpClient.newHttpClient();
            check(client.send(HttpRequest.newBuilder(URI.create(base+"/health")).build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200,"health");
            var invalid=HttpRequest.newBuilder(URI.create(base+"/api/tasks")).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("title=" )).build();
            check(client.send(invalid,HttpResponse.BodyHandlers.ofString()).statusCode()==400,"HTTP validation");
            var stale=HttpRequest.newBuilder(URI.create(base+"/api/tasks/"+race.id()+"/comments")).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("version=1&text=stale")).build();
            check(client.send(stale,HttpResponse.BodyHandlers.ofString()).statusCode()==409,"HTTP conflict");
            check(client.send(HttpRequest.newBuilder(URI.create(base+"/")).build(),HttpResponse.BodyHandlers.ofString()).body().contains("TaskFlow"),"browser page");
        } finally {server.stop(0);executor.shutdownNow();}
        System.out.println("TaskFlow: "+checks+" checks passed");
    }
}
