package dev.synclab

/**
 * Thrown to the code under test when a simulated network operation fails.
 * Catch this (or `Exception`) in retry logic exactly as you would catch an I/O error.
 */
public sealed class SimulatedNetworkException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    /** No reply arrived within the request timeout. The request may or may not have been processed. */
    public class Timeout(message: String) : SimulatedNetworkException(message)

    /** The destination could not be reached (disconnected, partitioned or crashed). Nothing was sent. */
    public class Unreachable(message: String) : SimulatedNetworkException(message)

    /** The request was rejected before reaching the receiver, like an HTTP 503. Nothing was processed. */
    public class RequestFailed(message: String) : SimulatedNetworkException(message)

    /** The receiver's handler threw. The request *was* processed up to the point of failure. */
    public class Remote(message: String, cause: Throwable?) : SimulatedNetworkException(message, cause)
}

/** Thrown by [SimNode.app] and [SimNode.perform] when the node is crashed. */
public class NodeDownException(public val node: String, message: String) : IllegalStateException(message)

/** Thrown when a simulation finds a problem. Extends [AssertionError] so every test framework reports it as a failure. */
public class SimulationFailure(public val report: FailureReport) : AssertionError(report.render())
