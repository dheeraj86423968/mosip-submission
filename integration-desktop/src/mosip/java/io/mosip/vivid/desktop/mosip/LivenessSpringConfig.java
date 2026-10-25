package io.mosip.vivid.desktop.mosip;

import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.context.ApplicationContext;
import io.mosip.vivid.api.LivenessEngine;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.SessionContext;
import io.mosip.vivid.desktop.core.CoreLivenessWiring;
import io.mosip.vivid.desktop.DesktopLivenessConfig;
import io.mosip.vivid.desktop.LivenessAuditAdapter;
import io.mosip.vivid.desktop.LivenessGate;
import io.mosip.vivid.desktop.spi.LivenessSessionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.ServiceLoader;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Spring beans for the Registration Client. Picked up once {@code AppConfig}'s component scan includes
 * {@code io.mosip.vivid.desktop.mosip} (patch 0001) or this class is moved into the already-scanned package
 * {@code io.mosip.registration.api.config}.
 * <p>
 * UNVERIFIED against a full registration-client build; written against release-1.2.0.x class names.
 */
@Configuration
public class LivenessSpringConfig {
    private static final Logger LOG = LoggerFactory.getLogger(LivenessSpringConfig.class);

    /** Re-read on every call so a config sync is honoured without restart (ApplicationContext.map() is the live map). */
    @Bean
    public Supplier<DesktopLivenessConfig> livenessConfigSupplier() {
        return () -> DesktopLivenessConfig.fromApplicationMap(ApplicationContext.map());
    }

    /**
     * Engine discovered through {@link ServiceLoader} so the ONNX engine jar (liveness-engine-onnx) can be swapped
     * for another implementation by dropping a different jar into lib/. Model directory from
     * {@code mosip.registration.liveness.model.dir}, relative to the client working directory (next to lib/).
     */
    @Bean(destroyMethod = "close")
    public LivenessEngine livenessEngine(Supplier<DesktopLivenessConfig> cfg) {
        // Fail closed for face, but never prevent the client from starting: an exception here would abort
        // AnnotationConfigApplicationContext and take finger/iris registration, sync and login down with it.
        // An unavailable engine makes every LivenessGate.verify end in ENGINE_ERROR (newAnalyzer() throws).
        try {
            DesktopLivenessConfig c = cfg.get();
            System.setProperty("mosip.vivid.model.dir", Path.of(c.modelDir()).toAbsolutePath().toString());
            for (LivenessEngine e : ServiceLoader.load(LivenessEngine.class)) {
                if (c.engineId().equalsIgnoreCase(e.info().engineId()) || "auto".equalsIgnoreCase(c.engineId())) {
                    LOG.info("liveness engine {} {} model {} {}", new Object[] {e.info().engineId(), e.info().engineVersion(), e.info().modelBundleId(), e.info().modelBundleVersion()});
                    LivenessEngine.SelfTestResult st = e.selfTest();
                    if (!st.passed) return new UnavailableLivenessEngine("self-test failed: " + st.detail);
                    return e;
                }
            }
            return new UnavailableLivenessEngine("no LivenessEngine implementation found for id '" + c.engineId() + "' (is liveness-engine-onnx on the classpath?)");
        } catch (RuntimeException | LinkageError e) {
            // LinkageError: e.g. UnsupportedClassVersionError from an engine jar built for a newer JRE than the client runs on
            LOG.error("liveness engine could not be loaded", e);
            return new UnavailableLivenessEngine(String.valueOf(e));
        }
    }

    /** Placeholder engine used when the real one cannot be loaded; every session open fails with ENGINE_ERROR. */
    static final class UnavailableLivenessEngine implements LivenessEngine {
        private final String reason;
        UnavailableLivenessEngine(String reason) {
            this.reason = reason;
            LOG.error("liveness engine UNAVAILABLE ({}); face capture and face authentication will be refused while liveness is enabled", reason);
        }
        @Override public io.mosip.vivid.api.EngineInfo info() {
            return new io.mosip.vivid.api.EngineInfo("unavailable", "0", null, null, java.util.Set.of(), java.util.Set.of(), false);
        }
        @Override public io.mosip.vivid.api.FrameAnalyzer newAnalyzer() { throw new IllegalStateException("liveness engine unavailable: " + reason); }
    }

    @Bean
    public LivenessSessionProvider livenessSessionProvider(LivenessEngine engine) {
        // liveness-core: the orchestrator is the LivenessSession implementation.
        return CoreLivenessWiring.sessionProvider(engine);
    }

    @Bean
    public BiFunction<float[], float[], Double> identityDistance() {
        return CoreLivenessWiring.identityDistance();
    }

    @Bean
    public LivenessAuditAdapter livenessAuditAdapter(AuditManagerService auditManagerService) {
        return LivenessAuditAdapter.composite(new MosipLivenessAuditAdapter(auditManagerService), LivenessAuditAdapter.logging());
    }

    @Bean(destroyMethod = "shutdown")
    public LivenessGate livenessGate(LivenessSessionProvider sessions, Supplier<DesktopLivenessConfig> cfg, LivenessAuditAdapter audit) {
        // The gate snapshots config at construction; LivenessGateFactory below re-creates it per capture so synced
        // values apply. Kept as a bean for callers that are fine with startup config (login screen).
        return new LivenessGate(sessions, cfg.get(), audit);
    }

    @Bean
    public LivenessGateFactory livenessGateFactory(LivenessSessionProvider sessions, Supplier<DesktopLivenessConfig> cfg, LivenessAuditAdapter audit) {
        return () -> new LivenessGate(sessions, cfg.get(), audit);
    }

    @FunctionalInterface
    public interface LivenessGateFactory { LivenessGate create(); }
}
