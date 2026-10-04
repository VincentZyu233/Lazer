package dev.naominet.lazer

import com.sun.jna.Memory
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopNativeReaderBindingTest {
    @Test
    fun `native cancel and close callbacks close the source only once`() {
        val closeCount = AtomicInteger()
        val binding = NativeReaderBinding(
            TestSeekableSource(closeBlock = { closeCount.incrementAndGet() }) { _, _ -> -1 },
        )

        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(8)
        try {
            val closes = (0 until 16).map { index ->
                workers.submit {
                    start.await()
                    when (index % 3) {
                        0 -> binding.close()
                        1 -> binding.cancel.callback(null)
                        else -> binding.close.callback(null)
                    }
                }
            }
            start.countDown()
            closes.forEach { it.get(3, TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
        }

        assertEquals(1, closeCount.get())
    }

    @Test
    fun `reader callback distinguishes data temporary wait eof and failure`() {
        val destination = Memory(4)
        try {
            val dataSource = TestSeekableSource { buffer, _ ->
                buffer[0] = 0x12
                buffer[1] = 0x34
                2
            }
            val dataCount = NativeReaderBinding(dataSource).read.callback(null, destination, 4)
            assertEquals(2, dataCount)
            assertArrayEquals(byteArrayOf(0x12, 0x34), destination.getByteArray(0, 2))

            assertEquals(
                0,
                NativeReaderBinding(TestSeekableSource { _, _ -> 0 })
                    .read.callback(null, destination, 4),
            )
            assertEquals(
                -1,
                NativeReaderBinding(TestSeekableSource { _, _ -> -1 })
                    .read.callback(null, destination, 4),
            )
            assertEquals(
                LAZER_AUDIO_READER_IO_ERROR,
                NativeReaderBinding(TestSeekableSource { _, _ -> throw IOException("network failed") })
                    .read.callback(null, destination, 4),
            )
            assertEquals(
                LAZER_AUDIO_READER_IO_ERROR,
                NativeReaderBinding(TestSeekableSource { _, _ -> 5 })
                    .read.callback(null, destination, 4),
            )
        } finally {
            destination.close()
        }
    }

    private class TestSeekableSource(
        private val closeBlock: () -> Unit = {},
        private val readBlock: (ByteArray, Int) -> Int,
    ) : DesktopSeekableAudioSource {
        override fun read(buffer: ByteArray, length: Int): Int = readBlock(buffer, length)

        override fun seek(offset: Long): Long = offset

        override fun length(): Long = 4L

        override fun close() = closeBlock()
    }
}
