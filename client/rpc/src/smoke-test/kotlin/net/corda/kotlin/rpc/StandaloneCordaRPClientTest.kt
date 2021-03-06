package net.corda.kotlin.rpc

import com.google.common.hash.Hashing
import com.google.common.hash.HashingInputStream
import net.corda.client.rpc.CordaRPCConnection
import net.corda.core.crypto.SecureHash
import net.corda.core.internal.*
import net.corda.core.messaging.*
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.*
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.loggerFor
import net.corda.core.utilities.seconds
import net.corda.finance.DOLLARS
import net.corda.finance.POUNDS
import net.corda.finance.SWISS_FRANCS
import net.corda.finance.USD
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.getCashBalance
import net.corda.finance.contracts.getCashBalances
import net.corda.finance.flows.CashIssueFlow
import net.corda.finance.flows.CashPaymentFlow
import net.corda.nodeapi.User
import net.corda.smoketesting.NodeConfig
import net.corda.smoketesting.NodeProcess
import org.apache.commons.io.output.NullOutputStream
import org.bouncycastle.asn1.x500.X500Name
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.streams.toList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StandaloneCordaRPClientTest {
    private companion object {
        val log = loggerFor<StandaloneCordaRPClientTest>()
        val user = User("user1", "test", permissions = setOf("ALL"))
        val port = AtomicInteger(15200)
        const val attachmentSize = 2116
        val timeout = 60.seconds
    }

    private lateinit var factory: NodeProcess.Factory
    private lateinit var notary: NodeProcess
    private lateinit var rpcProxy: CordaRPCOps
    private lateinit var connection: CordaRPCConnection
    private lateinit var notaryNode: NodeInfo

    private val notaryConfig = NodeConfig(
        legalName = X500Name("CN=Notary Service,O=R3,OU=corda,L=Zurich,C=CH"),
        p2pPort = port.andIncrement,
        rpcPort = port.andIncrement,
        webPort = port.andIncrement,
        extraServices = listOf("corda.notary.validating"),
        users = listOf(user)
    )

    @Before
    fun setUp() {
        factory = NodeProcess.Factory()
        copyFinanceCordapp()
        notary = factory.create(notaryConfig)
        connection = notary.connect()
        rpcProxy = connection.proxy
        notaryNode = fetchNotaryIdentity()
    }

    @After
    fun done() {
        try {
            connection.close()
        } finally {
            notary.close()
        }
    }

    private fun copyFinanceCordapp() {
        val pluginsDir = (factory.baseDirectory(notaryConfig) / "plugins").createDirectories()
        // Find the finance jar file for the smoke tests of this module
        val financeJar = Paths.get("build", "resources", "smokeTest").list {
            it.filter { "corda-finance" in it.toString() }.toList().single()
        }
        financeJar.copyToDirectory(pluginsDir)
    }

    @Test
    fun `test attachments`() {
        val attachment = InputStreamAndHash.createInMemoryTestZip(attachmentSize, 1)
        assertFalse(rpcProxy.attachmentExists(attachment.sha256))
        val id = WrapperStream(attachment.inputStream).use { rpcProxy.uploadAttachment(it) }
        assertEquals(attachment.sha256, id, "Attachment has incorrect SHA256 hash")

        val hash = HashingInputStream(Hashing.sha256(), rpcProxy.openAttachment(id)).use { it ->
            it.copyTo(NullOutputStream())
            SecureHash.SHA256(it.hash().asBytes())
        }
        assertEquals(attachment.sha256, hash)
    }

    @Test
    fun `test starting flow`() {
        rpcProxy.startFlow(::CashIssueFlow, 127.POUNDS, OpaqueBytes.of(0), notaryNode.notaryIdentity)
            .returnValue.getOrThrow(timeout)
    }

    @Test
    fun `test starting tracked flow`() {
        var trackCount = 0
        val handle = rpcProxy.startTrackedFlow(
            ::CashIssueFlow, 429.DOLLARS, OpaqueBytes.of(0), notaryNode.notaryIdentity
        )
        val updateLatch = CountDownLatch(1)
        handle.progress.subscribe { msg ->
            log.info("Flow>> $msg")
            ++trackCount
            updateLatch.countDown()
        }
        handle.returnValue.getOrThrow(timeout)
        updateLatch.await()
        assertNotEquals(0, trackCount)
    }

    @Test
    fun `test network map`() {
        assertEquals(notaryConfig.legalName, notaryNode.legalIdentity.name)
    }

    @Test
    fun `test state machines`() {
        val (stateMachines, updates) = rpcProxy.stateMachinesFeed()
        assertEquals(0, stateMachines.size)

        val updateLatch = CountDownLatch(1)
        val updateCount = AtomicInteger(0)
        updates.subscribe { update ->
            if (update is StateMachineUpdate.Added) {
                log.info("StateMachine>> Id=${update.id}")
                updateCount.incrementAndGet()
                updateLatch.countDown()
            }
        }

        // Now issue some cash
        rpcProxy.startFlow(::CashIssueFlow, 513.SWISS_FRANCS, OpaqueBytes.of(0), notaryNode.notaryIdentity)
            .returnValue.getOrThrow(timeout)
        updateLatch.await()
        assertEquals(1, updateCount.get())
    }

    @Test
    fun `test vault track by`() {
        val (vault, vaultUpdates) = rpcProxy.vaultTrackBy<Cash.State>(paging = PageSpecification(DEFAULT_PAGE_NUM))
        assertEquals(0, vault.totalStatesAvailable)

        val updateLatch = CountDownLatch(1)
        vaultUpdates.subscribe { update ->
            log.info("Vault>> FlowId=${update.flowId}")
            updateLatch.countDown()
        }

        // Now issue some cash
        rpcProxy.startFlow(::CashIssueFlow, 629.POUNDS, OpaqueBytes.of(0), notaryNode.notaryIdentity)
                .returnValue.getOrThrow(timeout)
        updateLatch.await()

        // Check that this cash exists in the vault
        val cashBalance = rpcProxy.getCashBalances()
        log.info("Cash Balances: $cashBalance")
        assertEquals(1, cashBalance.size)
        assertEquals(629.POUNDS, cashBalance[Currency.getInstance("GBP")])
    }

    @Test
    fun `test vault query by`() {
        // Now issue some cash
        rpcProxy.startFlow(::CashIssueFlow, 629.POUNDS, OpaqueBytes.of(0), notaryNode.notaryIdentity)
                .returnValue.getOrThrow(timeout)

        val criteria = QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.ALL)
        val paging = PageSpecification(DEFAULT_PAGE_NUM, 10)
        val sorting = Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))

        val queryResults = rpcProxy.vaultQueryBy<Cash.State>(criteria, paging, sorting)
        assertEquals(1, queryResults.totalStatesAvailable)
        assertEquals(queryResults.states.first().state.data.amount.quantity, 629.POUNDS.quantity)

        rpcProxy.startFlow(::CashPaymentFlow, 100.POUNDS, notaryNode.legalIdentity).returnValue.getOrThrow()

        val moreResults = rpcProxy.vaultQueryBy<Cash.State>(criteria, paging, sorting)
        assertEquals(3, moreResults.totalStatesAvailable)   // 629 - 100 + 100

        // Check that this cash exists in the vault
        val cashBalances = rpcProxy.getCashBalances()
        log.info("Cash Balances: $cashBalances")
        assertEquals(1, cashBalances.size)
        assertEquals(629.POUNDS, cashBalances[Currency.getInstance("GBP")])
    }

    @Test
    fun `test cash balances`() {
        val startCash = rpcProxy.getCashBalances()
        println(startCash)
        assertTrue(startCash.isEmpty(), "Should not start with any cash")

        val flowHandle = rpcProxy.startFlow(::CashIssueFlow, 629.DOLLARS, OpaqueBytes.of(0), notaryNode.legalIdentity)
        println("Started issuing cash, waiting on result")
        flowHandle.returnValue.get()

        val balance = rpcProxy.getCashBalance(USD)
        println("Balance: " + balance)
        assertEquals(629.DOLLARS, balance)
    }

    private fun fetchNotaryIdentity(): NodeInfo {
        val nodeInfo = rpcProxy.networkMapSnapshot()
        assertEquals(1, nodeInfo.size)
        return nodeInfo[0]
    }

    // This InputStream cannot have been whitelisted.
    private class WrapperStream(input: InputStream) : FilterInputStream(input)
}
