/*
 * Contributions to SpotBugs
 * Copyright (C) 2019, kengo
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package edu.umd.cs.findbugs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CurrentThreadExecutorServiceTest {

    @Test
    void testCurrentThread() throws InterruptedException {
        Thread currentThread = Thread.currentThread();
        ExecutorService executorService = new CurrentThreadExecutorService();
        AtomicBoolean isCalled = new AtomicBoolean();
        try {
            executorService.execute(() -> {
                assertEquals(currentThread, Thread.currentThread());
                isCalled.set(true);
            });
            assertTrue(isCalled.get());
        } finally {
            executorService.shutdown();
        }
        assertTrue(executorService.awaitTermination(1, TimeUnit.SECONDS));
        assertTrue(executorService.isShutdown());
        assertTrue(executorService.isTerminated());
    }

    @Test
    void testCloseTwice() {
        ExecutorService executorService = new CurrentThreadExecutorService();
        List<Runnable> remaining = executorService.shutdownNow();
        assertTrue(remaining.isEmpty());

        Assertions.assertThrows(IllegalStateException.class, () -> {
            executorService.shutdown();
        });
    }

    @Test
    void awaitTerminationWithoutShutdown() {
        ExecutorService executorService = new CurrentThreadExecutorService();
        Assertions.assertThrows(IllegalStateException.class, () -> {
            executorService.awaitTermination(1, TimeUnit.SECONDS);
        });
    }
}
