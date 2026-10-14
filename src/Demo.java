import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class Demo {
    public static void main(String[] args) throws Exception {
        Path file=Files.createTempDirectory("taskflow-demo").resolve("tasks.tsv");TaskStore store=new TaskStore(file);
        TaskStore.Task t=store.create("Concurrent update proof","demo");AtomicInteger accepted=new AtomicInteger(),conflicts=new AtomicInteger();
        ExecutorService pool=Executors.newFixedThreadPool(8);var futures=new java.util.ArrayList<Future<?>>();
        long start=System.nanoTime();
        for(int i=0;i<100;i++) futures.add(pool.submit(()->{try{store.comment(t.id(),1,"Update");accepted.incrementAndGet();}catch(TaskStore.Conflict e){conflicts.incrementAndGet();}catch(Exception e){throw new RuntimeException(e);}}));
        for(var f:futures)f.get();pool.shutdown();
        System.out.printf("attempts=100 accepted=%d conflicts=%d elapsed_ms=%.2f persisted_version=%d%n",accepted.get(),conflicts.get(),(System.nanoTime()-start)/1e6,new TaskStore(file).list().get(0).version());
        if(accepted.get()!=1||conflicts.get()!=99)throw new AssertionError("Lost update protection failed");
    }
}
