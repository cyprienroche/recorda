package com.rec.flows

import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.IssuedTokenType
import com.r3.corda.lib.tokens.contracts.utilities.amount
import com.rec.states.EnergySource
import com.rec.states.RECToken
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.CordaX500Name
import net.corda.testing.node.*
import java.io.File
import java.util.*
import kotlin.test.assertEquals

object FlowTestHelpers {
    private fun propertiesFromConf(pathname: String): Map<String, String> {
        val tokenProperties = Properties()
        File(pathname).inputStream().let { tokenProperties.load(it) }
        return tokenProperties.entries
          .associateBy(
            { it.key as String },
            {
                (it.value as String)
                  .removeSurrounding("\"")
                  .removeSurrounding("\'")
            })
    }

    private val tokensConfig = propertiesFromConf("res/tokens-workflows.conf")

    val prepareMockNetworkParameters: MockNetworkParameters = MockNetworkParameters()
      .withNotarySpecs(listOf(
        MockNetworkNotarySpec(CordaX500Name.parse("O=Unwanted Notary,L=London,C=GB")),
        MockNetworkNotarySpec(CordaX500Name.parse(
          tokensConfig["notary"]
            ?: error("no notary given in tokens-workflows.conf")))
      ))
      .withCordappsForAllNodes(listOf(
        TestCordapp.findCordapp("com.r3.corda.lib.tokens.contracts"),
        TestCordapp.findCordapp("com.r3.corda.lib.tokens.workflows")
          .withConfig(tokensConfig),
        TestCordapp.findCordapp("com.r3.corda.lib.tokens.money"),
        TestCordapp.findCordapp("com.r3.corda.lib.tokens.selection"),
        TestCordapp.findCordapp("com.rec.states"),
        TestCordapp.findCordapp("com.rec.flows"))
      )

    fun createFrom(issuer: StartedMockNode, holder: StartedMockNode, quantity: Long, source: EnergySource) = FungibleToken(
      amount = amount(quantity, IssuedTokenType(issuer.info.legalIdentities.first(), RECToken(source))),
      holder = holder.info.legalIdentities.first()
    )

    fun FungibleToken.toPair() = Pair(this.holder, this.amount.quantity)


    fun assertHasStatesInVault(node: StartedMockNode, tokenStates: List<FungibleToken>) {
        val vaultTokens = node.transaction {
            node.services.vaultService.queryBy(FungibleToken::class.java).states
        }
        assertEquals(tokenStates.size, vaultTokens.size)
        tokenStates.indices.forEach {
            assertEquals(vaultTokens[it].state.data, tokenStates[it])
        }
    }

    data class NodeHolding(val holder: StartedMockNode, val quantity: Long) {
        fun toPair(): Pair<AbstractParty, Long> {
            return Pair(holder.info.legalIdentities[0], quantity)
        }
    }

    fun issueTokens(node: StartedMockNode, network: MockNetwork, nodeHoldings: Collection<NodeHolding>, source: EnergySource): List<StateAndRef<FungibleToken>> {
        val flow = IssueFlows.Initiator(nodeHoldings.map { it.toPair() }, source)
        val future = node.startFlow(flow)
        network.runNetwork()
        val tx = future.get()
        return tx.toLedgerTransaction(node.services).outRefsOfType(FungibleToken::class.java)
    }

}