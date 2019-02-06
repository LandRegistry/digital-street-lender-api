package com.hmlr.api.controllers

import com.hmlr.api.common.VaultQueryHelperConsumer
import com.hmlr.api.common.models.*
import com.hmlr.api.rpcClient.NodeRPCConnection
import com.hmlr.flows.ConsentForDischargeFlow
import com.hmlr.model.ChargeRestriction
import com.hmlr.states.LandTitleState
import com.hmlr.states.ProposedChargesAndRestrictionsState
import net.corda.core.node.services.IdentityService
import net.corda.core.utilities.loggerFor
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*


@Suppress("unused")
@RestController
@RequestMapping("/api")
class ApiController(private val rpc: NodeRPCConnection) : VaultQueryHelperConsumer() {

    override val rpcOps = rpc.proxy
    override val myIdentity = rpcOps.nodeInfo().legalIdentities.first()

    companion object {
        private val logger = loggerFor<ApiController>()
    }

    /**
     * Return the node's name
     */
    @GetMapping(value = "/me", produces = arrayOf(MediaType.APPLICATION_JSON_VALUE))
    fun me() = mapOf("me" to myIdentity.toDTOWithName())

    /**
     * Returns all parties registered with the [NetworkMapService]. These names can be used to look up identities
     * using the [IdentityService].
     */
    @GetMapping(value = "/peers", produces = arrayOf(MediaType.APPLICATION_JSON_VALUE))
    fun peers() = mapOf("peers" to rpcOps.networkMapSnapshot()
            .asSequence()
            .filter { nodeInfo -> nodeInfo.legalIdentities.first() != myIdentity }
            .map { it.legalIdentities.first().toDTOWithName() }
            .toList())

    /**
     * Gets the restrictions on a title
     */
    @GetMapping(value = "/titles/{title-number}/restrictions",
            produces = arrayOf(MediaType.APPLICATION_JSON_VALUE))
    fun getRestrictionsOnTitle(@PathVariable("title-number") titleNumber: String,
                               @RequestParam("type", required = false) restrictionType: String?): ResponseEntity<Any?> {
        logger.info("GET /titles/$titleNumber/restrictions")

        vaultQueryHelper {
            //Get State and Instant
            val chargesAndRestrictionsStateAndInstant: StateAndInstant<ProposedChargesAndRestrictionsState>? = getStateBy { it.state.data.titleID == titleNumber }

            val restrictions = chargesAndRestrictionsStateAndInstant.let { it ->
                // Either return restrictions or empty array
                it?.state?.restrictions ?: return ResponseEntity.ok().body(listOf<Unit>())
            }.let { restrictions ->
                //Filter by restriction type if applicable
                if (restrictionType == null) restrictions else {
                    restrictions.filter { restriction ->
                        when (restriction) {
                            is ChargeRestriction -> restrictionType == ChargeRestrictionDTO.RESTRICTION_TYPE
                            else /*is Restriction*/ -> restrictionType == RestrictionDTO.RESTRICTION_TYPE
                        }
                    }
                }
            }

            //Return Restrictions DTO
            return ResponseEntity.ok().body(restrictions.map { it.toDTO() })
        }
    }

    /**
     * Removes all restrictions from the title
     */
    @DeleteMapping(value = "/titles/{title-number}/restrictions",
            produces = arrayOf(MediaType.APPLICATION_JSON_VALUE))
    fun removeRestrictionsFromTitle(@PathVariable("title-number") titleNumber: String): ResponseEntity<Any?> {
        logger.info("DELETE /titles/$titleNumber/restrictions")

        //Get title or 404 if null
        val landTitle: StateAndInstant<LandTitleState> = vaultQueryHelper {
            getStateBy { it.state.data.titleID == titleNumber } ?: return ResponseEntity.notFound().build()
        }

        //Start flow
        return responseEntityFromFlowHandle {
            it.startFlowDynamic(
                    ConsentForDischargeFlow::class.java,
                    landTitle.state.linearId.toString()
            )
        }
    }

    /**
     * Gets the charge on a title
     */
    @GetMapping(value = "/titles/{title-number}/charges",
            produces = arrayOf(MediaType.APPLICATION_JSON_VALUE))
    fun getChargesOnTitle(@PathVariable("title-number") titleNumber: String): ResponseEntity<Any?> {
        logger.info("GET /titles/$titleNumber/charges")

        //Get State
        val chargesAndRestrictions: StateAndInstant<ProposedChargesAndRestrictionsState> = vaultQueryHelper {
            getStateBy { it.state.data.titleID == titleNumber } ?: return ResponseEntity.ok().body(listOf<Unit>())
        }

        //Get list of charges (from charges and restriction's charges)
        val charges = chargesAndRestrictions.state.charges.toSet() +
                chargesAndRestrictions.state.restrictions.asSequence()
                        .filterIsInstance<ChargeRestriction>()
                        .map { it.charge }
                        .toSet()

        //Build the DTOs
        val chargesDTO = charges.map { it.toDTO() }

        //Return the DTOs
        return ResponseEntity.ok().body(chargesDTO)
    }

    /**
     * Returns all titles
     */
    @GetMapping(value = "/titles", produces = arrayOf(MediaType.APPLICATION_JSON_VALUE))
    fun getTitles(): ResponseEntity<Any?> {
        logger.info("GET /titles")

        vaultQueryHelper {
            //Build Title Transfer DTOs
            val titleTransferDTO = buildTitleTransferDTOs()

            //Return Title Transfer DTOs
            return ResponseEntity.ok().body(titleTransferDTO)
        }
    }

    /**
     * Returns a title
     */
    @GetMapping(value = "/titles/{title-number}", produces = arrayOf(MediaType.APPLICATION_JSON_VALUE))
    fun getTitle(@PathVariable("title-number") titleNumber: String): ResponseEntity<Any?> {
        logger.info("GET /titles/$titleNumber")

        vaultQueryHelper {
            //Build Title Transfer DTO
            val titleTransferDTO = buildTitleTransferDTO(titleNumber)

            //Return 404 if null
            titleTransferDTO ?: return ResponseEntity.notFound().build()

            //Return Title Transfer DTO
            return ResponseEntity.ok().body(titleTransferDTO)
        }
    }

}
