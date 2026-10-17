package com.zhuoying.ariaedgevlm

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * A phone-native version of the exported question-only WHICH router.
 * Runs in the Android app process: no Termux, Mac inference, scikit-learn,
 * network, or additional Gradle dependencies.
 *
 * Model export: the existing Galaxy router_models.json. Fallback is deliberately
 * enabled until the long-term memory backend is wired to the Android dispatcher.
 */
internal object NativeWhichRouter {
    private const val ASSET_NAME = "router_models.json"
    private var bundle: RouterBundle? = null

    /** Call on Dispatchers.Default/IO, not the UI thread (first load parses model weights). */
    fun route(context: Context, rawQuestion: String): JSONObject {
        val question = rawQuestion.trim()
        require(question.isNotEmpty()) { "Question cannot be empty" }
        val models = synchronized(this) {
            bundle ?: RouterBundle(
                JSONObject(context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() })
            ).also { bundle = it }
        }

        val rule = when {
            KNOWLEDGE.matcher(question).find() -> "KNOWLEDGE"
            SOUND.matcher(question).find() -> "DIRECT_VISUAL"
            CONVERSATION.matcher(question).find() -> "MEMORY"
            else -> null
        }
        val probabilities = models.high.predict(question)
        val best = probabilities.maxByOrNull { it.value }?.key
            ?: error("Question classifier produced no output")
        val high = rule ?: if (best == "MEMORY" && !LONG_TERM.matcher(question).find()) {
            "TEMPORAL"
        } else best

        val output = JSONObject()
            .put("question", question)
            .put("high_level", high)
            .put("confidence", if (rule != null) 1.0 else probabilities.getValue(best))
            .put("reason", if (rule != null) "keyword_rule" else "classifier")
            .put("high_probabilities", JSONObject().apply {
                for ((name, probability) in probabilities) put(name, probability)
            })

        if (high == "DIRECT_VISUAL") {
            val rescueProbability = models.rescue.predict(question).getValue("1")
            output.put("rescue_probability", rescueProbability)
                .put("threshold", models.threshold)
                .put("backend", if (rescueProbability >= models.threshold) "LARGE" else "SMALL")
        }
        return output
    }

    private val KNOWLEDGE = Pattern.compile(
        """\b(weather\w*|forecast|temperature (outside|today|tomorrow)|(the )?news|headlines?|stock price|exchange rate|opening hours|open until|what time is it|what(?:'s| is) (?:the )?(?:date|day) today|today'?s date)\b""",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )
    private val SOUND = Pattern.compile(
        """\b(hear|heard|hearing|sound|sounds|noise|noises|siren|sirens|alarm|alarms|horn|honk\w*|beep\w*)\b""",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )
    private val CONVERSATION = Pattern.compile(
        """\b(you (said|told|mentioned|answered|suggested|recommended)|did you (say|tell|mention|answer|suggest|recommend)|did i (ask|tell|say|mention)|we (talked|discussed|were talking)|our conversation|earlier you|what was your answer)\b""",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )
    private val LONG_TERM = Pattern.compile(
        """\b(yesterday|last (night|week|weekend|month|year|time)|this (morning|afternoon|evening|week|month)|earlier today|today|(hours?|days?|weeks?|months?|years?) ago|ever|usually|always|every (day|time))\b""",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )
}

private class RouterBundle(json: JSONObject) {
    val high = TfidfLogReg(json.getJSONObject("high"))
    val rescue = TfidfLogReg(json.getJSONObject("rescue"))
    val threshold = json.getDouble("threshold")
}

private class TfidfLogReg(json: JSONObject) {
    private val vocabulary: Map<String, Int>
    private val idf: DoubleArray
    private val coefficients: Array<DoubleArray>
    private val intercept: DoubleArray
    private val classes: List<String>
    private val lowercase: Boolean

    init {
        require(json.getString("token_pattern") == "(?u)\\b\\w\\w+\\b") {
            "Unexpected token pattern: router export needs update"
        }
        val vocabObj = json.getJSONObject("vocabulary")
        vocabulary = HashMap<String, Int>(vocabObj.length() * 2).apply {
            val keys = vocabObj.keys()
            while (keys.hasNext()) {
                val term = keys.next()
                put(term, vocabObj.getInt(term))
            }
        }
        fun numbers(key: String): DoubleArray {
            val arr = json.getJSONArray(key)
            return DoubleArray(arr.length()) { arr.getDouble(it) }
        }
        idf = numbers("idf")
        intercept = numbers("intercept")
        classes = json.getJSONArray("classes").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }
        val coefArray = json.getJSONArray("coef")
        coefficients = Array(coefArray.length()) { row ->
            val arr = coefArray.getJSONArray(row)
            DoubleArray(arr.length()) { col -> arr.getDouble(col) }
        }
        lowercase = json.getBoolean("lowercase")
        require(coefficients.size == intercept.size && coefficients.all { it.size == idf.size }) {
            "Classifier weights have invalid dimensions"
        }
        require(classes.size == coefficients.size || (classes.size == 2 && coefficients.size == 1)) {
            "Classifier has invalid number of classes"
        }
    }

    fun predict(question: String): Map<String, Double> {
        val normalized = Normalizer.normalize(question, Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS, "")
        val preprocessed = if (lowercase) normalized.lowercase(Locale.ROOT) else normalized
        val matcher = TOKEN_PATTERN.matcher(preprocessed)
        val tokens = ArrayList<String>()
        while (matcher.find()) tokens.add(matcher.group())
        val counts = HashMap<Int, Int>()
        fun add(term: String) {
            val index = vocabulary[term] ?: return
            counts[index] = (counts[index] ?: 0) + 1
        }
        tokens.forEach(::add)
        for (i in 0 until tokens.size - 1) add("${tokens[i]} ${tokens[i + 1]}")

        val features = HashMap<Int, Double>(counts.size)
        var squaredNorm = 0.0
        for ((index, count) in counts) {
            val value = (1.0 + ln(count.toDouble())) * idf[index]
            features[index] = value
            squaredNorm += value * value
        }
        val norm = sqrt(squaredNorm)
        val logits = DoubleArray(coefficients.size) { row ->
            var result = intercept[row]
            for ((index, value) in features) {
                result += coefficients[row][index] * (if (norm > 0.0) value / norm else value)
            }
            result
        }
        val p = if (logits.size == 1) {
            val positive = if (logits[0] >= 0.0) {
                1.0 / (1.0 + exp(-logits[0]))
            } else {
                val e = exp(logits[0]); e / (1.0 + e)
            }
            doubleArrayOf(1.0 - positive, positive)
        } else {
            val max = logits.maxOrNull() ?: 0.0
            val exponents = logits.map { exp(it - max) }
            val total = exponents.sum()
            exponents.map { it / total }.toDoubleArray()
        }
        return LinkedHashMap<String, Double>().apply {
            classes.forEachIndexed { index, name -> put(name, p[index]) }
        }
    }

    companion object {
        private val TOKEN_PATTERN = Pattern.compile(
            "\\b\\w\\w+\\b", 0
        )
        private val COMBINING_MARKS = Regex("\\p{M}+")
    }
}
