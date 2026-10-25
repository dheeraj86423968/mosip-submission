package io.mosip.vivid.device.mock.render;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.QuadCurve2D;
import java.awt.image.BufferedImage;

/**
 * Draws a simple cartoon face with java.awt: a head ellipse, eyes that blink, eyebrows that raise, a mouth
 * that smiles or opens, and feature displacement that imitates yaw and pitch. It is deliberately simple;
 * the goal is deterministic, dependency-free frames whose geometry changes the way a face detector expects
 * when a subject performs an active challenge.
 *
 * <p>Instances are immutable and thread-safe. Rendering happens in headless mode.
 */
public final class SyntheticFaceRenderer {
    private static final Color BACKGROUND = new Color(0x3a, 0x44, 0x52);
    private static final Color SKIN = new Color(0xe8, 0xbe, 0x9a);
    private static final Color SKIN_SHADOW = new Color(0xc9, 0x9b, 0x78);
    private static final Color HAIR = new Color(0x3b, 0x2a, 0x1d);
    private static final Color EYE_WHITE = new Color(0xfa, 0xfa, 0xfa);
    private static final Color IRIS = new Color(0x4a, 0x6b, 0x8a);
    private static final Color PUPIL = new Color(0x10, 0x10, 0x10);
    private static final Color LIP = new Color(0xa8, 0x4a, 0x4a);
    private static final Color MOUTH_INNER = new Color(0x3a, 0x12, 0x12);
    private static final Color SHIRT = new Color(0x6a, 0x8a, 0xa8);

    // Depth of each feature in front of the head's vertical rotation axis, in head heights. The nose tip protrudes
    // about 0.16 head heights (roughly 0.43 eye-to-mouth distances, as on a real face) in front of the eyes and the
    // mouth corners, which lie at about the same depth.
    private static final double EYE_DEPTH = 0.20, MOUTH_DEPTH = 0.20, NOSE_TIP_DEPTH = 0.36, NOSE_BRIDGE_DEPTH = 0.26, EAR_DEPTH = 0.0, HAIRLINE_DEPTH = 0.15;

    private final int width;
    private final int height;

    public SyntheticFaceRenderer(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("size must be positive");
        this.width = width; this.height = height;
    }

    public int width() { return width; }
    public int height() { return height; }

    /** A new background-only frame (no face). */
    public BufferedImage newCanvas() {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, width, height);
            // a faint vignette so frames are not perfectly flat
            g.setColor(new Color(0, 0, 0, 40));
            g.fillRect(0, (int) (height * 0.85), width, height);
        } finally { g.dispose(); }
        return img;
    }

    /** Render a single centred face in the given state. */
    public BufferedImage render(FaceState state) {
        BufferedImage img = newCanvas();
        if (state.yawDeg() < 0) {
            // The face geometry (nose curve in particular) is drawn for positive yaw. A turn to the subject's
            // right is rendered as the exact mirror image of the equivalent left turn, so both directions are
            // equally well formed and a face detector sees them symmetrically.
            FaceState mirrored = new FaceState(-state.yawDeg(), state.pitchDeg(), state.eyeOpen(), state.mouthOpen(), state.smile(), state.browRaise());
            BufferedImage left = newCanvas();
            drawFace(left, mirrored, 0.5, 0.5, 1.0);
            java.awt.Graphics2D g = img.createGraphics();
            try { g.drawImage(left, width, 0, -width, height, null); } finally { g.dispose(); }
            return img;
        }
        drawFace(img, state, 0.5, 0.5, 1.0);
        return img;
    }

    /**
     * Where {@link #render(FaceState)} draws the five points a landmark detector reports, in pixels and in YuNet
     * order: subject-right eye centre (image left), subject-left eye centre, nose tip, subject-right mouth corner,
     * subject-left mouth corner. This is the ground truth for tests that need to separate the pose estimator from
     * the landmark detector's accuracy on drawn faces.
     */
    public double[] landmarks(FaceState state) {
        if (state.yawDeg() < 0) {
            FaceState mirrored = new FaceState(-state.yawDeg(), state.pitchDeg(), state.eyeOpen(), state.mouthOpen(), state.smile(), state.browRaise());
            double[] m = landmarks(mirrored);
            // mirror x and swap the left/right pairs
            return new double[] {width - m[2], m[3], width - m[0], m[1], width - m[4], m[5], width - m[8], m[9], width - m[6], m[7]};
        }
        double headH = height * 0.60, headW = headH * 0.78;
        double yawR = Math.toRadians(state.yawDeg()), pitchR = Math.toRadians(state.pitchDeg());
        double x0 = 0.5 * width, y0 = 0.5 * height;
        double dx = Math.sin(yawR) * headW * 0.30;
        double eyeY = y0 - headH * 0.10 + headH * pitchShift(-0.10, EYE_DEPTH, pitchR);
        double eyeDX = headW * 0.22 * (0.85 + 0.15 * Math.cos(yawR));
        double noseX = x0 + dx * 1.3, noseY = y0 + headH * 0.08 + headH * pitchShift(0.08, NOSE_TIP_DEPTH, pitchR);
        double mouthY = y0 + headH * 0.27 + headH * pitchShift(0.27, MOUTH_DEPTH, pitchR);
        double mouthW = headW * (0.34 + 0.12 * state.smile());
        double cornerY = state.mouthOpen() > 0.05 ? mouthY : mouthY - headH * (0.02 + 0.12 * state.smile()) * 0.4;
        double mouthX = x0 + dx;
        return new double[] {x0 + dx - eyeDX, eyeY, x0 + dx + eyeDX, eyeY, noseX, noseY,
                mouthX - mouthW / 2, cornerY, mouthX + mouthW / 2, cornerY};
    }

    /**
     * Draw a face onto an existing image.
     *
     * @param cx     horizontal centre as a fraction of the width
     * @param cy     vertical centre as a fraction of the height
     * @param scale  1.0 = head height about 60% of the frame height
     */
    public void drawFace(BufferedImage img, FaceState s, double cx, double cy, double scale) {
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double headH = height * 0.60 * scale;
            double headW = headH * 0.78;
            double yawR = Math.toRadians(s.yawDeg());
            double pitchR = Math.toRadians(s.pitchDeg());
            // head narrows as it turns, features slide toward the turn direction
            double headWVis = headW * (0.80 + 0.20 * Math.cos(yawR));
            double x0 = cx * width, y0 = cy * height;
            double dx = Math.sin(yawR) * headW * 0.30;      // positive yaw -> features move right in image
            // Pitch is a rotation about the horizontal axis through the head centre (see pitchShift): features that
            // protrude further (the nose tip) move more than the eyes and mouth, and the eye-to-mouth span foreshortens.

            // shoulders
            g.setColor(SHIRT);
            g.fill(new Ellipse2D.Double(x0 - headW * 1.3, y0 + headH * 0.45, headW * 2.6, headH * 0.9));
            // neck
            g.setColor(SKIN_SHADOW);
            g.fillRect((int) (x0 - headW * 0.18), (int) (y0 + headH * 0.35), (int) (headW * 0.36), (int) (headH * 0.25));
            // head
            g.setColor(SKIN);
            g.fill(new Ellipse2D.Double(x0 - headWVis / 2, y0 - headH / 2, headWVis, headH));
            // hair cap: its lower edge is the hairline, which lies on the forehead and follows the pitch (more crown
            // shows when the head tilts down); the top of the cap is the skull outline and stays put
            double hairlineDy = headH * pitchShift(-0.225, HAIRLINE_DEPTH, pitchR);
            g.setColor(HAIR);
            g.fill(new Arc2D.Double(x0 - headWVis / 2, y0 - headH / 2, headWVis, Math.max(headH * 0.1, headH * 0.55 + 2 * hairlineDy), 0, 180, Arc2D.CHORD));
            double earDy = headH * pitchShift(0.04, EAR_DEPTH, pitchR);
            // ears, only the one away from the turn is visible prominently
            g.setColor(SKIN_SHADOW);
            g.fill(new Ellipse2D.Double(x0 - headWVis / 2 - headW * 0.06, y0 - headH * 0.05 + earDy, headW * 0.12, headH * 0.18));
            g.fill(new Ellipse2D.Double(x0 + headWVis / 2 - headW * 0.06, y0 - headH * 0.05 + earDy, headW * 0.12, headH * 0.18));

            // eyes
            double eyeY = y0 - headH * 0.10 + headH * pitchShift(-0.10, EYE_DEPTH, pitchR);
            double eyeDX = headW * 0.22 * (0.85 + 0.15 * Math.cos(yawR));
            double eyeW = headW * 0.18, eyeHFull = headH * 0.09;
            double eyeH = Math.max(1.5, eyeHFull * s.eyeOpen());
            for (int side = -1; side <= 1; side += 2) {
                double ex = x0 + dx + side * eyeDX;
                // brow
                double browLift = headH * 0.05 * s.browRaise();
                g.setColor(HAIR);
                g.setStroke(new BasicStroke((float) Math.max(2, headH * 0.015), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new QuadCurve2D.Double(ex - eyeW * 0.6, eyeY - eyeHFull * 1.3 - browLift,
                        ex, eyeY - eyeHFull * 1.9 - browLift, ex + eyeW * 0.6, eyeY - eyeHFull * 1.3 - browLift));
                // eye
                g.setColor(EYE_WHITE);
                g.fill(new Ellipse2D.Double(ex - eyeW / 2, eyeY - eyeH / 2, eyeW, eyeH));
                if (s.eyeOpen() > 0.15) {
                    double irisD = Math.min(eyeW * 0.5, eyeH * 0.9);
                    double irisX = ex + Math.sin(yawR) * eyeW * 0.15;
                    g.setColor(IRIS);
                    g.fill(new Ellipse2D.Double(irisX - irisD / 2, eyeY - irisD / 2, irisD, irisD));
                    g.setColor(PUPIL);
                    g.fill(new Ellipse2D.Double(irisX - irisD / 4, eyeY - irisD / 4, irisD / 2, irisD / 2));
                }
                g.setColor(SKIN_SHADOW);
                g.setStroke(new BasicStroke(1.5f));
                g.draw(new Ellipse2D.Double(ex - eyeW / 2, eyeY - eyeH / 2, eyeW, eyeH));
            }

            // nose
            g.setColor(SKIN_SHADOW);
            g.setStroke(new BasicStroke((float) Math.max(2, headH * 0.012), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double noseX = x0 + dx * 1.3, noseY = y0 + headH * 0.08 + headH * pitchShift(0.08, NOSE_TIP_DEPTH, pitchR);
            double noseTopY = y0 - headH * 0.04 + headH * pitchShift(-0.04, NOSE_BRIDGE_DEPTH, pitchR);
            g.draw(new QuadCurve2D.Double(noseX, noseTopY, noseX - headW * 0.06 * (1 + Math.sin(yawR)), noseY, noseX + headW * 0.05, noseY));

            // mouth
            double mouthY = y0 + headH * 0.27 + headH * pitchShift(0.27, MOUTH_DEPTH, pitchR);
            double mouthW = headW * (0.34 + 0.12 * s.smile());
            double mouthX = x0 + dx;
            if (s.mouthOpen() > 0.05) {
                double openH = headH * 0.16 * s.mouthOpen();
                g.setColor(MOUTH_INNER);
                g.fill(new Ellipse2D.Double(mouthX - mouthW / 2, mouthY - openH / 2, mouthW, openH));
                g.setColor(LIP);
                g.setStroke(new BasicStroke((float) Math.max(2, headH * 0.02)));
                g.draw(new Ellipse2D.Double(mouthX - mouthW / 2, mouthY - openH / 2, mouthW, openH));
            } else {
                double curve = headH * (0.02 + 0.12 * s.smile());  // smile = corners up, centre down
                g.setColor(LIP);
                g.setStroke(new BasicStroke((float) Math.max(2.5, headH * 0.025), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new QuadCurve2D.Double(mouthX - mouthW / 2, mouthY - curve * 0.4, mouthX, mouthY + curve, mouthX + mouthW / 2, mouthY - curve * 0.4));
            }
        } finally { g.dispose(); }
    }

    /**
     * Vertical image displacement, in head heights, of a feature at neutral height {@code y} (head heights below the
     * head centre) and depth {@code depth} (head heights in front of the rotation axis) when the head is pitched by
     * {@code pitchR} radians (positive = looking up), under orthographic projection.
     */
    static double pitchShift(double y, double depth, double pitchR) {
        return y * Math.cos(pitchR) - depth * Math.sin(pitchR) - y;
    }
}
