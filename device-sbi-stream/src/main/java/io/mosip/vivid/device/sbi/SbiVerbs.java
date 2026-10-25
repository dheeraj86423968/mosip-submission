package io.mosip.vivid.device.sbi;

/**
 * The custom HTTP verbs and paths of the MOSIP Device Service / SBI specification 0.9.5 (confirmed against
 * the MDS spec page and MOSIP's reference MockMDS). SBI 1.0 drafts move to plain {@code POST} with the same
 * paths (and {@code /rcapture}); this adapter sends the 0.9.5 verbs, which every MDS/SBI service shipped
 * with Registration Client 1.2.x answers.
 */
public final class SbiVerbs {
    private SbiVerbs() {}

    /** Discovery: {@code MOSIPDISC /device}, body {@code {"type":"Face"}}. */
    public static final String DISCOVER = "MOSIPDISC";
    /** Device info: {@code MOSIPDINFO /info}, no body. */
    public static final String INFO = "MOSIPDINFO";
    /** Live preview: {@code STREAM /stream}, body {@code {"deviceId":"..","deviceSubId":0,"timeout":..}}. Registration devices only. */
    public static final String STREAM = "STREAM";
    /** Registration capture: {@code RCAPTURE /capture}. */
    public static final String RCAPTURE = "RCAPTURE";
    /** Authentication capture: {@code CAPTURE /capture}. */
    public static final String CAPTURE = "CAPTURE";

    public static final String PATH_DEVICE = "/device";
    public static final String PATH_INFO = "/info";
    public static final String PATH_STREAM = "/stream";
    public static final String PATH_CAPTURE = "/capture";

    /** First port an SBI service may bind on 127.0.0.1. */
    public static final int PORT_MIN = 4501;
    /** Last port an SBI service may bind on 127.0.0.1. */
    public static final int PORT_MAX = 4600;
    /** SBI services must bind to this address (not "localhost"). */
    public static final String HOST = "127.0.0.1";
}
