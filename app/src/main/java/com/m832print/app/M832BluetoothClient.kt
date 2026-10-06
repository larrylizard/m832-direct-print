package com.m832print.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * Sends a finished M832 byte stream over Bluetooth Classic SPP (RFCOMM).
 *
 * Five independent, isolated fixes to an earlier single-threaded
 * implementation each failed to change a real phone's symptom (full byte
 * count always reported, but the printer only ever produces 1-2cm of
 * output): chunk size + delay pacing, reading the printer's response
 * before closing the socket, createInsecureRfcommSocketToServiceRecord()
 * instead of the secure variant, and BluetoothAdapter.cancelDiscovery()
 * before connecting (all confirmed on real hardware 2026-10-05). The
 * exact same bytes always printed correctly when replayed from a VM's
 * generic USB Bluetooth dongle (10_bt_rfcomm_replay.py), so the bug is
 * specific to this phone's own Bluetooth stack in some way no isolated
 * detail fix touched.
 *
 * Rather than guess at another isolated detail, this is a line-by-line
 * architectural port of the official Phomemo app's own connector
 * (com.quyin.phomemo 4.1.1, decompiled with jadx/apktool, class sk/e):
 *   - cancelDiscovery(), then createInsecureRfcommSocketToServiceRecord()
 *     and connect() (sk/e.q())
 *   - a dedicated reader thread that loops on InputStream.read() for the
 *     whole life of the connection, started right after connect()
 *     succeeds -- not a one-shot read at the end (sk/e.w(), sk/e$a)
 *   - a dedicated writer thread that pulls byte[] chunks off a
 *     LinkedBlockingQueue and does exactly one unbuffered
 *     OutputStream.write() per chunk -- no flush(), no sleep() anywhere
 *     in their connector (sk/e.w(), sk/e$b)
 *   - the producer splits any payload over 1024 bytes into exactly
 *     1024-byte pieces before queueing (sk/e.m()/y(); 1024 is their
 *     field `i`, set in their constructor) and relies entirely on the
 *     writer thread's blocking write() calls plus the queue's own
 *     blocking behavior for pacing -- no explicit delay
 */
object M832BluetoothClient {
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val CHUNK_SIZE = 1024 // sk/e's field `i` -- the exact value the official app chunks by

    // Real measured throughput, 2026-10-06 (m832/docs/findings.md,
    // 14_bt_completion_probe.py): a plain unpaced sock.sendall() of the
    // same 1,474,584-byte job this app sends took 88.01s end to end over
    // this printer's Bluetooth link -- ~16.7 KB/s actual, not a guess.
    // That measurement was a raw BlueZ socket, which blocks realistically
    // on real transmission; there's no equivalent proof Android's
    // BluetoothSocket.write() blocks the same way rather than absorbing
    // the whole job into some larger internal buffer and returning early
    // (which would explain why the full-image case still needed this
    // wait at all -- see below). So WRITER_JOIN_TIMEOUT_MS has to assume
    // the writer thread's own write() calls could legitimately take close
    // to that long for a large job, not just a few seconds, or join()
    // could return before the writer is actually done and let the
    // `finally` block below close the socket mid-transmission.
    private const val WRITER_JOIN_TIMEOUT_MS = 180_000L

    // Same real measurement, with a safety margin (lowballed to ~60% of
    // the measured 16.7 KB/s) for the post-write drain wait below. An
    // earlier attempt waited for the printer's own reply instead of a
    // fixed estimate, but that latched on whatever byte it sent back
    // first, which can arrive early (e.g. right after the header
    // commands) and made the wait a no-op for anything but a tiny job
    // (2026-10-06 user test: ~20% of a larger image missing, cut cleanly,
    // no error). Pure-guess size-proportional waits before this (8000
    // bytes/sec/30s cap, then 3000/60s) got the raster content through
    // but were still too tight for the tiny tail feed commands right
    // after it (2026-10-06 user test: full image printed, no tail feed)
    // -- unsurprising now that the real throughput and the cap are both
    // known: those caps were lower than what a large job actually needs.
    // The same probe also found the printer only needs ~5.3s after
    // transmission truly finishes to settle to idle (findings.md), so
    // MIN_DRAIN_WAIT_MS covers that specific tail with margin rather than
    // guessing at it separately. Under-waiting silently drops data;
    // over-waiting just costs extra seconds on a job that isn't
    // time-critical.
    private const val ASSUMED_BYTES_PER_SECOND = 10_000.0
    private const val MIN_DRAIN_WAIT_MS = 8_000L
    private const val MAX_DRAIN_WAIT_MS = 180_000L

    // 2026-10-06 (findings.md, 15_bt_concurrent_probe.py): confirmed 3/3 on
    // real hardware, reading concurrently instead of only after send (which
    // the first attempt at this showed produces a jumbled, misleading
    // buffered burst) -- `1a 0f 0c` followed about 0.2s later by the
    // already-known idle reply `1a 06 89` is a real, reproducible "just
    // finished" signal, not an artifact, confirmed across both a 3,284-byte
    // and a 1,474,584-byte job. So: poll for *any* fresh read activity
    // after the writer has finished, rather than guessing a flat delay --
    // if the printer actually answers, we can close as soon as that grace
    // period passes instead of waiting out the full conservative estimate.
    // Only ~5s of real hardware evidence backs this (not the hundreds of
    // runs it'd take to call it bulletproof), so the size-proportional
    // drain wait above stays as a fallback for whenever no reply arrives
    // in time -- this shortens the common case without removing the
    // safety net for the uncommon one.
    private const val RESPONSE_POLL_TIMEOUT_MS = 15_000L
    private const val RESPONSE_GRACE_MS = 1_000L // covers the ~0.2s gap to the second reply plus margin

    class PrintResult(val bytesSent: Int, val totalBytes: Int)

    /** Mirrors sk/e$b: pulls queued chunks and writes each one, unbuffered, on its own thread. */
    private class Writer(private val out: OutputStream) : Thread("BtWriteThread") {
        val queue = LinkedBlockingQueue<ByteArray>()
        @Volatile var error: IOException? = null

        override fun run() {
            while (true) {
                val chunk = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    return
                }
                if (chunk.isEmpty()) return // poison pill: all real chunks are non-empty
                try {
                    out.write(chunk)
                } catch (e: IOException) {
                    error = e
                    return
                }
            }
        }
    }

    // The two confirmed reply patterns (findings.md, 15_bt_concurrent_probe.py)
    // -- deliberately NOT "any byte", see Reader's doc below.
    private val KNOWN_DONE_REPLIES = listOf(
        byteArrayOf(0x1a, 0x0f, 0x0c),
        byteArrayOf(0x1a, 0x06.toByte(), 0x89.toByte()),
    )

    /**
     * Mirrors sk/e$a: drains the input stream for the whole connection
     * lifetime. Also records when a recognized "done" reply arrives so
     * sendJob() can detect it happened strictly after the writer finished
     * (see RESPONSE_POLL_TIMEOUT_MS's doc above). This intentionally does
     * NOT treat just any received byte as that signal: real hardware
     * testing found sparse, unexplained bytes (`1a 03 a8`/`1a 03 a9`) can
     * also arrive mid-transmission (findings.md, 2026-10-06) -- an
     * earlier version of this polling logic treated any fresh byte as
     * "done" and closed after only a 1s grace period on seeing one of
     * those, cutting off the tail feed commands while the job was still
     * genuinely in flight (2026-10-06 user test: printed fully, but no
     * tail feed, on the version with that bug).
     */
    private class Reader(private val input: InputStream) : Thread("BtReadThread") {
        @Volatile var lastKnownDoneReplyAtNanos: Long = 0

        override fun run() {
            val buf = ByteArray(1024)
            while (true) {
                try {
                    val n = input.read(buf)
                    if (n == -1) return
                    if (containsKnownDoneReply(buf, n)) {
                        lastKnownDoneReplyAtNanos = System.nanoTime()
                    }
                } catch (_: IOException) {
                    return
                }
            }
        }

        private fun containsKnownDoneReply(buf: ByteArray, len: Int): Boolean {
            for (pattern in KNOWN_DONE_REPLIES) {
                for (start in 0..len - pattern.size) {
                    if (pattern.indices.all { buf[start + it] == pattern[it] }) return true
                }
            }
            return false
        }
    }

    /**
     * Blocking call -- must be run off the main thread. Connects exactly
     * as the official app does, starts the reader/writer threads, feeds
     * the whole job through the writer's queue in 1024-byte pieces, waits
     * for the writer to actually drain the queue, then closes the socket.
     *
     * Confirmed 2026-10-05/06 on a plain (non-GrapheneOS) Android device,
     * where the connection itself works correctly end to end: closing the
     * socket right after the writer drains its queue still cuts off
     * whatever was still physically in flight, up to a large (~20%)
     * fraction of a bigger job -- see WRITER_JOIN_TIMEOUT_MS and
     * ASSUMED_BYTES_PER_SECOND's docs above for the real measurement this
     * is now based on, instead of a pure guess.
     */
    @SuppressLint("MissingPermission") // caller is required to have checked BLUETOOTH_CONNECT first
    fun sendJob(
        device: BluetoothDevice,
        data: ByteArray,
        onProgress: (sent: Int, total: Int) -> Unit,
        onPhase: (String) -> Unit = {},
    ): PrintResult {
        try {
            BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() // sk/e.q() -- needs BLUETOOTH_SCAN on API 31+, which this app doesn't separately request
        } catch (_: SecurityException) {
        }

        var socket: BluetoothSocket? = null
        var writer: Writer? = null
        try {
            socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            socket.connect()

            val reader = Reader(socket.inputStream).also { it.start() }
            writer = Writer(socket.outputStream).also { it.start() }

            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + CHUNK_SIZE, data.size)
                writer.queue.put(data.copyOfRange(offset, end))
                writer.error?.let { throw it }
                offset = end
                onProgress(offset, data.size)
            }
            val queueDrainedAtMs = System.currentTimeMillis()

            writer.queue.put(ByteArray(0)) // poison pill: let the writer finish draining before we close anything
            writer.join(WRITER_JOIN_TIMEOUT_MS)
            writer.error?.let { throw it }
            val writerJoinMs = System.currentTimeMillis() - queueDrainedAtMs
            onPhase("writer.join() took ${writerJoinMs}ms after all chunks were queued")

            val drainWaitMs = (data.size / ASSUMED_BYTES_PER_SECOND * 1000)
                .toLong()
                .coerceIn(MIN_DRAIN_WAIT_MS, MAX_DRAIN_WAIT_MS)
            val pollStartMs = System.currentTimeMillis()
            val sawKnownReply = awaitReplyOrFallback(reader, drainWaitMs)
            val waitMs = System.currentTimeMillis() - pollStartMs
            onPhase(
                if (sawKnownReply) "saw known done-reply, waited ${waitMs}ms after writer finished"
                else "no known done-reply seen, used fallback wait of ${waitMs}ms"
            )

            return PrintResult(offset, data.size)
        } finally {
            try {
                socket?.close()
            } catch (_: IOException) {
                // nothing more to do if close itself fails
            }
        }
    }

    /**
     * Polls for a recognized done-reply (KNOWN_DONE_REPLIES) that arrived
     * strictly after this point in time (the writer having just
     * finished), up to RESPONSE_POLL_TIMEOUT_MS. If one shows up, waits
     * RESPONSE_GRACE_MS more for the rest of the reply sequence and
     * returns -- closing sooner than the full fallback wait. If nothing
     * recognized arrives in time, falls back to fallbackWaitMs (minus
     * whatever time was already spent polling).
     */
    private fun awaitReplyOrFallback(reader: Reader, fallbackWaitMs: Long): Boolean {
        val pollStartNanos = System.nanoTime()
        val pollDeadlineNanos = pollStartNanos + RESPONSE_POLL_TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < pollDeadlineNanos) {
            if (reader.lastKnownDoneReplyAtNanos > pollStartNanos) {
                Thread.sleep(RESPONSE_GRACE_MS)
                return true
            }
            Thread.sleep(150L)
        }
        val alreadyWaitedMs = (System.nanoTime() - pollStartNanos) / 1_000_000L
        val remainingMs = fallbackWaitMs - alreadyWaitedMs
        if (remainingMs > 0) {
            Thread.sleep(remainingMs)
        }
        return false
    }
}
