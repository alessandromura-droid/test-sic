package it.salvaiciclisti.app.json

/**
 * Minimal, dependency-free JSON parser. The routing/geocoding assets are
 * always emitted by our own Python scripts (precompila.py's build steps) as
 * compact, well-formed UTF-8 JSON - no NaN/Infinity, no comments, no trailing
 * commas - so this only needs to handle that well-behaved subset. It is not
 * a general-purpose/validating parser and will throw on malformed input
 * rather than trying to recover from it.
 *
 * Written by hand instead of pulling in org.json (present on-device but not
 * on a plain JVM, which would force a test-only dependency like GTL's) or a
 * third-party library, to keep the app's dependency footprint the same as
 * the rest of this project.
 */
sealed class JsonValue {
    data class JsonObject(val map: Map<String, JsonValue>) : JsonValue()
    data class JsonArray(val items: List<JsonValue>) : JsonValue()
    data class JsonString(val value: String) : JsonValue()
    data class JsonNumber(val value: Double) : JsonValue()
    data class JsonBool(val value: Boolean) : JsonValue()
    object JsonNull : JsonValue()
}

class JsonParseException(message: String) : Exception(message)

object SimpleJson {
    fun parse(text: String): JsonValue {
        val p = Parser(text)
        p.skipWhitespace()
        val v = p.parseValue()
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun parseValue(): JsonValue {
            skipWhitespace()
            if (i >= s.length) throw JsonParseException("unexpected end of input")
            val result = when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.JsonString(parseString())
                't' -> { expect("true"); JsonValue.JsonBool(true) }
                'f' -> { expect("false"); JsonValue.JsonBool(false) }
                'n' -> { expect("null"); JsonValue.JsonNull }
                else -> parseNumber()
            }
            return result
        }

        fun expect(word: String) {
            if (i + word.length > s.length || s.substring(i, i + word.length) != word) {
                throw JsonParseException("expected '$word' at position $i")
            }
            i += word.length
        }

        fun parseObject(): JsonValue.JsonObject {
            i++ // consume '{'
            val map = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (i < s.length && s[i] == '}') { i++; return JsonValue.JsonObject(map) }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                if (s[i] != ':') throw JsonParseException("expected ':' at position $i")
                i++
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                when (s[i]) {
                    ',' -> { i++ }
                    '}' -> { i++; break }
                    else -> throw JsonParseException("expected ',' or '}' at position $i")
                }
            }
            return JsonValue.JsonObject(map)
        }

        fun parseArray(): JsonValue.JsonArray {
            i++ // consume '['
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (i < s.length && s[i] == ']') { i++; return JsonValue.JsonArray(items) }
            while (true) {
                items.add(parseValue())
                skipWhitespace()
                when (s[i]) {
                    ',' -> { i++ }
                    ']' -> { i++; break }
                    else -> throw JsonParseException("expected ',' or ']' at position $i")
                }
            }
            return JsonValue.JsonArray(items)
        }

        fun parseString(): String {
            if (s[i] != '"') throw JsonParseException("expected '\"' at position $i")
            i++
            val sb = StringBuilder()
            while (s[i] != '"') {
                val c = s[i]
                if (c == '\\') {
                    i++
                    when (s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = s.substring(i + 1, i + 5)
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> throw JsonParseException("invalid escape at position $i")
                    }
                    i++
                } else {
                    sb.append(c)
                    i++
                }
            }
            i++ // consume closing quote
            return sb.toString()
        }

        fun parseNumber(): JsonValue.JsonNumber {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '-' || s[i] == '+')) i++
            val numStr = s.substring(start, i)
            return JsonValue.JsonNumber(numStr.toDouble())
        }
    }
}

fun JsonValue.obj(): Map<String, JsonValue> = (this as JsonValue.JsonObject).map
fun JsonValue.arr(): List<JsonValue> = (this as JsonValue.JsonArray).items
fun JsonValue.str(): String = (this as JsonValue.JsonString).value
fun JsonValue.num(): Double = (this as JsonValue.JsonNumber).value
fun JsonValue.strOrNull(): String? = (this as? JsonValue.JsonString)?.value
