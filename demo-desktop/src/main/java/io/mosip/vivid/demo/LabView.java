package io.mosip.vivid.demo;

import io.mosip.vivid.api.SessionState.Phase;
import io.mosip.vivid.api.Workflow;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** The attack-lab window content: a control bar over the {@link LabCanvas}. Also renders offscreen. */
public final class LabView extends JPanel {
    public static final int W = LabCanvas.W, H = LabCanvas.H + 52;

    /** What the controls do. */
    public interface Controller {
        void start(Workflow workflow, Subject subject);
        void retry();
        void stop();
        void switchTo(Subject subject);
    }

    private final LabCanvas canvas = new LabCanvas();
    private final JComboBox<Workflow> workflow = new JComboBox<>(Workflow.values());
    private final JComboBox<Subject> subject = new JComboBox<>(Subject.values());
    private final JButton start = new JButton("Start session");
    private final JButton retry = new JButton("Try again");
    private final JButton stop = new JButton("Stop");
    private final List<JButton> switches = new ArrayList<>();
    private final boolean subjectsEnabled;

    public LabView(Controller c, boolean subjectsEnabled) {
        super(new BorderLayout());
        this.subjectsEnabled = subjectsEnabled;
        setBackground(Theme.BG);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 12));
        bar.setBackground(Color.WHITE);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));
        bar.setPreferredSize(new Dimension(W, 52));
        JLabel title = new JLabel("MOSIP Vivid · Attack Lab");
        title.setFont(new Font(Theme.SANS, Font.BOLD, 13));
        title.setForeground(Theme.ACCENT);
        bar.add(title);
        bar.add(sep());
        subject.setPreferredSize(new Dimension(212, 26));
        workflow.setPreferredSize(new Dimension(104, 26));
        bar.add(label("Workflow"));
        bar.add(workflow);
        bar.add(label("Subject"));
        subject.setEnabled(subjectsEnabled);
        bar.add(subject);
        bar.add(start);
        bar.add(retry);
        bar.add(stop);
        bar.add(sep());
        bar.add(label("Switch to"));
        for (Subject s : Subject.values()) {
            JButton b = new JButton(shortName(s));
            b.setToolTipText("Replace what is in front of the camera with: " + s.label());
            b.setMargin(new java.awt.Insets(2, 4, 2, 4));
            b.setForeground(s.isAttack() ? Theme.RED : Theme.GREEN);
            b.addActionListener(e -> c.switchTo(s));
            b.setEnabled(false);
            switches.add(b);
            bar.add(b);
        }
        for (Component comp : bar.getComponents()) comp.setFont(comp == title ? comp.getFont() : Theme.LABEL);
        workflow.setFont(Theme.LABEL);
        for (JButton b : List.of(start, retry, stop)) b.setMargin(new java.awt.Insets(2, 6, 2, 6));
        start.addActionListener(e -> c.start((Workflow) workflow.getSelectedItem(), (Subject) subject.getSelectedItem()));
        retry.addActionListener(e -> c.retry());
        stop.addActionListener(e -> c.stop());
        retry.setEnabled(false);
        stop.setEnabled(false);
        add(bar, BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
    }

    private static String shortName(Subject s) {
        return switch (s) {
            case LIVE -> "Live";
            case PRINT -> "Print";
            case VIDEO_REPLAY -> "Video";
            case SCREEN_REPLAY -> "Screen";
            case INJECTED -> "Injected";
        };
    }

    private static JLabel label(String t) {
        JLabel l = new JLabel(t);
        l.setForeground(Theme.MUTED);
        return l;
    }

    private static JSeparator sep() {
        JSeparator s = new JSeparator(SwingConstants.VERTICAL);
        s.setPreferredSize(new Dimension(1, 26));
        return s;
    }

    public LabCanvas canvas() { return canvas; }

    /** Shows a snapshot and syncs the controls with it. Call on the EDT when the view is on screen. */
    public void show(Snapshot s, boolean running) {
        canvas.setSnapshot(s);
        workflow.setSelectedItem(s.workflow());
        for (Subject v : Subject.values()) if (v.label().equals(s.sourceLabel())) subject.setSelectedItem(v);
        Phase p = s.state() == null ? Phase.IDLE : s.state().phase();
        boolean live = running && !(s.state() != null && s.state().isTerminal());
        retry.setEnabled(s.state() != null && s.state().canRetry());
        stop.setEnabled(live);
        start.setEnabled(true);
        for (int i = 0; i < switches.size(); i++) {
            Subject v = Subject.values()[i];
            switches.get(i).setEnabled(subjectsEnabled && live && p != Phase.FAILED && !v.label().equals(s.sourceLabel()));
        }
    }

    /** Lays the view out without a window and paints it into an image (works with java.awt.headless=true). */
    public BufferedImage renderOffscreen() {
        setSize(W, H);
        layoutTree(this);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            printAll(g);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static void layoutTree(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) if (child instanceof Container k) layoutTree(k);
    }
}
