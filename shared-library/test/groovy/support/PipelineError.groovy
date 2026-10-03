package support

/** What the real Jenkins `error` step throws, as far as the tests are concerned. */
class PipelineError extends RuntimeException {

    PipelineError(String message) {
        super(message)
    }
}
