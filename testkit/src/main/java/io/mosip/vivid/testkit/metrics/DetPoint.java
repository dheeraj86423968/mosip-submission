package io.mosip.vivid.testkit.metrics;

/**
 * One operating point of the detection error trade-off: at {@code threshold}, the maximum APCER over all
 * species and the BPCER. Points are produced by sweeping the threshold over every distinct score.
 */
public record DetPoint(double threshold, double apcer, double bpcer) {}
