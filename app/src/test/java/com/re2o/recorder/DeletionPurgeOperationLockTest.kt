package com.re2o.recorder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DeletionPurgeOperationLockTest {
    @Test fun concurrentDeleteAndPurgeAreSerializedWithoutDeadlock() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val overlap = AtomicBoolean(false)
        val firstInside = AtomicBoolean(false)

        val delete = Thread {
            DeletionPurgeOperationLock.withLock {
                firstInside.set(true)
                firstEntered.countDown()
                assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                firstInside.set(false)
            }
            completed.countDown()
        }
        val purge = Thread {
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            DeletionPurgeOperationLock.withLock {
                if (firstInside.get()) overlap.set(true)
                secondEntered.countDown()
            }
            completed.countDown()
        }

        delete.start()
        purge.start()
        assertFalse(secondEntered.await(150, TimeUnit.MILLISECONDS))
        releaseFirst.countDown()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(secondEntered.await(0, TimeUnit.MILLISECONDS))
        assertFalse(overlap.get())
        delete.join()
        purge.join()
    }
}