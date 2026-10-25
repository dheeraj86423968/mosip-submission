package io.mosip.vivid.engine.onnx;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A loaded ONNX session with a single float tensor input. Sessions are thread-safe in ONNX Runtime, so one
 * {@code OrtModel} is shared by every analyzer. The input is chosen by name preference ({@code input}, {@code data},
 * {@code images}) and otherwise as the first 4-D float input, which copes with exports that list initialisers as
 * graph inputs (the OpenCV Zoo SFace int8 export does).
 */
public final class OrtModel implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(OrtModel.class);
    private static final List<String> PREFERRED_INPUTS = List.of("input", "data", "images", "image", "x");

    private final Path path;
    private final OrtSession session;
    private final String inputName;
    private final long[] inputShape;
    private final List<String> outputNames;
    private final List<long[]> outputShapes;
    private final ExecutionProvider.Resolution provider;

    /** Shared, process-wide runtime environment with warnings silenced (they are informational and very noisy). */
    public static OrtEnvironment environment() {
        return OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR, "mosip-vivid");
    }

    /**
     * @param path    ONNX file
     * @param threads intra-op thread count (2 is the sweet spot on a 4-core registration laptop)
     */
    public static OrtModel load(Path path, int threads) {
        return load(path, threads, ExecutionProvider.CPU);
    }

    /**
     * @param path     ONNX file
     * @param threads  intra-op thread count
     * @param provider execution provider; when it is not in the loaded ONNX Runtime build, fails to register, or
     *                 the session cannot be created with it, the model is loaded on the CPU provider and a warning
     *                 is logged ({@link ExecutionProvider})
     */
    public static OrtModel load(Path path, int threads, ExecutionProvider provider) {
        if (!Files.isRegularFile(path)) throw new EngineException("model file not found: " + path);
        ExecutionProvider.Resolution res;
        try {
            OrtSession.SessionOptions opts = baseOptions(threads);
            res = ExecutionProvider.apply(opts, provider, ExecutionProvider.available());
            OrtSession session;
            try {
                session = environment().createSession(path.toString(), opts);
            } catch (OrtException e) {
                if (res.used() == ExecutionProvider.CPU) throw e;
                LOG.warn("creating {} with execution provider {} failed, falling back to CPU: {}", path.getFileName(), res.used(), e.getMessage());
                res = new ExecutionProvider.Resolution(res.requested(), ExecutionProvider.CPU, "session creation failed: " + e.getMessage());
                session = environment().createSession(path.toString(), baseOptions(threads));
            }
            return new OrtModel(path, session, res);
        } catch (OrtException e) {
            throw new EngineException("cannot load ONNX model " + path + ": " + e.getMessage(), e);
        }
    }

    private static OrtSession.SessionOptions baseOptions(int threads) throws OrtException {
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.max(1, threads));
        opts.setInterOpNumThreads(1);
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        return opts;
    }

    private OrtModel(Path path, OrtSession session, ExecutionProvider.Resolution provider) throws OrtException {
        this.path = path;
        this.provider = provider;
        this.session = session;
        Map<String, NodeInfo> inputs = session.getInputInfo();
        String chosen = null;
        long[] shape = null;
        if (inputs.size() == 1) {
            chosen = inputs.keySet().iterator().next();
        } else {
            for (String p : PREFERRED_INPUTS) if (inputs.containsKey(p)) { chosen = p; break; }
            if (chosen == null) {
                for (Map.Entry<String, NodeInfo> e : inputs.entrySet()) {
                    if (e.getValue().getInfo() instanceof TensorInfo && ((TensorInfo) e.getValue().getInfo()).getShape().length == 4) {
                        chosen = e.getKey(); break;
                    }
                }
            }
        }
        if (chosen == null) throw new EngineException("cannot determine the input tensor of " + path);
        NodeInfo info = inputs.get(chosen);
        if (info.getInfo() instanceof TensorInfo) shape = ((TensorInfo) info.getInfo()).getShape();
        this.inputName = chosen;
        this.inputShape = shape == null ? new long[0] : shape;
        List<String> names = new ArrayList<>();
        List<long[]> shapes = new ArrayList<>();
        for (Map.Entry<String, NodeInfo> e : session.getOutputInfo().entrySet()) {
            names.add(e.getKey());
            shapes.add(e.getValue().getInfo() instanceof TensorInfo ? ((TensorInfo) e.getValue().getInfo()).getShape() : new long[0]);
        }
        this.outputNames = Collections.unmodifiableList(names);
        this.outputShapes = Collections.unmodifiableList(shapes);
        LOG.info("loaded {} input={}{} outputs={} provider={}", path.getFileName(), inputName, java.util.Arrays.toString(inputShape), outputNames, provider);
    }

    public Path path() { return path; }
    /** Which execution provider was requested and which one the session actually runs on. */
    public ExecutionProvider.Resolution executionProvider() { return provider; }
    public String inputName() { return inputName; }
    /** Declared input shape; -1 marks dynamic dimensions. */
    public long[] inputShape() { return inputShape.clone(); }
    public List<String> outputNames() { return outputNames; }
    public long[] outputShape(int i) { return outputShapes.get(i).clone(); }

    /** Runs the model; returns every output flattened, keyed by output name (insertion order preserved). */
    public Map<String, float[]> run(float[] data, long[] shape) {
        try (OnnxTensor t = OnnxTensor.createTensor(environment(), FloatBuffer.wrap(data), shape);
             OrtSession.Result r = session.run(Collections.singletonMap(inputName, t))) {
            Map<String, float[]> out = new LinkedHashMap<>();
            for (int i = 0; i < outputNames.size(); i++) {
                OnnxValue v = r.get(i);
                if (!(v instanceof OnnxTensor)) continue;
                FloatBuffer fb = ((OnnxTensor) v).getFloatBuffer();
                if (fb == null) continue;
                float[] a = new float[fb.remaining()];
                fb.get(a);
                out.put(outputNames.get(i), a);
            }
            return out;
        } catch (OrtException e) {
            throw new EngineException("inference failed for " + path.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** Runs the model and returns the first output flattened. */
    public float[] runFirst(float[] data, long[] shape) {
        Map<String, float[]> out = run(data, shape);
        if (out.isEmpty()) throw new EngineException("model " + path.getFileName() + " produced no float output");
        return out.values().iterator().next();
    }

    @Override public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            LOG.warn("closing {} failed: {}", path, e.getMessage());
        }
    }
}
