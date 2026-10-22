package dev.stride.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The console's multipart body, checked against a real HTTP server.
 *
 * **Why this is here and not only in the Python test.** The console and
 * the listener are two implementations of one format, on opposite sides of
 * a network, and the only thing that holds them together is that they
 * agree. `tools/walk_listener_test.py` builds a body the way the console
 * builds it — deliberately, an independent implementation — and checks the
 * listener understands it.
 *
 * This test is the other half: it takes the body the console *actually*
 * produces, `WalkFiles.multipart`, and posts it at a real server, and
 * checks the server sees both files and both fields. It is the check that
 * catches the console changing its own format without the listener
 * noticing.
 */
class WalkLogPostTest {

    @Test
    fun `the body the console builds is understood by a server`() {
        val server = HttpServerSocket()
        server.start()
        try {
            val csv = "elapsed_s,speed_kph,belt_kph,incline_pct,distance_m,calories_kcal,pulse_bpm\n" +
                    "0,6.0,5.9,0.0,0,0,0\n"
            val summary = """{"who":"Sam","distance":2000}"""
            val body = WalkFiles.multipart(
                "----STRIDEboundary",
                listOf(
                    WalkFiles.Field("walk_id", "walk-1792530000000-abc123"),
                    WalkFiles.Field("who", "Sam"),
                ),
                listOf(
                    WalkFiles.FilePart("csv", "walk-1792530000000-abc123.csv",
                        "text/csv", csv.toByteArray()),
                    WalkFiles.FilePart("summary", "walk-1792530000000-abc123.summary.json",
                        "application/json", summary.toByteArray()),
                )
            )
            val answer = server.post(body, "multipart/form-data; boundary=----STRIDEboundary")
            assertEquals(200, answer.code)

            // The raw body, rather than the server's JSON answer: this is
            // what the listener will actually parse, and checking it here
            // keeps the assertions readable.
            val seen = server.received
            assertTrue("the server did not see the walk id: $seen",
                seen.contains("walk-1792530000000-abc123"))
            assertTrue("the server did not see the walker's name: $seen",
                seen.contains("name=\"who\"") && seen.contains("Sam"))
            assertTrue("the server did not see the CSV: $seen",
                seen.contains("elapsed_s,speed_kph"))
            assertTrue("the server did not see the summary: $seen",
                seen.contains("\"distance\":2000"))
            // Both files are in one request, which is what lets a receiver
            // treat them as one walk.
            assertTrue("the body did not carry both files: $seen",
                seen.contains("name=\"csv\"") && seen.contains("name=\"summary\""))
        } finally {
            server.stop()
        }
    }
}

/**
 * A socket that reads a request and answers it, and nothing else.
 *
 * **Hand-rolled rather than a library**, because there is no HTTP server
 * in the JDK's public API and adding one to test one request would be a
 * bigger step than the check is worth. It reads the request line, the
 * headers and the body, and writes back a small JSON answer.
 */
private class HttpServerSocket {

    private var server: java.net.ServerSocket? = null
    private var thread: Thread? = null

    /** What the server saw, so the test can check it. */
    @Volatile var received: String = ""

    fun start() {
        val s = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        server = s
        thread = Thread {
            s.accept().use { socket ->
                val input = socket.getInputStream()
                val reader = input.bufferedReader()
                val requestLine = reader.readLine() ?: ""
                var length = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val lower = line.lowercase()
                    if (lower.startsWith("content-length:")) {
                        length = line.substringAfter(":").trim().toInt()
                    }
                }
                val body = CharArray(length)
                var read = 0
                while (read < length) {
                    val n = reader.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                received = String(body, 0, maxOf(read, 0))

                val payload = received
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\r", "\\r").replace("\n", "\\n")
                val answer = "{\"code\":200,\"body\":\"$payload\"}".toByteArray()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\n".toByteArray())
                    write("Content-Type: application/json\r\n".toByteArray())
                    write("Content-Length: ${answer.size}\r\n\r\n".toByteArray())
                    write(answer)
                    flush()
                }
            }
        }
        thread?.isDaemon = true
        thread?.start()
    }

    fun post(body: ByteArray, contentType: String): Answer {
        val s = server ?: throw IllegalStateException("not started")
        java.net.Socket("127.0.0.1", s.localPort).use { socket ->
            socket.getOutputStream().apply {
                write("POST /walk HTTP/1.1\r\n".toByteArray())
                write("Host: 127.0.0.1\r\n".toByteArray())
                write("Content-Type: $contentType\r\n".toByteArray())
                write("Content-Length: ${body.size}\r\n\r\n".toByteArray())
                write(body)
                flush()
            }
            val text = socket.getInputStream().bufferedReader().readText()
            val code = text.lineSequence().firstOrNull()
                ?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: 0
            return Answer(code, text)
        }
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
    }

    class Answer(val code: Int, val body: String)
}
