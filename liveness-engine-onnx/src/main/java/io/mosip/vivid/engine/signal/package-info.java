/**
 * Anti-spoofing signals. Each {@link io.mosip.vivid.engine.signal.Signal} scores one frame (or a rolling window)
 * independently and emits a calibrated attack probability with a confidence; fusion happens in liveness-core.
 * Signal ids come from {@code io.mosip.vivid.api.Signals}:
 * <ul>
 *   <li>S1 {@link io.mosip.vivid.engine.signal.CnnTextureSignal} - CNN texture classifier (print, screen, mask)</li>
 *   <li>S2 {@link io.mosip.vivid.engine.signal.MoireSignal} - frequency-domain moire detector (screen replay)</li>
 *   <li>S3 {@link io.mosip.vivid.engine.signal.ColorStatsSignal} - specular / colour statistics (print, screen)</li>
 *   <li>S4 {@link io.mosip.vivid.engine.signal.RppgSignal} - remote photoplethysmography (photo, video replay, mask)</li>
 *   <li>S5 {@link io.mosip.vivid.engine.signal.ParallaxSignal} - planar-motion test during head-turn challenges</li>
 *   <li>S9 {@link io.mosip.vivid.engine.signal.QualitySignal} - capture quality gate (not an attack signal)</li>
 *   <li>{@link io.mosip.vivid.engine.signal.InjectionSignal} - virtual camera driver detection (desktop only)</li>
 * </ul>
 */
package io.mosip.vivid.engine.signal;
