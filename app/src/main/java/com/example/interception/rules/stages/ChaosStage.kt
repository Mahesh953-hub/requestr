package com.example.interception.rules.stages

import android.util.Log
import com.example.interception.ChaosInjector
import com.example.interception.http.ParsedHttpMessage
import com.example.interception.rules.ChaosConfig
import com.example.interception.rules.RuleMetadataHeaders
import kotlin.random.Random

/**
 * S4 task 26/30: the CHAOS slot. The `failRate` die is rolled on the **request** leg (before
 * upstream is dialed, so an injected failure behaves like an unreachable server); the delay and
 * corrupt-body dice are rolled on the **response** leg. Attribution goes on
 * [RuleMetadataHeaders.CHAOS] exactly as the legacy `RuleEnforcer.applyChaosToRequest/Response`
 * did (G16 parity).
 */
object ChaosStage {
    private const val TAG = "ChaosStage"

    sealed interface Result {
        data object PassThrough : Result
        data object Applied : Result
        data class Delay(val millis: Long) : Result
        data class Fail(val failureResponse: ParsedHttpMessage) : Result
    }

    fun toInjectorConfig(config: ChaosConfig): ChaosInjector.ChaosConfig {
        val lo = config.delayMinMs.coerceIn(0L, ChaosConfig.MAX_DELAY_MS)
        val hi = config.delayMaxMs.coerceIn(lo, ChaosConfig.MAX_DELAY_MS)
        return ChaosInjector.ChaosConfig(
            failRate = config.failRate.coerceIn(0.0, 1.0),
            delayMsRange = lo..hi,
            corruptBodyRate = config.corruptBodyRate.coerceIn(0.0, 1.0),
        )
    }

    fun applyToRequest(config: ChaosConfig, ruleName: String, random: Random = Random.Default): Result {
        return runCatching {
            val failOnly = toInjectorConfig(config).copy(delayMsRange = 0L..0L, corruptBodyRate = 0.0)
            when (ChaosInjector.decide(failOnly, random)) {
                is ChaosInjector.ChaosOutcome.Fail -> Result.Fail(failureResponse(config, ruleName))
                else -> Result.PassThrough
            }
        }.getOrElse {
            Log.w(TAG, "Failed to apply chaos (request leg): ${it.message}")
            Result.PassThrough
        }
    }

    fun applyToResponse(message: ParsedHttpMessage, config: ChaosConfig, ruleName: String, random: Random = Random.Default): Result {
        return runCatching {
            val noFail = toInjectorConfig(config).copy(failRate = 0.0)
            when (val outcome = ChaosInjector.decide(noFail, random)) {
                is ChaosInjector.ChaosOutcome.Delay -> {
                    message.headers = message.headers + (RuleMetadataHeaders.CHAOS to
                        "delay=${outcome.millis}ms; ${RuleMetadataHeaders.ruleAttribution(ruleName)}")
                    Result.Delay(outcome.millis)
                }
                is ChaosInjector.ChaosOutcome.CorruptBody -> {
                    val binaryBytes = if (message.isBodyBinary) message.decodedBodyBytes ?: message.rawBodyBytes else null
                    val bodyEmpty: Boolean
                    if (binaryBytes != null && binaryBytes.isNotEmpty()) {
                        message.replacedBodyBytes = ChaosInjector.corruptBytes(binaryBytes, random)
                        bodyEmpty = false
                    } else {
                        bodyEmpty = message.body.isEmpty()
                        message.body = ChaosInjector.corrupt(message.body, random)
                    }
                    message.bodyReplaced = true
                    val suffix = if (bodyEmpty) "; body-empty=true" else ""
                    message.headers = message.headers + (RuleMetadataHeaders.CHAOS to
                        "corrupt-body; ${RuleMetadataHeaders.ruleAttribution(ruleName)}$suffix")
                    Result.Applied
                }
                is ChaosInjector.ChaosOutcome.Fail, ChaosInjector.ChaosOutcome.PassThrough -> Result.PassThrough
            }
        }.getOrElse {
            Log.w(TAG, "Failed to apply chaos (response leg): ${it.message}")
            Result.PassThrough
        }
    }

    fun failureResponse(config: ChaosConfig, ruleName: String): ParsedHttpMessage {
        val body = config.failBody
        val headers = linkedMapOf(
            "content-type" to "application/json; charset=utf-8",
            "content-length" to body.toByteArray(Charsets.UTF_8).size.toString(),
            RuleMetadataHeaders.CHAOS to "fail; ${RuleMetadataHeaders.ruleAttribution(ruleName)}",
        )
        return ParsedHttpMessage(
            isRequest = false,
            method = null,
            statusCode = config.failStatusCode,
            statusMessage = config.failStatusMessage,
            headers = headers,
            body = body,
            bodyReplaced = true,
        )
    }
}
