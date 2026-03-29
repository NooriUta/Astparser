package com.hound.processor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Менеджер пула потоков для обработки файлов
 */
public class ThreadPoolManager {
    private static final Logger logger = LoggerFactory.getLogger(ThreadPoolManager.class);

    private final ExecutorService executorService;
    private final int threadCount;
    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final CountDownLatch completionLatch;

    public ThreadPoolManager(int threadCount) {
        this.threadCount = threadCount;
        this.executorService = Executors.newFixedThreadPool(threadCount, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setName("hound-worker-" + counter.incrementAndGet());
                thread.setDaemon(false);
                thread.setPriority(Thread.NORM_PRIORITY);
                return thread;
            }
        });
        this.completionLatch = new CountDownLatch(1);
        logger.info("Thread pool initialized with {} threads", threadCount);
    }

    /**
     * Отправляет задачу на выполнение
     */
    public void submit(Runnable task) {
        activeTasks.incrementAndGet();
        executorService.submit(() -> {
            try {
                task.run();
            } finally {
                if (activeTasks.decrementAndGet() == 0) {
                    completionLatch.countDown();
                }
            }
        });
    }

    /**
     * Отправляет задачу с возвратом результата
     */
    public <T> Future<T> submit(Callable<T> task) {
        activeTasks.incrementAndGet();
        return executorService.submit(() -> {
            try {
                return task.call();
            } finally {
                if (activeTasks.decrementAndGet() == 0) {
                    completionLatch.countDown();
                }
            }
        });
    }

    /**
     * Ожидает завершения всех задач с таймаутом
     */
    public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
        return completionLatch.await(timeout, unit);
    }

    /**
     * Ожидает завершения всех задач (без таймаута)
     */
    public void awaitCompletion() throws InterruptedException {
        completionLatch.await();
    }

    /**
     * Останавливает пул потоков и ожидает завершения
     */
    public void shutdownAndWait() {
        logger.info("Shutting down thread pool...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                logger.warn("Forcing shutdown of thread pool...");
                executorService.shutdownNow();
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.error("Thread pool did not terminate");
                }
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("Thread pool shut down");
    }

    /**
     * Немедленная остановка пула потоков
     */
    public void shutdownNow() {
        logger.warn("Immediately shutting down thread pool");
        executorService.shutdownNow();
    }

    /**
     * Проверяет, завершены ли все задачи
     */
    public boolean isAllTasksCompleted() {
        return activeTasks.get() == 0;
    }

    /**
     * Получает количество активных задач
     */
    public int getActiveTaskCount() {
        return activeTasks.get();
    }

    /**
     * Получает количество потоков
     */
    public int getThreadCount() {
        return threadCount;
    }

    /**
     * Создает пул с фиксированным количеством потоков
     */
    public static ThreadPoolManager newFixedThreadPool(int threadCount) {
        return new ThreadPoolManager(threadCount);
    }

    /**
     * Создает пул с количеством потоков равным количеству ядер процессора
     */
    public static ThreadPoolManager newCachedThreadPool() {
        return new ThreadPoolManager(Runtime.getRuntime().availableProcessors());
    }

    /**
     * Создает пул с одним потоком
     */
    public static ThreadPoolManager newSingleThreadPool() {
        return new ThreadPoolManager(1);
    }
}