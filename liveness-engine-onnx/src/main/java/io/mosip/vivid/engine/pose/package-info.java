/**
 * Face geometry: head pose, eye/mouth openness and gaze from the five YuNet landmarks
 * ({@link io.mosip.vivid.engine.pose.ObservationExtractor}), plus the small linear-algebra kit the engine needs
 * ({@link io.mosip.vivid.engine.pose.LinearSolver}, {@link io.mosip.vivid.engine.pose.Homography},
 * {@link io.mosip.vivid.engine.pose.SimilarityTransform}).
 *
 * <p>With only five landmarks every expression measure is an approximation; see the module README for the
 * derivations and their limits. The extractor is written so that a 68/106-point model can replace it behind the
 * same {@code FaceObservation} contract.
 */
package io.mosip.vivid.engine.pose;
