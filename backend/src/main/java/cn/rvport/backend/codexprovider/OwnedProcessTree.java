package cn.rvport.backend.codexprovider;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 在父进程存活时记录后代，避免 wrapper 退出后丢失资源归属。 */
final class OwnedProcessTree {
    private final Set<ProcessHandle> owned = new LinkedHashSet<>();
    private final ProcessHandle root;

    OwnedProcessTree(ProcessHandle root) {
        this.root = root;
        owned.add(root);
    }

    void capture() {
        for (ProcessHandle process : List.copyOf(owned)) {
            process.descendants().forEach(owned::add);
        }
    }

    void terminate(ExecutionBudget cleanup) throws InterruptedException {
        capture();
        try {
            for (ProcessHandle process : List.copyOf(owned).reversed()) {
                if (!process.equals(root) && process.isAlive()) {
                    process.destroyForcibly();
                }
            }
            // 先给仍活着的 wrapper 回收子进程的机会，避免同时强杀留下孤儿僵尸。
            long started = System.nanoTime();
            while (owned.stream().anyMatch(process -> !process.equals(root) && process.isAlive())
                    && !cleanup.expired() && System.nanoTime() - started < 300_000_000) {
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(Math.min(cleanup.remaining().toNanos(), 10_000_000));
            }
        } finally {
            if (root.isAlive()) { root.destroyForcibly(); }
        }
    }

    boolean alive() {
        return owned.stream().anyMatch(ProcessHandle::isAlive);
    }
}
