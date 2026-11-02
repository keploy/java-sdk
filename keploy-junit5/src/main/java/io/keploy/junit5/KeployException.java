package io.keploy.junit5;

/**
 * An answer of keploy's agent this extension cannot trust, the agent's own
 * refusal, or keploy's judgment of a test. Its message is keploy's text, and
 * it carries no stack trace: the text is the whole report.
 */
public final class KeployException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** The HTTP status of a non-2xx answer (0 when there was none). */
    final int status;
    /** The scope protocol's error code of a refusal ("not_open", ...), or "". */
    final String code;

    KeployException(String message, int status, String code) {
        // Suppression stays on: JUnit adds a later failure of the same test
        // to the first one as a suppressed exception.
        super(message, null, true, false);
        this.status = status;
        this.code = code;
    }

    KeployException(String message) {
        this(message, 0, "");
    }
}
