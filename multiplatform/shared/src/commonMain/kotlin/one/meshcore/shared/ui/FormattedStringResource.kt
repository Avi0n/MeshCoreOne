package one.meshcore.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Reads a string resource and substitutes its printf-style format arguments. Compose Multiplatform's
 * own `stringResource(resource, vararg)` only honors positional `%N$s`/`%N$d` specifiers, so the bare
 * `%d`/`%s` the strings pipeline emits would render literally through it. This reads the unformatted
 * template and applies [formatTemplate], which handles bare and positional `%d`/`%s` plus `%%`.
 */
@Composable
fun formattedStringResource(resource: StringResource, vararg formatArgs: Any): String =
    formatTemplate(stringResource(resource), formatArgs.asList())

/**
 * Same substitution as [formattedStringResource], after [quantity] selects the `.stringsdict` variant.
 * Quantity selection is Compose's; filling `%d`/`%s` stays in [formatTemplate] so bare specifiers still
 * work. [formatArgs] are the printf arguments, not the quantity unless the template also prints it.
 */
@Composable
fun formattedPluralStringResource(resource: PluralStringResource, quantity: Int, vararg formatArgs: Any): String =
    formatTemplate(pluralStringResource(resource, quantity), formatArgs.asList())

private val FORMAT_SPECIFIER = Regex("""%(?:(\d+)\${'$'})?([%ds])""")

/**
 * Substitutes [args] into [template]'s `%d`/`%s` specifiers. Bare specifiers consume arguments left to
 * right; a `%N$` prefix selects the 1-based argument explicitly; `%%` is a literal percent. Both
 * conversions stringify their argument, which matches the pipeline (it emits only `%d`/`%s`). An
 * unmatched or out-of-range specifier is left intact rather than throwing, mirroring the leniency of
 * the iOS `String(format:)` these strings were ported from. Public so non-composable call sites such
 * as CLI banner builders can reuse the same substitution rules.
 */
fun formatTemplate(template: String, args: List<Any?>): String {
    var next = 0
    return FORMAT_SPECIFIER.replace(template) { match ->
        if (match.groupValues[2] == "%") return@replace "%"
        val positional = match.groupValues[1]
        val index = if (positional.isEmpty()) next++ else positional.toInt() - 1
        args.getOrNull(index)?.toString() ?: match.value
    }
}

/**
 * Resolves [resources] during composition. The returned function formats one mapped failure and answers
 * null when the throwable is unmapped or its resource was not resolved.
 */
@Composable
internal fun <T : Any> rememberMappedErrorText(
    resources: Set<StringResource>,
    select: (Throwable) -> T?,
    resource: (T) -> StringResource?,
    args: (T) -> List<Any>,
): (Throwable) -> String? {
    val templates = resources.associateWith { stringResource(it) }
    return remember(templates) { { error -> mappedErrorText(templates, error, select, resource, args) } }
}

/**
 * Same lookup as [rememberMappedErrorText], with [fallback] when the throwable is unmapped or its
 * resource was not resolved.
 */
@Composable
internal fun <T : Any> rememberFallbackErrorText(
    resources: Set<StringResource>,
    select: (Throwable) -> T?,
    resource: (T) -> StringResource?,
    args: (T) -> List<Any>,
    fallback: String,
): (Throwable) -> String {
    val map = rememberMappedErrorText(resources, select, resource, args)
    return remember(map, fallback) { { error -> map(error) ?: fallback } }
}

internal fun <T : Any> mappedErrorText(
    templates: Map<StringResource, String>,
    error: Throwable,
    select: (Throwable) -> T?,
    resource: (T) -> StringResource?,
    args: (T) -> List<Any>,
): String? = select(error)?.let { typed ->
    resource(typed)?.let(templates::get)?.let { formatTemplate(it, args(typed)) }
}
