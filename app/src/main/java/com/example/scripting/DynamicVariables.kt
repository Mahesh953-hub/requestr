package com.example.scripting

import java.util.UUID
import kotlin.random.Random

/**
 * Master plan §3 LOW-ADD — "richer dynamic/fake variables for ApiTestr (~20 built-in generators
 * vs requestr's current 3 hardcoded ones)", design donor `ReVoman`. Previously
 * `com.example.data.env.EnvironmentRepository.substitute()` only understood three hardcoded
 * `{{TIMESTAMP}}`/`{{RANDOM}}`/`{{UUID}}` tokens with no extension point.
 *
 * Postman-style `{{$name}}` dynamic variables: each generator is a pure, side-effect-free
 * function producing a fresh value on every call (never cached/memoized -- two references to
 * the same `{{$guid}}` inside one template intentionally yield two different values, matching
 * Postman's own behavior and this class's own contract). Shared by three call sites so one
 * generator table backs all of them instead of drifting into three copies:
 *  - [com.example.data.env.EnvironmentRepository.substitute] (ApiTestr's `{{var}}` template pass)
 *  - [com.example.scripting.ScriptBridge] (`pm.variables.replaceIn`, and the implicit templating
 *    every `pm.request.*`/`pm.response.*` setter applies -- see [ApiTestrScriptRunner])
 *  - [com.example.interception.RuleEnforcer]'s live `SCRIPT_REWRITE` rule-rewrite templating
 */
object DynamicVariables {

    private val firstNames = listOf(
        "Alex", "Jordan", "Taylor", "Morgan", "Riley", "Casey", "Jamie", "Avery",
        "Quinn", "Skyler", "Rowan", "Reese", "Emerson", "Hayden", "Elliot", "Sage",
    )
    private val lastNames = listOf(
        "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis",
        "Rodriguez", "Martinez", "Hernandez", "Lopez", "Clark", "Lewis", "Walker", "Young",
    )
    private val domains = listOf("example.com", "mail.test", "demo.dev", "sandbox.io")
    private val words = listOf(
        "lorem", "ipsum", "dolor", "sit", "amet", "consectetur", "adipiscing", "elit",
        "sed", "eiusmod", "tempor", "incididunt", "labore", "magna", "aliqua", "enim",
    )
    private val cities = listOf("Springfield", "Rivertown", "Fairview", "Oakdale", "Clearwater", "Maplewood")
    private val countries = listOf("Wonderland", "Freedonia", "Elbonia", "Genovia", "Kaskiristan")
    private val colors = listOf("red", "blue", "green", "amber", "violet", "teal", "coral", "slate")

    private fun randomDigits(n: Int): String = (1..n).joinToString("") { Random.nextInt(0, 10).toString() }
    private fun randomIsoOffset(deltaDays: Long): String {
        val millis = System.currentTimeMillis() + deltaDays * 86_400_000L
        return java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(millis))
    }

    /** Every supported `$name` (without the leading `$`), mapped to a fresh-value generator. */
    private val generators: Map<String, () -> String> = mapOf(
        "guid" to { UUID.randomUUID().toString() },
        "randomUUID" to { UUID.randomUUID().toString() },
        "timestamp" to { (System.currentTimeMillis() / 1000).toString() },
        "isoTimestamp" to { randomIsoOffset(0) },
        "randomInt" to { Random.nextInt(0, 1000).toString() },
        "randomInt100" to { Random.nextInt(0, 100).toString() },
        "randomBoolean" to { Random.nextBoolean().toString() },
        "randomFirstName" to { firstNames.random() },
        "randomLastName" to { lastNames.random() },
        "randomFullName" to { "${firstNames.random()} ${lastNames.random()}" },
        "randomUserName" to { "${firstNames.random().lowercase()}.${lastNames.random().lowercase()}${Random.nextInt(1, 999)}" },
        "randomEmail" to { "${firstNames.random().lowercase()}.${lastNames.random().lowercase()}${Random.nextInt(1, 999)}@${domains.random()}" },
        "randomPassword" to { (1..12).map { ('a'..'z') + ('A'..'Z') + ('0'..'9') }.map { it.random() }.joinToString("") },
        "randomIP" to { (1..4).joinToString(".") { Random.nextInt(0, 256).toString() } },
        "randomIPV6" to { (1..8).joinToString(":") { Random.nextInt(0, 0x10000).toString(16) } },
        "randomColor" to { colors.random() },
        "randomHexColor" to { "#" + (1..6).joinToString("") { "0123456789abcdef".random().toString() } },
        "randomUrl" to { "https://${words.random()}.${domains.random()}/${words.random()}" },
        "randomWord" to { words.random() },
        "randomWords" to { (1..Random.nextInt(2, 5)).joinToString(" ") { words.random() } },
        "randomSentence" to { (1..Random.nextInt(4, 9)).joinToString(" ") { words.random() }.replaceFirstChar { it.uppercase() } + "." },
        "randomPhoneNumber" to { "+1${randomDigits(10)}" },
        "randomCity" to { cities.random() },
        "randomCountry" to { countries.random() },
        "randomCreditCardNumber" to { (1..4).joinToString(" ") { randomDigits(4) } },
        "randomDatePast" to { randomIsoOffset(-Random.nextLong(1, 3650)) },
        "randomDateFuture" to { randomIsoOffset(Random.nextLong(1, 3650)) },
        "randomFileName" to { "${words.random()}_${Random.nextInt(100, 999)}.txt" },
        "randomAlphaNumeric" to { (1..8).map { ('a'..'z') + ('0'..'9') }.map { it.random() }.joinToString("") },
    )

    /** Every recognized dynamic-variable name (no leading `$`), for docs/tests. */
    val supportedNames: Set<String> get() = generators.keys

    /** Produces a fresh value for `$name` (no leading `$`), or null if [name] is unrecognized. */
    fun generate(name: String): String? = generators[name]?.invoke()

    /**
     * Replaces every `{{$name}}` occurrence in [template] with a freshly generated value.
     * Unrecognized `{{$name}}` tokens are left untouched (never dropped/blanked) so a typo is
     * visibly diagnosable instead of silently vanishing.
     */
    fun substitute(template: String): String {
        if (!template.contains("{{$")) return template
        val pattern = Regex("\\{\\{\\$([A-Za-z0-9_]+)}}")
        return pattern.replace(template) { match ->
            generate(match.groupValues[1]) ?: match.value
        }
    }
}
