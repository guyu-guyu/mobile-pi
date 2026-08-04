package dev.mobilepi.runtime.rpc

import dev.mobilepi.runtime.process.ProcessExit
import dev.mobilepi.runtime.process.RawProcess
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiRpcClientTest {
    @Test
    fun `correlates responses that arrive out of order`() = runBlocking {
        val process = FakeRawProcess()
        val client = PiRpcClient(process, requestTimeoutMs = 2_000)
        client.start()

        val state = async { client.getState() }
        val prompt = async { client.prompt("hello") }
        val first = process.readCommand()
        val second = process.readCommand()
        val byCommand = listOf(first, second).associateBy { it["type"]?.jsonPrimitive?.content }

        process.writeStdout(response(byCommand.getValue("prompt"), "prompt-data"))
        process.writeStdout(response(byCommand.getValue("get_state"), "state-data"))

        assertEquals("state-data", state.await()?.jsonPrimitive?.content)
        assertEquals("prompt-data", prompt.await()?.jsonPrimitive?.content)
        client.stop(100)
    }

    @Test
    fun `protocol noise fails pending request and terminates process`() = runBlocking {
        val process = FakeRawProcess()
        val client = PiRpcClient(process, requestTimeoutMs = 2_000)
        client.start()
        val state = async { runCatching { client.getState() } }
        process.readCommand()

        process.writeStdout("not-json\n")

        assertTrue(state.await().exceptionOrNull() is RpcProtocolException)
        assertTrue(process.terminated.await())
    }

    @Test
    fun `secret is redacted from stderr diagnostics`() = runBlocking {
        val process = FakeRawProcess()
        val client = PiRpcClient(process, sensitiveValues = setOf("sk-secret"))
        val diagnostic = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) { client.diagnostics.firstValue() }
        }
        client.start()

        process.writeStderr("Authorization: Bearer sk-secret")

        val value = diagnostic.await()
        assertFalse(value.message.contains("sk-secret"))
        assertTrue(value.message.contains("[REDACTED]"))
        client.stop(100)
    }

    private fun response(request: kotlinx.serialization.json.JsonObject, data: String): String =
        """{"id":"${request["id"]?.jsonPrimitive?.content}","type":"response","command":"${request["type"]?.jsonPrimitive?.content}","success":true,"data":"$data"}""" + "\n"
}

private class FakeRawProcess : RawProcess {
    private val commandInput = PipedInputStream()
    override val stdin = PipedOutputStream(commandInput)
    override val stdout = PipedInputStream()
    private val stdoutWriter = PipedOutputStream(stdout as PipedInputStream)
    override val stderr = PipedInputStream()
    private val stderrWriter = PipedOutputStream(stderr as PipedInputStream)
    override val exit = CompletableDeferred<ProcessExit>()
    val terminated = CompletableDeferred<Boolean>()
    private val reader = commandInput.bufferedReader()
    private val json = Json

    suspend fun readCommand() = withContext(Dispatchers.IO) {
        json.parseToJsonElement(reader.readLine()).jsonObject
    }

    fun writeStdout(value: String) {
        stdoutWriter.write(value.toByteArray())
        stdoutWriter.flush()
    }

    fun writeStderr(value: String) {
        stderrWriter.write(value.toByteArray())
        stderrWriter.flush()
    }

    override suspend fun terminate(gracePeriodMs: Long) {
        if (!exit.isCompleted) exit.complete(ProcessExit(0, true))
        terminated.complete(true)
        runCatching { stdoutWriter.close() }
        runCatching { stderrWriter.close() }
    }
}

private suspend fun <T> kotlinx.coroutines.flow.SharedFlow<T>.firstValue(): T =
    first()
