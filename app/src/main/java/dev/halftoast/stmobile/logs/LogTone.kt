package dev.halftoast.stmobile.logs

enum class LogTone { Normal, Warn, Error }

private val ERROR_WORDS = Regex("\\b(error|err|fatal)\\b", RegexOption.IGNORE_CASE)
private val WARN_WORDS = Regex("\\b(warn|warning)\\b", RegexOption.IGNORE_CASE)

/** Colour class of a log line by whole-word keyword; errors win over warnings. */
fun logTone(line: String): LogTone = when {
    ERROR_WORDS.containsMatchIn(line) -> LogTone.Error
    WARN_WORDS.containsMatchIn(line) -> LogTone.Warn
    else -> LogTone.Normal
}
