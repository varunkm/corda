package net.corda.core.messaging

import net.corda.core.concurrent.CordaFuture
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UpgradedContract
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowInitiator
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StateMachineRunId
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.NetworkMapCache
import net.corda.core.node.services.Vault
import net.corda.core.node.services.VaultQueryException
import net.corda.core.node.services.vault.DEFAULT_PAGE_SIZE
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.Sort
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.Try
import org.bouncycastle.asn1.x500.X500Name
import rx.Observable
import java.io.InputStream
import java.security.PublicKey
import java.time.Instant
import java.util.*

@CordaSerializable
data class StateMachineInfo(
        val id: StateMachineRunId,
        val flowLogicClassName: String,
        val initiator: FlowInitiator,
        val progressTrackerStepAndUpdates: DataFeed<String, String>?
) {
    override fun toString(): String = "${javaClass.simpleName}($id, $flowLogicClassName)"
}

@CordaSerializable
sealed class StateMachineUpdate {
    abstract val id: StateMachineRunId

    data class Added(val stateMachineInfo: StateMachineInfo) : StateMachineUpdate() {
        override val id: StateMachineRunId get() = stateMachineInfo.id
    }

    data class Removed(override val id: StateMachineRunId, val result: Try<*>) : StateMachineUpdate()
}

@CordaSerializable
data class StateMachineTransactionMapping(val stateMachineRunId: StateMachineRunId, val transactionId: SecureHash)

/** RPC operations that the node exposes to clients. */
interface CordaRPCOps : RPCOps {
    /**
     * Returns the RPC protocol version, which is the same the node's Platform Version. Exists since version 1 so guaranteed
     * to be present.
     */
    override val protocolVersion: Int get() = nodeIdentity().platformVersion

    /**
     * Returns a list of currently in-progress state machine infos.
     */
    fun stateMachinesSnapshot(): List<StateMachineInfo>

    /**
     * Returns a data feed of currently in-progress state machine infos and an observable of future state machine adds/removes.
     */
    @RPCReturnsObservables
    fun stateMachinesFeed(): DataFeed<List<StateMachineInfo>, StateMachineUpdate>

    /**
     * Returns a snapshot of vault states for a given query criteria (and optional order and paging specification)
     *
     * Generic vault query function which takes a [QueryCriteria] object to define filters,
     * optional [PageSpecification] and optional [Sort] modification criteria (default unsorted),
     * and returns a [Vault.Page] object containing the following:
     *  1. states as a List of <StateAndRef> (page number and size defined by [PageSpecification])
     *  2. states metadata as a List of [Vault.StateMetadata] held in the Vault States table.
     *  3. total number of results available if [PageSpecification] supplied (otherwise returns -1)
     *  4. status types used in this query: UNCONSUMED, CONSUMED, ALL
     *  5. other results (aggregate functions with/without using value groups)
     *
     * @throws VaultQueryException if the query cannot be executed for any reason
     *        (missing criteria or parsing error, paging errors, unsupported query, underlying database error)
     *
     * Notes
     *   If no [PageSpecification] is provided, a maximum of [DEFAULT_PAGE_SIZE] results will be returned.
     *   API users must specify a [PageSpecification] if they are expecting more than [DEFAULT_PAGE_SIZE] results,
     *   otherwise a [VaultQueryException] will be thrown alerting to this condition.
     *   It is the responsibility of the API user to request further pages and/or specify a more suitable [PageSpecification].
     */
    // DOCSTART VaultQueryByAPI
    @RPCReturnsObservables
    fun <T : ContractState> vaultQueryBy(criteria: QueryCriteria,
                                         paging: PageSpecification,
                                         sorting: Sort,
                                         contractType: Class<out T>): Vault.Page<T>
    // DOCEND VaultQueryByAPI

    // Note: cannot apply @JvmOverloads to interfaces nor interface implementations
    // Java Helpers

    // DOCSTART VaultQueryAPIHelpers
    fun <T : ContractState> vaultQuery(contractType: Class<out T>): Vault.Page<T> {
        return vaultQueryBy(QueryCriteria.VaultQueryCriteria(), PageSpecification(), Sort(emptySet()), contractType)
    }
    fun <T : ContractState> vaultQueryByCriteria(criteria: QueryCriteria, contractType: Class<out T>): Vault.Page<T> {
        return vaultQueryBy(criteria, PageSpecification(), Sort(emptySet()), contractType)
    }
    fun <T : ContractState> vaultQueryByWithPagingSpec(contractType: Class<out T>, criteria: QueryCriteria, paging: PageSpecification): Vault.Page<T> {
        return vaultQueryBy(criteria, paging, Sort(emptySet()), contractType)
    }
    fun <T : ContractState> vaultQueryByWithSorting(contractType: Class<out T>, criteria: QueryCriteria, sorting: Sort): Vault.Page<T> {
        return vaultQueryBy(criteria, PageSpecification(), sorting, contractType)
    }
    // DOCEND VaultQueryAPIHelpers

    /**
     * Returns a snapshot (as per queryBy) and an observable of future updates to the vault for the given query criteria.
     *
     * Generic vault query function which takes a [QueryCriteria] object to define filters,
     * optional [PageSpecification] and optional [Sort] modification criteria (default unsorted),
     * and returns a [Vault.PageAndUpdates] object containing
     * 1) a snapshot as a [Vault.Page] (described previously in [queryBy])
     * 2) an [Observable] of [Vault.Update]
     *
     * Notes: the snapshot part of the query adheres to the same behaviour as the [queryBy] function.
     *        the [QueryCriteria] applies to both snapshot and deltas (streaming updates).
     */
    // DOCSTART VaultTrackByAPI
    @RPCReturnsObservables
    fun <T : ContractState> vaultTrackBy(criteria: QueryCriteria,
                                         paging: PageSpecification,
                                         sorting: Sort,
                                         contractType: Class<out T>): DataFeed<Vault.Page<T>, Vault.Update<T>>
    // DOCEND VaultTrackByAPI

    // Note: cannot apply @JvmOverloads to interfaces nor interface implementations
    // Java Helpers

    // DOCSTART VaultTrackAPIHelpers
    fun <T : ContractState> vaultTrack(contractType: Class<out T>): DataFeed<Vault.Page<T>, Vault.Update<T>> {
        return vaultTrackBy(QueryCriteria.VaultQueryCriteria(), PageSpecification(), Sort(emptySet()), contractType)
    }
    fun <T : ContractState> vaultTrackByCriteria(contractType: Class<out T>, criteria: QueryCriteria): DataFeed<Vault.Page<T>, Vault.Update<T>> {
        return vaultTrackBy(criteria, PageSpecification(), Sort(emptySet()), contractType)
    }
    fun <T : ContractState> vaultTrackByWithPagingSpec(contractType: Class<out T>, criteria: QueryCriteria, paging: PageSpecification): DataFeed<Vault.Page<T>, Vault.Update<T>> {
        return vaultTrackBy(criteria, paging, Sort(emptySet()), contractType)
    }
    fun <T : ContractState> vaultTrackByWithSorting(contractType: Class<out T>, criteria: QueryCriteria, sorting: Sort): DataFeed<Vault.Page<T>, Vault.Update<T>> {
        return vaultTrackBy(criteria, PageSpecification(), sorting, contractType)
    }
    // DOCEND VaultTrackAPIHelpers

    /**
     * Returns a list of all recorded transactions.
     */
    fun verifiedTransactionsSnapshot(): List<SignedTransaction>

    /**
     * Returns a data feed of all recorded transactions and an observable of future recorded ones.
     */
    @RPCReturnsObservables
    fun verifiedTransactionsFeed(): DataFeed<List<SignedTransaction>, SignedTransaction>

    /**
     * Returns a snapshot list of existing state machine id - recorded transaction hash mappings.
     */
    fun stateMachineRecordedTransactionMappingSnapshot(): List<StateMachineTransactionMapping>

    /**
     * Returns a snapshot list of existing state machine id - recorded transaction hash mappings, and a stream of future
     * such mappings as well.
     */
    @RPCReturnsObservables
    fun stateMachineRecordedTransactionMappingFeed(): DataFeed<List<StateMachineTransactionMapping>, StateMachineTransactionMapping>

    /**
     * Returns all parties currently visible on the network with their advertised services.
     */
    fun networkMapSnapshot(): List<NodeInfo>

    /**
     * Returns all parties currently visible on the network with their advertised services and an observable of future updates to the network.
     */
    @RPCReturnsObservables
    fun networkMapFeed(): DataFeed<List<NodeInfo>, NetworkMapCache.MapChange>

    /**
     * Start the given flow with the given arguments. [logicType] must be annotated with [net.corda.core.flows.StartableByRPC].
     */
    @RPCReturnsObservables
    fun <T> startFlowDynamic(logicType: Class<out FlowLogic<T>>, vararg args: Any?): FlowHandle<T>

    /**
     * Start the given flow with the given arguments, returning an [Observable] with a single observation of the
     * result of running the flow. [logicType] must be annotated with [net.corda.core.flows.StartableByRPC].
     */
    @RPCReturnsObservables
    fun <T : Any> startTrackedFlowDynamic(logicType: Class<out FlowLogic<T>>, vararg args: Any?): FlowProgressHandle<T>

    /**
     * Returns Node's identity, assuming this will not change while the node is running.
     */
    fun nodeIdentity(): NodeInfo

    /*
     * Add note(s) to an existing Vault transaction
     */
    fun addVaultTransactionNote(txnId: SecureHash, txnNote: String)

    /*
     * Retrieve existing note(s) for a given Vault transaction
     */
    fun getVaultTransactionNotes(txnId: SecureHash): Iterable<String>

    /**
     * Checks whether an attachment with the given hash is stored on the node.
     */
    fun attachmentExists(id: SecureHash): Boolean

    /**
     * Download an attachment JAR by ID
     */
    fun openAttachment(id: SecureHash): InputStream

    /**
     * Uploads a jar to the node, returns it's hash.
     */
    fun uploadAttachment(jar: InputStream): SecureHash

    /**
     * Returns the node's current time.
     */
    fun currentNodeTime(): Instant

    /**
     * Returns a [CordaFuture] which completes when the node has registered wih the network map service. It can also
     * complete with an exception if it is unable to.
     */
    @RPCReturnsObservables
    fun waitUntilNetworkReady(): CordaFuture<Void?>

    // TODO These need rethinking. Instead of these direct calls we should have a way of replicating a subset of
    // the node's state locally and query that directly.
    /**
     * Returns the well known identity from an abstract party. This is intended to resolve the well known identity
     * from a confidential identity, however it transparently handles returning the well known identity back if
     * a well known identity is passed in.
     *
     * @param party identity to determine well known identity for.
     * @return well known identity, if found.
     */
    fun partyFromAnonymous(party: AbstractParty): Party?
    /**
     * Returns the [Party] corresponding to the given key, if found.
     */
    fun partyFromKey(key: PublicKey): Party?

    /**
     * Returns the [Party] with the X.500 principal as it's [Party.name]
     */
    fun partyFromX500Name(x500Name: X500Name): Party?

    /**
     * Returns a list of candidate matches for a given string, with optional fuzzy(ish) matching. Fuzzy matching may
     * get smarter with time e.g. to correct spelling errors, so you should not hard-code indexes into the results
     * but rather show them via a user interface and let the user pick the one they wanted.
     *
     * @param query The string to check against the X.500 name components
     * @param exactMatch If true, a case sensitive match is done against each component of each X.500 name.
     */
    fun partiesFromName(query: String, exactMatch: Boolean): Set<Party>

    /** Enumerates the class names of the flows that this node knows about. */
    fun registeredFlows(): List<String>

    /**
     * Returns a node's identity from the network map cache, where known.
     *
     * @return the node info if available.
     */
    fun nodeIdentityFromParty(party: AbstractParty): NodeInfo?

    /**
     * Clear all network map data from local node cache.
     */
    fun clearNetworkMapCache()
}

inline fun <reified T : ContractState> CordaRPCOps.vaultQueryBy(criteria: QueryCriteria = QueryCriteria.VaultQueryCriteria(),
                                                                paging: PageSpecification = PageSpecification(),
                                                                sorting: Sort = Sort(emptySet())): Vault.Page<T> {
    return vaultQueryBy(criteria, paging, sorting, T::class.java)
}

inline fun <reified T : ContractState> CordaRPCOps.vaultTrackBy(criteria: QueryCriteria = QueryCriteria.VaultQueryCriteria(),
                                                                paging: PageSpecification = PageSpecification(),
                                                                sorting: Sort = Sort(emptySet())): DataFeed<Vault.Page<T>, Vault.Update<T>> {
    return vaultTrackBy(criteria, paging, sorting, T::class.java)
}

/**
 * These allow type safe invocations of flows from Kotlin, e.g.:
 *
 * val rpc: CordaRPCOps = (..)
 * rpc.startFlow(::ResolveTransactionsFlow, setOf<SecureHash>(), aliceIdentity)
 *
 * Note that the passed in constructor function is only used for unification of other type parameters and reification of
 * the Class instance of the flow. This could be changed to use the constructor function directly.
 */
inline fun <T : Any, reified R : FlowLogic<T>> CordaRPCOps.startFlow(
        @Suppress("UNUSED_PARAMETER")
        flowConstructor: () -> R
): FlowHandle<T> = startFlowDynamic(R::class.java)

inline fun <T , A, reified R : FlowLogic<T>> CordaRPCOps.startFlow(
        @Suppress("UNUSED_PARAMETER")
        flowConstructor: (A) -> R,
        arg0: A
): FlowHandle<T> = startFlowDynamic(R::class.java, arg0)

inline fun <T : Any, A, B, reified R : FlowLogic<T>> CordaRPCOps.startFlow(
        @Suppress("UNUSED_PARAMETER")
        flowConstructor: (A, B) -> R,
        arg0: A,
        arg1: B
): FlowHandle<T> = startFlowDynamic(R::class.java, arg0, arg1)

inline fun <T : Any, A, B, C, reified R : FlowLogic<T>> CordaRPCOps.startFlow(
        @Suppress("UNUSED_PARAMETER")
        flowConstructor: (A, B, C) -> R,
        arg0: A,
        arg1: B,
        arg2: C
): FlowHandle<T> = startFlowDynamic(R::class.java, arg0, arg1, arg2)

inline fun <T : Any, A, B, C, D, reified R : FlowLogic<T>> CordaRPCOps.startFlow(
        @Suppress("UNUSED_PARAMETER")
        flowConstructor: (A, B, C, D) -> R,
        arg0: A,
        arg1: B,
        arg2: C,
        arg3: D
): FlowHandle<T> = startFlowDynamic(R::class.java, arg0, arg1, arg2, arg3)

inline fun <T : Any, A, B, C, D, E, reified R : FlowLogic<T>> CordaRPCOps.startFlow(
        @Suppress("UNUSED_PARAMETER")
        flowConstructor: (A, B, C, D, E) -> R,
        arg0: A,
        arg1: B,
        arg2: C,
        arg3: D,
        arg4: E
): FlowHandle<T> = startFlowDynamic(R::class.java, arg0, arg1, arg2, arg3, arg4)

inline fun <T : Any, A, B, C, D, E, F, reified R : FlowLogic<T>> CordaRPCOps.startFlow(
        @Suppress("UNUSED_PARAMETER")
        flowConstructor: (A, B, C, D, E, F) -> R,
        arg0: A,
        arg1: B,
        arg2: C,
        arg3: D,
        arg4: E,
        arg5: F
): FlowHandle<T> = startFlowDynamic(R::class.java, arg0, arg1, arg2, arg3, arg4, arg5)

/**
 * Same again, except this time with progress-tracking enabled.
 */
@Suppress("unused")
inline fun <T : Any, reified R : FlowLogic<T>> CordaRPCOps.startTrackedFlow(
        @Suppress("unused_parameter")
        flowConstructor: () -> R
): FlowProgressHandle<T> = startTrackedFlowDynamic(R::class.java)

@Suppress("unused")
inline fun <T : Any, A, reified R : FlowLogic<T>> CordaRPCOps.startTrackedFlow(
        @Suppress("unused_parameter")
        flowConstructor: (A) -> R,
        arg0: A
): FlowProgressHandle<T> = startTrackedFlowDynamic(R::class.java, arg0)

@Suppress("unused")
inline fun <T : Any, A, B, reified R : FlowLogic<T>> CordaRPCOps.startTrackedFlow(
        @Suppress("unused_parameter")
        flowConstructor: (A, B) -> R,
        arg0: A,
        arg1: B
): FlowProgressHandle<T> = startTrackedFlowDynamic(R::class.java, arg0, arg1)

@Suppress("unused")
inline fun <T : Any, A, B, C, reified R : FlowLogic<T>> CordaRPCOps.startTrackedFlow(
        @Suppress("unused_parameter")
        flowConstructor: (A, B, C) -> R,
        arg0: A,
        arg1: B,
        arg2: C
): FlowProgressHandle<T> = startTrackedFlowDynamic(R::class.java, arg0, arg1, arg2)

@Suppress("unused")
inline fun <T : Any, A, B, C, D, reified R : FlowLogic<T>> CordaRPCOps.startTrackedFlow(
        @Suppress("unused_parameter")
        flowConstructor: (A, B, C, D) -> R,
        arg0: A,
        arg1: B,
        arg2: C,
        arg3: D
): FlowProgressHandle<T> = startTrackedFlowDynamic(R::class.java, arg0, arg1, arg2, arg3)

@Suppress("unused")
inline fun <T : Any, A, B, C, D, E, reified R : FlowLogic<T>> CordaRPCOps.startTrackedFlow(
        @Suppress("unused_parameter")
        flowConstructor: (A, B, C, D, E) -> R,
        arg0: A,
        arg1: B,
        arg2: C,
        arg3: D,
        arg4: E
): FlowProgressHandle<T> = startTrackedFlowDynamic(R::class.java, arg0, arg1, arg2, arg3, arg4)

@Suppress("unused")
inline fun <T : Any, A, B, C, D, E, F, reified R : FlowLogic<T>> CordaRPCOps.startTrackedFlow(
        @Suppress("unused_parameter")
        flowConstructor: (A, B, C, D, E, F) -> R,
        arg0: A,
        arg1: B,
        arg2: C,
        arg3: D,
        arg4: E,
        arg5: F
): FlowProgressHandle<T> = startTrackedFlowDynamic(R::class.java, arg0, arg1, arg2, arg3, arg4, arg5)

/**
 * The Data feed contains a snapshot of the requested data and an [Observable] of future updates.
 */
@CordaSerializable
data class DataFeed<out A, B>(val snapshot: A, val updates: Observable<B>)
