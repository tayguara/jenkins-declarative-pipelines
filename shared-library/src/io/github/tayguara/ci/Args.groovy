package io.github.tayguara.ci

import com.cloudbees.groovy.cps.NonCPS

/**
 * Argument validation shared by the library steps. Pure functions, no Jenkins steps.
 * Failures are IllegalArgumentException with a message that says what to fix.
 */
class Args {

    private static final String SAFE_PATH = '^[A-Za-z0-9._/-]+$'
    private static final String SAFE_ABSOLUTE_PATH = '^/[A-Za-z0-9._/-]+$'
    private static final String ENV_NAME = '^[a-z][a-z0-9-]{0,30}$'
    private static final String DECIMAL = '^-?\\d+(\\.\\d+)?$'

    @NonCPS
    static Object required(Map args, String key) {
        Object value = args == null ? null : args[key]
        if (value == null || value.toString().trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required argument '${key}'".toString())
        }
        return value
    }

    /** A relative path made only of letters, digits, dot, dash, underscore and slash, without '..'. */
    @NonCPS
    static String safePath(String path) {
        boolean ok = path != null && path.matches(SAFE_PATH) && !path.startsWith('/') &&
                !path.split('/').toList().contains('..')
        if (!ok) {
            throw new IllegalArgumentException(("Unsafe path '${path}': use a relative path with " +
                    "letters, digits, '.', '-', '_' and '/' only").toString())
        }
        return path
    }

    /** An absolute path with the same restricted alphabet, for directories that end up inside shell commands. */
    @NonCPS
    static String safeAbsolutePath(String path) {
        if (path == null || !path.matches(SAFE_ABSOLUTE_PATH) || path.split('/').toList().contains('..')) {
            throw new IllegalArgumentException(("Unsafe absolute path '${path}': use an absolute path with " +
                    "letters, digits, '.', '-', '_' and '/' only").toString())
        }
        return path
    }

    /** Environment names become file names, so they must not be able to walk the file system. */
    @NonCPS
    static String envName(String name) {
        if (name == null || !name.matches(ENV_NAME)) {
            throw new IllegalArgumentException(("Invalid environment name '${name}': " +
                    "use lowercase letters, digits and '-', starting with a letter").toString())
        }
        return name
    }

    @NonCPS
    static BigDecimal percent(Object value) {
        BigDecimal number = numberOrNull(value)
        if (number == null || number < BigDecimal.ZERO || number > new BigDecimal('100')) {
            throw new IllegalArgumentException("Expected a number between 0 and 100 but got '${value}'".toString())
        }
        return number
    }

    @NonCPS
    static BigDecimal positiveNumber(Object value) {
        BigDecimal number = numberOrNull(value)
        if (number == null || number <= BigDecimal.ZERO) {
            throw new IllegalArgumentException("Expected a positive number but got '${value}'".toString())
        }
        return number
    }

    /** Plain decimal text (no exponent, no spaces inside) as a BigDecimal, or null when it is anything else. */
    @NonCPS
    static BigDecimal numberOrNull(Object value) {
        String text = value == null ? '' : value.toString().trim()
        return text.matches(DECIMAL) ? new BigDecimal(text) : null
    }
}
