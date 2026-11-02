package cn.rvport.backend.codexprovider;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class OwnedProcessTreeTests {
    @Test
    void waitsForChildReapingAndForcesAnUnresponsiveRoot() throws Exception {
        ProcessHandle root = mock(ProcessHandle.class);
        ProcessHandle child = mock(ProcessHandle.class);
        ProcessHandle finished = mock(ProcessHandle.class);
        when(root.descendants()).thenAnswer(i -> Stream.of(child, finished));
        when(child.descendants()).thenAnswer(i -> Stream.empty());
        when(finished.descendants()).thenAnswer(i -> Stream.empty());
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        when(child.isAlive()).thenAnswer(i -> checks.incrementAndGet() < 5);
        when(root.isAlive()).thenReturn(true);
        when(finished.isAlive()).thenReturn(false);
        OwnedProcessTree tree = new OwnedProcessTree(root);
        tree.terminate(new ExecutionBudget(java.time.Duration.ofSeconds(1)));
        verify(root).destroyForcibly();
        verify(child).destroyForcibly();
        verify(finished, never()).destroyForcibly();
        assertThat(checks.get()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void retainsOrphanedDescendantsAndNeverTouchesUnrelatedProcesses() throws Exception {
        ProcessHandle root = mock(ProcessHandle.class);
        ProcessHandle child = mock(ProcessHandle.class);
        ProcessHandle unrelated = mock(ProcessHandle.class);
        when(root.descendants()).thenAnswer(i -> Stream.of(child));
        when(child.descendants()).thenAnswer(i -> Stream.empty());
        when(root.isAlive()).thenReturn(false);
        when(child.isAlive()).thenReturn(true);
        OwnedProcessTree tree = new OwnedProcessTree(root);
        tree.capture();
        when(root.descendants()).thenAnswer(i -> Stream.empty());
        tree.terminate(new ExecutionBudget(java.time.Duration.ofNanos(1)));
        verify(child).destroyForcibly();
        verify(root, never()).destroyForcibly();
        verifyNoInteractions(unrelated);
        assertThat(tree.alive()).isTrue();
        when(child.isAlive()).thenReturn(false);
        assertThat(tree.alive()).isFalse();
    }
}
