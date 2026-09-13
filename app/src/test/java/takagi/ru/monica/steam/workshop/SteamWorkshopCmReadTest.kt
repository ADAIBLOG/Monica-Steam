package takagi.ru.monica.steam.workshop

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import takagi.ru.monica.steam.network.SteamApiClient
import takagi.ru.monica.steam.network.SteamProtoReader
import takagi.ru.monica.steam.network.SteamProtoWriter
import takagi.ru.monica.steam.network.cm.*
import takagi.ru.monica.steam.session.domain.SteamAccountSessionResolver

/** Exercises real CM framing, connection errors, Workshop decoding and UI state together. */
@OptIn(ExperimentalCoroutinesApi::class)
class SteamWorkshopCmReadTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val pools = mutableListOf<SteamCmConnectionPool>()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { pools.forEach { it.close() }; Dispatchers.resetMain() }

    @Test fun authenticatedSubscriptionRecordsReachTheListThroughCm() = runTest(scheduler) {
        val transport = SubscriptionSocketFactory()
        val vm = viewModel(transport)
        vm.query(WorkshopQuery(subscribedOnly = true, sort = WorkshopSort.UPDATED))
        advanceUntilIdle()
        assertNull(vm.state.value.failure)
        assertEquals(listOf("123", "18446744073709551614"), vm.state.value.items.map { it.id })
        assertTrue(vm.state.value.items.all { it.appId == 550 && it.subscribed == true })
        assertEquals("中文模组", vm.state.value.items.first().title)
        assertEquals(listOf("地图"), vm.state.value.items.first().tags)
        assertEquals(3, vm.state.value.total)
        assertFalse(vm.state.value.hasMore)
        assertEquals(1, transport.operations.size)
        val request = transport.operations.single()
        assertEquals("PublishedFile.GetUserFiles#1", request.header.targetJobName)
        val fields = SteamProtoReader(request.body).parse()
        assertEquals("76561198000000001", fields[1]?.asFixed64UnsignedString)
        assertEquals(550, fields[2]?.asInt)
        assertEquals(1, fields[4]?.asInt)
        assertEquals(30, fields[5]?.asInt)
        assertEquals("mysubscriptions", fields[6]?.asString)
    }

    @Test fun expiredCmServiceSessionRefreshesOnceAndRecoversRealSubscriptionRows() = runTest(scheduler) {
        val transport = SubscriptionSocketFactory(serviceResults = listOf(21, 1))
        val refreshes = mutableListOf<Boolean>()
        val vm = viewModel(transport, refreshes)
        vm.query(WorkshopQuery(subscribedOnly = true, sort = WorkshopSort.UPDATED))
        advanceUntilIdle()
        assertEquals(listOf(false, true), refreshes)
        assertNull(vm.state.value.failure)
        assertEquals(2, vm.state.value.items.size)
        assertEquals(2, transport.operations.size)
    }

    @Test fun expiredCmLogonRefreshesBeforeSendingTheSubscriptionRequest() = runTest(scheduler) {
        val transport = SubscriptionSocketFactory(logonResults = listOf(27, 1))
        val refreshes = mutableListOf<Boolean>()
        val vm = viewModel(transport, refreshes)
        vm.query(WorkshopQuery(subscribedOnly = true, sort = WorkshopSort.UPDATED))
        advanceUntilIdle()
        assertEquals(listOf(false, true), refreshes)
        assertNull(vm.state.value.failure)
        assertEquals(2, vm.state.value.items.size)
        assertEquals(1, transport.operations.size)
    }

    @Test fun rateLimitAndPermissionFailuresKeepTheirMeaningWithoutRefreshingOrReplaying() = runTest(scheduler) {
        for ((result, failure) in listOf(84 to WorkshopFailure.RATE_LIMIT, 15 to WorkshopFailure.UNAVAILABLE)) {
            val transport = SubscriptionSocketFactory(serviceResults = listOf(result))
            val refreshes = mutableListOf<Boolean>()
            val vm = viewModel(transport, refreshes)
            vm.query(WorkshopQuery(subscribedOnly = true, sort = WorkshopSort.UPDATED))
            advanceUntilIdle()
            assertEquals(failure, vm.state.value.failure)
            assertEquals(listOf(false), refreshes)
            assertEquals(1, transport.operations.size)
        }
    }

    private fun viewModel(
        transport: SubscriptionSocketFactory,
        refreshes: MutableList<Boolean> = mutableListOf()
    ): SteamWorkshopViewModel {
        val http = OkHttpClient.Builder().addInterceptor { error("No real HTTP or Web API calls") }.build()
        val pool = SteamCmConnectionPool(
            bootstrap = SteamCmBootstrapLoader { account ->
                SteamCmBootstrapData(account.steamId.toLong(), requireNotNull(account.accessToken), listOf("cm1.steamserver.net:443"))
            },
            socketClient = http,
            timeoutMillis = 100,
            socketFactory = transport
        ).also(pools::add)
        return SteamWorkshopViewModel(
            550, SteamWorkshopViewModelTest.account(),
            SteamWorkshopService(SteamApiClient(http), dispatcher, SteamCmClient(pool)),
            SteamAccountSessionResolver { account, force ->
                refreshes += force
                if (force) account.copy(accessToken = "fresh") else account
            }, dispatcher
        )
    }
}

private class SubscriptionSocketFactory(
    private val logonResults: List<Int> = listOf(1),
    private val serviceResults: List<Int> = listOf(1)
) : (Request, WebSocketListener) -> WebSocket {
    private var logons = 0
    val operations = mutableListOf<SteamCmEnvelope>()

    override fun invoke(request: Request, listener: WebSocketListener): WebSocket {
        val socket = object : WebSocket {
            override fun request() = request
            override fun queueSize() = 0L
            override fun send(text: String) = error("Expected binary CM frames")
            override fun close(code: Int, reason: String?) = true
            override fun cancel() = Unit
            override fun send(bytes: ByteString): Boolean {
                val envelope = SteamCmProtocol.decodeMessages(bytes.toByteArray()).single()
                val response: ByteArray
                if (envelope.eMsg == SteamCmProtocol.EMSG_CLIENT_LOGON) {
                    val result = logonResults.getOrElse(logons++) { logonResults.last() }
                    response = SteamCmProtocol.encodeMessage(
                        SteamCmProtocol.EMSG_CLIENT_LOGON_RESPONSE, 76561198000000001L, 42,
                        SteamProtoWriter().apply { writeVarint(1, result.toLong()) }.toByteArray()
                    )
                } else {
                    operations += envelope
                    val result = serviceResults.getOrElse(operations.lastIndex) { serviceResults.last() }
                    val header = SteamProtoWriter().apply {
                        writeFixed64(11, envelope.header.jobIdSource)
                        writeVarint(13, result.toLong())
                    }.toByteArray()
                    val body = if (result == 1) subscriptionPage() else byteArrayOf()
                    response = ByteBuffer.allocate(8 + header.size + body.size).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(SteamCmProtocol.EMSG_SERVICE_METHOD_RESPONSE or Int.MIN_VALUE)
                        .putInt(header.size).put(header).put(body).array()
                }
                listener.onMessage(this, response.toByteString())
                return true
            }
        }
        listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(101).message("Switching Protocols").build())
        return socket
    }

    private fun subscriptionPage() = SteamProtoWriter().apply {
        writeVarint(1, 3)
        // Live Steam responses use one-based item indices (1, 31, ...).
        writeVarint(2, 1)
        for ((id, title) in listOf("123" to "中文模组", "18446744073709551614" to "Another mod")) {
            writeMessage(3, SteamProtoWriter().apply {
                writeVarint(1, 1); writeUint64(2, id); writeFixed64(3, 76561198000000001L)
                writeVarint(5, 550); writeString(16, title); writeVarint(20, 100); writeBool(35, true)
                writeMessage(52, SteamProtoWriter().apply { writeString(1, "Maps"); writeString(3, "地图") })
                writeMessage(55, SteamProtoWriter().apply { writeFixed32(1, 1.0f.toRawBits().toLong()) })
            })
        }
        writeMessage(3, SteamProtoWriter().apply { writeVarint(1, 9); writeUint64(2, "456") })
    }.toByteArray()
}
