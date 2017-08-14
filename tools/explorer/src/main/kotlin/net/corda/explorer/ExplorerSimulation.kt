package net.corda.explorer

import joptsimple.OptionSet
import net.corda.client.mock.ErrorFlowsEventGenerator
import net.corda.client.mock.EventGenerator
import net.corda.client.mock.Generator
import net.corda.client.mock.pickOne
import net.corda.client.rpc.CordaRPCConnection
import net.corda.core.contracts.Amount
import net.corda.core.identity.Party
import net.corda.core.internal.concurrent.thenMatch
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.FlowHandle
import net.corda.core.messaging.startFlow
import net.corda.core.node.services.ServiceInfo
import net.corda.core.node.services.ServiceType
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.finance.GBP
import net.corda.finance.USD
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.flows.AbstractCashFlow
import net.corda.finance.flows.CashExitFlow
import net.corda.finance.flows.CashExitFlow.ExitRequest
import net.corda.finance.flows.CashIssueAndPaymentFlow
import net.corda.finance.flows.CashIssueAndPaymentFlow.IssueAndPaymentRequest
import net.corda.finance.flows.CashPaymentFlow
import net.corda.node.services.FlowPermissions.Companion.startFlowPermission
import net.corda.node.services.transactions.SimpleNotaryService
import net.corda.nodeapi.User
import net.corda.testing.ALICE
import net.corda.testing.BOB
import net.corda.testing.DUMMY_NOTARY
import net.corda.testing.driver.NodeHandle
import net.corda.testing.driver.PortAllocation
import net.corda.testing.driver.driver
import org.bouncycastle.asn1.x500.X500Name
import java.time.Instant
import java.util.*

class ExplorerSimulation(val options: OptionSet) {
    private val user = User("user1", "test", permissions = setOf(
            startFlowPermission<CashPaymentFlow.Initiate>()
    ))
    private val manager = User("manager", "test", permissions = setOf(
            startFlowPermission<CashIssueAndPaymentFlow>(),
            startFlowPermission<CashPaymentFlow.Initiate>(),
            startFlowPermission<CashExitFlow>())
    )

    private lateinit var notaryNode: NodeHandle
    private lateinit var aliceNode: NodeHandle
    private lateinit var bobNode: NodeHandle
    private lateinit var issuerNodeGBP: NodeHandle
    private lateinit var issuerNodeUSD: NodeHandle

    private val RPCConnections = ArrayList<CordaRPCConnection>()
    private val issuers = HashMap<Currency, CordaRPCOps>()
    private val parties = ArrayList<Pair<Party, CordaRPCOps>>()

    init {
        startDemoNodes()
    }

    private fun onEnd() {
        println("Closing RPC connections")
        RPCConnections.forEach { it.close() }
    }

    private fun startDemoNodes() {
        val portAllocation = PortAllocation.Incremental(20000)
        driver(portAllocation = portAllocation) {
            // TODO : Supported flow should be exposed somehow from the node instead of set of ServiceInfo.
            val notary = startNode(DUMMY_NOTARY.name, advertisedServices = setOf(ServiceInfo(SimpleNotaryService.type)),
                    customOverrides = mapOf("nearestCity" to "Zurich"))
            val alice = startNode(ALICE.name, rpcUsers = arrayListOf(user),
                    advertisedServices = setOf(ServiceInfo(ServiceType.corda.getSubType("cash"))),
                    customOverrides = mapOf("nearestCity" to "Milan"))
            val bob = startNode(BOB.name, rpcUsers = arrayListOf(user),
                    advertisedServices = setOf(ServiceInfo(ServiceType.corda.getSubType("cash"))),
                    customOverrides = mapOf("nearestCity" to "Madrid"))
            val ukBankName = X500Name("CN=UK Bank Plc,O=UK Bank Plc,L=London,C=GB")
            val usaBankName = X500Name("CN=USA Bank Corp,O=USA Bank Corp,L=New York,C=USA")
            val issuerGBP = startNode(ukBankName, rpcUsers = arrayListOf(manager),
                    advertisedServices = setOf(ServiceInfo(ServiceType.corda.getSubType("issuer.GBP"))),
                    customOverrides = mapOf("nearestCity" to "London"))
            val issuerUSD = startNode(usaBankName, rpcUsers = arrayListOf(manager),
                    advertisedServices = setOf(ServiceInfo(ServiceType.corda.getSubType("issuer.USD"))),
                    customOverrides = mapOf("nearestCity" to "New York"))

            notaryNode = notary.get()
            aliceNode = alice.get()
            bobNode = bob.get()
            issuerNodeGBP = issuerGBP.get()
            issuerNodeUSD = issuerUSD.get()

            arrayOf(notaryNode, aliceNode, bobNode, issuerNodeGBP, issuerNodeUSD).forEach {
                println("${it.nodeInfo.legalIdentity} started on ${it.configuration.rpcAddress}")
            }

            when {
                options.has("S") -> startNormalSimulation()
                options.has("F") -> startErrorFlowsSimulation()
            }

            waitForAllNodesToFinish()
        }
    }

    private fun setUpRPC() {
        // Register with alice to use alice's RPC proxy to create random events.
        val aliceClient = aliceNode.rpcClientToNode()
        val aliceConnection = aliceClient.start(user.username, user.password)
        val aliceRPC = aliceConnection.proxy

        val bobClient = bobNode.rpcClientToNode()
        val bobConnection = bobClient.start(user.username, user.password)
        val bobRPC = bobConnection.proxy

        val issuerClientGBP = issuerNodeGBP.rpcClientToNode()
        val issuerGBPConnection = issuerClientGBP.start(manager.username, manager.password)
        val issuerRPCGBP = issuerGBPConnection.proxy

        val issuerClientUSD = issuerNodeUSD.rpcClientToNode()
        val issuerUSDConnection =issuerClientUSD.start(manager.username, manager.password)
        val issuerRPCUSD = issuerUSDConnection.proxy

        RPCConnections.addAll(listOf(aliceConnection, bobConnection, issuerGBPConnection, issuerUSDConnection))
        issuers.putAll(mapOf(USD to issuerRPCUSD, GBP to issuerRPCGBP))

        parties.addAll(listOf(aliceNode.nodeInfo.legalIdentity to aliceRPC,
                bobNode.nodeInfo.legalIdentity to bobRPC,
                issuerNodeGBP.nodeInfo.legalIdentity to issuerRPCGBP,
                issuerNodeUSD.nodeInfo.legalIdentity to issuerRPCUSD))
    }

    private fun startSimulation(eventGenerator: EventGenerator, maxIterations: Int) {
        // Log to logger when flow finish.
        fun FlowHandle<AbstractCashFlow.Result>.log(seq: Int, name: String) {
            val out = "[$seq] $name $id :"
            returnValue.thenMatch({ (stx) ->
                Main.log.info("$out ${stx.id} ${(stx.tx.outputs.first().data as Cash.State).amount}")
            }, {
                Main.log.info("$out ${it.message}")
            })
        }

        for (i in 0..maxIterations) {
            Thread.sleep(300)
            // Issuer requests.
            eventGenerator.issuerGenerator.map { request ->
                when (request) {
                    is IssueAndPaymentRequest -> issuers[request.amount.token]?.let {
                        println("${Instant.now()} [$i] ISSUING ${request.amount} with ref ${request.issueRef} to ${request.recipient}")
                        it.startFlow(::CashIssueAndPaymentFlow, request).log(i, "${request.amount.token}Issuer")
                    }
                    is ExitRequest -> issuers[request.amount.token]?.let {
                        println("${Instant.now()} [$i] EXITING ${request.amount} with ref ${request.issueRef}")
                        it.startFlow(::CashExitFlow, request).log(i, "${request.amount.token}Exit")
                    }
                    else -> throw IllegalArgumentException("Unsupported command: $request")
                }
            }.generate(SplittableRandom())
            // Party pay requests.
            eventGenerator.moveCashGenerator.combine(Generator.pickOne(parties)) { request, (party, rpc) ->
                println("${Instant.now()} [$i] SENDING ${request.amount} from $party to ${request.recipient}")
                rpc.startFlow(CashPaymentFlow::Initiate, request).log(i, party.name.toString())
            }.generate(SplittableRandom())
        }
        println("Simulation completed")
    }

    private fun startNormalSimulation() {
        println("Running simulation mode ...")
        setUpRPC()
        val eventGenerator = EventGenerator(
                parties = parties.map { it.first },
                notary = notaryNode.nodeInfo.notaryIdentity,
                currencies = listOf(GBP, USD)
        )
        val maxIterations = 100_000
        val anonymous = true
        // Pre allocate some money to each party.
        eventGenerator.parties.forEach {
            for (ref in 0..1) {
                for ((currency, issuer) in issuers) {
                    val amount = Amount(1_000_000, currency)
                    issuer.startFlow(::CashIssueAndPaymentFlow, amount, OpaqueBytes(ByteArray(1, { ref.toByte() })), it, anonymous, notaryNode.nodeInfo.notaryIdentity).returnValue.getOrThrow()
                }
            }
        }
        startSimulation(eventGenerator, maxIterations)
        onEnd()
    }

    private fun startErrorFlowsSimulation() {
        println("Running flows with errors simulation mode ...")
        setUpRPC()
        val eventGenerator = ErrorFlowsEventGenerator(
                parties = parties.map { it.first },
                notary = notaryNode.nodeInfo.notaryIdentity,
                currencies = listOf(GBP, USD)
        )
        val maxIterations = 10_000
        startSimulation(eventGenerator, maxIterations)
        onEnd()
    }
}
