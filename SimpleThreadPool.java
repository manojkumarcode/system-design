import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class SimpleThreadPool {

    // Queue that stores all submitted tasks
    private final BlockingQueue<Runnable> taskQueue;

    // Stores references to all worker threads
    private final List<Thread> workers = new ArrayList<>();

    // Indicates whether the pool is accepting new tasks
    private final AtomicBoolean running = new AtomicBoolean(true);

    // Constructor
    public SimpleThreadPool(int poolSize, int queueCapacity) {

        // Create the blocking queue
        taskQueue = new LinkedBlockingQueue<>(queueCapacity);

        // Create worker threads
        for (int i = 0; i < poolSize; i++) {

            // Create a thread whose job is to execute workerLoop()
            Thread worker = new Thread(this::workerLoop,
                    "pool-worker-" + i);

            // Store the thread reference
            workers.add(worker);

            // Start the thread
            worker.start();
        }
    }

    // Method used by application to submit work
    public void submit(Runnable task) {

        if (!running.get()) {
            throw new RejectedExecutionException("Thread pool is shutting down");
        }

        try {

            // Put task into queue
            taskQueue.put(task);

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new RuntimeException(e);
        }
    }

    // Every worker thread executes this method
    private void workerLoop() {

        while (running.get() || !taskQueue.isEmpty()) {

            try {

                // Wait until a task becomes available
                Runnable task = taskQueue.take();

                // Execute the task
                task.run();

            } catch (InterruptedException e) {

                // Exit if pool is shutting down
                if (!running.get()) {
                    break;
                }
            }
        }

        System.out.println(Thread.currentThread().getName()
                + " stopped.");
    }

    // Graceful shutdown
    public void shutdown() {

        running.set(false);

        // Wake up all waiting workers
        for (Thread worker : workers) {
            worker.interrupt();
        }
    }

    // Demo Program
    public static void main(String[] args) throws InterruptedException {

        // Create thread pool with
        // 3 workers
        // queue capacity = 10
        SimpleThreadPool pool =
                new SimpleThreadPool(3, 10);

        // Submit 10 tasks
        for (int i = 1; i <= 10; i++) {

            int taskNumber = i;

            pool.submit(() -> {

                System.out.println(
                        Thread.currentThread().getName()
                                + " executing Task "
                                + taskNumber);

                try {

                    Thread.sleep(2000);

                } catch (InterruptedException e) {

                    Thread.currentThread().interrupt();
                }

                System.out.println(
                        Thread.currentThread().getName()
                                + " completed Task "
                                + taskNumber);

            });
        }

        // Wait before shutdown
        Thread.sleep(10000);

        pool.shutdown();
    }
}
