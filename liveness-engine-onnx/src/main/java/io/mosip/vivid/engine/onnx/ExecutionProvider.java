package io.mosip.vivid.engine.onnx;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtProvider;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Opt-in ONNX Runtime execution provider ({@code vivid.engine.executionProvider}). The default is {@link #CPU},
 * which is what the stock {@code com.microsoft.onnxruntime:onnxruntime} jar ships. Every other provider needs a
 * different ORT artifact on the class path (see {@code liveness-engine-onnx/README.md}); when the requested
 * provider is not compiled into the loaded native library, or registering it fails (missing CUDA/cuDNN, no
 * OpenVINO runtime, ...), the session falls back to the CPU provider and a warning is logged. Models never fail
 * to load because an accelerator is missing.
 *
 * <p>{@link #AUTO} picks the first available of CUDA, DirectML, CoreML, OpenVINO and DNNL, else CPU, and logs the
 * choice at INFO (no warning: CPU is an expected outcome for AUTO).
 */
public enum ExecutionProvider {
    CPU(OrtProvider.CPU),
    CUDA(OrtProvider.CUDA),
    DNNL(OrtProvider.DNNL),
    OPENVINO(OrtProvider.OPEN_VINO),
    DIRECTML(OrtProvider.DIRECT_ML),
    COREML(OrtProvider.CORE_ML),
    AUTO(null);

    private static final Logger LOG = LoggerFactory.getLogger(ExecutionProvider.class);
    /** Preference order for {@link #AUTO}. */
    static final List<ExecutionProvider> AUTO_ORDER = List.of(CUDA, DIRECTML, COREML, OPENVINO, DNNL);

    private final OrtProvider ort;

    ExecutionProvider(OrtProvider ort) { this.ort = ort; }

    /** The ONNX Runtime provider this maps to; {@code null} for {@link #AUTO}. */
    public OrtProvider ortProvider() { return ort; }

    /** Parses {@code cpu|cuda|dnnl|openvino|directml|coreml|auto} (case-insensitive; {@code -}/{@code _} ignored). */
    public static ExecutionProvider parse(String s) {
        if (s == null || s.isBlank()) return CPU;
        String k = s.trim().toUpperCase(Locale.ROOT).replace("-", "").replace("_", "");
        switch (k) {
            case "CPU": return CPU;
            case "CUDA": case "GPU": return CUDA;
            case "DNNL": case "ONEDNN": return DNNL;
            case "OPENVINO": return OPENVINO;
            case "DIRECTML": case "DML": return DIRECTML;
            case "COREML": return COREML;
            case "AUTO": return AUTO;
            default: throw new IllegalArgumentException("unknown execution provider '" + s
                    + "' (expected cpu, cuda, dnnl, openvino, directml, coreml or auto)");
        }
    }

    /** Outcome of applying a provider to session options. */
    public static final class Resolution {
        private final ExecutionProvider requested;
        private final ExecutionProvider used;
        private final String fallbackReason;

        Resolution(ExecutionProvider requested, ExecutionProvider used, String fallbackReason) {
            this.requested = requested; this.used = used; this.fallbackReason = fallbackReason;
        }
        public ExecutionProvider requested() { return requested; }
        /** The provider actually registered; {@link #CPU} after a fallback. */
        public ExecutionProvider used() { return used; }
        /** Why the requested provider was not used; null when it was (or when AUTO found nothing). */
        public String fallbackReason() { return fallbackReason; }
        public boolean fellBack() { return fallbackReason != null; }
        @Override public String toString() {
            return requested == used ? used.name() : requested + "->" + used + (fallbackReason == null ? "" : " (" + fallbackReason + ")");
        }
    }

    /** Providers compiled into the loaded native library; {CPU} if the query itself fails. */
    public static Set<OrtProvider> available() {
        try { return OrtSessionSupport.available(); }
        catch (RuntimeException | LinkageError e) { return EnumSet.of(OrtProvider.CPU); }
    }

    /**
     * Registers {@code requested} on {@code opts} if {@code available} contains it, else leaves the options on the
     * default CPU provider. Never throws for an unavailable provider.
     *
     * @param available providers compiled into the native library (normally {@link #available()}); a parameter so
     *                  the fallback can be tested with the CPU-only jar
     */
    public static Resolution apply(OrtSession.SessionOptions opts, ExecutionProvider requested, Set<OrtProvider> available) {
        Objects.requireNonNull(opts); Objects.requireNonNull(available);
        ExecutionProvider req = requested == null ? CPU : requested;
        if (req == CPU) return new Resolution(CPU, CPU, null);
        if (req == AUTO) {
            for (ExecutionProvider p : AUTO_ORDER) {
                if (!available.contains(p.ort)) continue;
                try {
                    p.register(opts);
                    LOG.info("ONNX Runtime execution provider AUTO selected {}", p);
                    return new Resolution(AUTO, p, null);
                } catch (OrtException | RuntimeException e) {
                    LOG.info("ONNX Runtime execution provider AUTO: {} is compiled in but failed to register ({}); trying the next", p, e.getMessage());
                }
            }
            LOG.info("ONNX Runtime execution provider AUTO selected CPU (available: {})", available);
            return new Resolution(AUTO, CPU, null);
        }
        String reason;
        if (!available.contains(req.ort)) {
            reason = req + " is not in this ONNX Runtime build (available: " + available + "); use the matching onnxruntime artifact";
        } else {
            try {
                req.register(opts);
                return new Resolution(req, req, null);
            } catch (OrtException | RuntimeException e) {
                reason = req + " failed to register: " + e.getMessage();
            }
        }
        LOG.warn("ONNX Runtime execution provider {} unavailable, falling back to CPU: {}", req, reason);
        return new Resolution(req, CPU, reason);
    }

    private void register(OrtSession.SessionOptions opts) throws OrtException {
        switch (this) {
            case CUDA: opts.addCUDA(0); break;
            case DNNL: opts.addDnnl(true); break;
            case OPENVINO: opts.addOpenVINO(""); break;      // "" = OpenVINO's default device (CPU_FP32 / AUTO)
            case DIRECTML: opts.addDirectML(0); break;
            case COREML: opts.addCoreML(); break;
            default: break;
        }
    }

    /** Isolated so a missing native library surfaces as a catchable error in {@link #available()}. */
    private static final class OrtSessionSupport {
        static Set<OrtProvider> available() { return ai.onnxruntime.OrtEnvironment.getAvailableProviders(); }
    }
}
