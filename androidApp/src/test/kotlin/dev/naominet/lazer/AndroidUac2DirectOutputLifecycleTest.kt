package dev.naominet.lazer

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUac2DirectOutputLifecycleTest {
    @Test
    fun `provider close waits for in-progress output creation and owns its transport`() {
        val transport = FakeTransport()
        val lifecycle = lifecycle()
        val factoryEntered = CountDownLatch(1)
        val allowFactoryToReturn = CountDownLatch(1)
        val creationCompleted = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val closeCompleted = CountDownLatch(1)
        val providerReleased = AtomicBoolean(false)
        val output = AtomicReference<FakeTransport?>()
        val creationFailure = AtomicReference<Throwable?>()
        val closeFailure = AtomicReference<Throwable?>()

        val creator = Thread {
            try {
                output.set(
                    lifecycle.createOutput(
                        createTransport = {
                            factoryEntered.countDown()
                            assertTrue(allowFactoryToReturn.await(5, TimeUnit.SECONDS))
                            transport
                        },
                        createAudioOutput = { it },
                    ),
                )
            } catch (error: Throwable) {
                creationFailure.set(error)
            } finally {
                creationCompleted.countDown()
            }
        }
        creator.start()
        assertTrue(factoryEntered.await(5, TimeUnit.SECONDS))

        val closer = Thread {
            closeStarted.countDown()
            try {
                lifecycle.closeProvider { providerReleased.set(true) }
            } catch (error: Throwable) {
                closeFailure.set(error)
            } finally {
                closeCompleted.countDown()
            }
        }
        closer.start()
        assertTrue(closeStarted.await(5, TimeUnit.SECONDS))

        try {
            awaitBlocked(closer)
            assertEquals("provider close must wait until creation hands off ownership", 1L, closeCompleted.count)
        } finally {
            allowFactoryToReturn.countDown()
        }

        assertTrue(creationCompleted.await(5, TimeUnit.SECONDS))
        assertTrue(closeCompleted.await(5, TimeUnit.SECONDS))
        creator.join(5_000L)
        closer.join(5_000L)

        assertEquals(null, creationFailure.get())
        assertEquals(null, closeFailure.get())
        assertSame(transport, output.get())
        assertTrue(providerReleased.get())
        assertEquals(1, transport.closeCount.get())
        assertFalse(transport.closedAsDetached.get())
        assertFalse(lifecycle.isAvailable)

        var factoryCalled = false
        assertThrows(IllegalStateException::class.java) {
            lifecycle.createOutput(
                createTransport = { factoryCalled = true; FakeTransport() },
                createAudioOutput = { it },
            )
        }
        assertFalse(factoryCalled)
    }

    @Test
    fun `device detach fences creation while an output is being constructed`() {
        val transport = FakeTransport()
        val lifecycle = lifecycle()
        val factoryEntered = CountDownLatch(1)
        val allowFactoryToReturn = CountDownLatch(1)
        val creationCompleted = CountDownLatch(1)
        val detachStarted = CountDownLatch(1)
        val detachCompleted = CountDownLatch(1)
        val creationFailure = AtomicReference<Throwable?>()
        val detachFailure = AtomicReference<Throwable?>()

        val creator = Thread {
            try {
                lifecycle.createOutput(
                    createTransport = {
                        factoryEntered.countDown()
                        assertTrue(allowFactoryToReturn.await(5, TimeUnit.SECONDS))
                        transport
                    },
                    createAudioOutput = { it },
                )
            } catch (error: Throwable) {
                creationFailure.set(error)
            } finally {
                creationCompleted.countDown()
            }
        }
        creator.start()
        assertTrue(factoryEntered.await(5, TimeUnit.SECONDS))

        val detacher = Thread {
            detachStarted.countDown()
            try {
                lifecycle.onDeviceDetached()
            } catch (error: Throwable) {
                detachFailure.set(error)
            } finally {
                detachCompleted.countDown()
            }
        }
        detacher.start()
        assertTrue(detachStarted.await(5, TimeUnit.SECONDS))

        try {
            awaitBlocked(detacher)
        } finally {
            allowFactoryToReturn.countDown()
        }

        assertTrue(creationCompleted.await(5, TimeUnit.SECONDS))
        assertTrue(detachCompleted.await(5, TimeUnit.SECONDS))
        creator.join(5_000L)
        detacher.join(5_000L)

        assertEquals(null, creationFailure.get())
        assertEquals(null, detachFailure.get())
        assertEquals(1, transport.closeCount.get())
        assertTrue(transport.closedAsDetached.get())
        assertFalse(lifecycle.isAvailable)

        var factoryCalled = false
        assertThrows(IllegalStateException::class.java) {
            lifecycle.createOutput(
                createTransport = { factoryCalled = true; FakeTransport() },
                createAudioOutput = { it },
            )
        }
        assertFalse(factoryCalled)
    }

    private fun lifecycle() = AndroidUac2DirectOutputLifecycle<FakeTransport> { transport, detached ->
        transport.closeCount.incrementAndGet()
        transport.closedAsDetached.set(detached)
    }

    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.BLOCKED && thread.isAlive && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertEquals(Thread.State.BLOCKED, thread.state)
    }

    private class FakeTransport {
        val closeCount = AtomicInteger()
        val closedAsDetached = AtomicBoolean()
    }
}
