package chat.vch;

import chat.vch.Models.*;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.CompletableFuture;

/** Persistent in-app controls, also available while reading a text channel. */
final class VoicePanel extends JPanel implements AutoCloseable {
    private final DesktopVoice voice;
    private final JButton talk = new JButton("Hold to talk");
    private final JLabel state = new JLabel("Loading the local DAC model…");
    private final JFrame owner;
    private final KeyEventDispatcher keyboard;
    private final WindowFocusListener focus;
    private volatile boolean closed;

    VoicePanel(JFrame owner, ClientCore core, SavedServer server, Channel channel, Runnable leave) throws Exception {
        this(owner,core,server,channel,leave,VoiceAudio.system());
    }

    VoicePanel(JFrame owner, ClientCore core, SavedServer server, Channel channel, Runnable leave, VoiceAudio audio) throws Exception {
        super(new BorderLayout(8, 6)); this.owner = owner;
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.MUTED), BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        JLabel title = new JLabel(server.name + " / " + channel.name + " · DAC " + channel.bitRate + " bits/s");
        add(title, BorderLayout.NORTH); add(state, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); talk.setEnabled(false); actions.add(talk);
        actions.add(new JLabel("Hold " + core.state.pttKey + " · switch windows to mute"));
        actions.add(Theme.button("Leave", leave)); add(actions, BorderLayout.SOUTH);
        voice = new DesktopVoice(core, server, channel, DesktopVoice.models(), audio, update -> SwingUtilities.invokeLater(() -> {
            if (closed) return;
            talk.setEnabled(update.ready()); talk.setText(update.transmitting() ? "Speaking · release to mute" : "Hold to talk");
            talk.setBackground(update.transmitting() ? new Color(25, 128, 56) : UIManager.getColor("Button.background"));
            state.setText(update.message() + " · " + update.connected() + "/" + update.participants() + " peers connected");
        }));
        talk.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { if (SwingUtilities.isLeftMouseButton(e) && talk.isEnabled()) voice.press(); }
            public void mouseReleased(MouseEvent e) { voice.release(); }
            public void mouseExited(MouseEvent e) { if ((e.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) != 0) voice.release(); }
        });
        int key = switch (core.state.pttKey) { case "SPACE" -> KeyEvent.VK_SPACE; case "V" -> KeyEvent.VK_V; default -> KeyEvent.VK_F8; };
        keyboard = event -> {
            if (closed || event.getKeyCode() != key) return false;
            if (event.getID() == KeyEvent.KEY_RELEASED) { voice.release(); return false; }
            Component target = event.getComponent();
            if (event.getID() == KeyEvent.KEY_PRESSED && event.getModifiersEx() == 0 && owner.isFocused()
                    && !(target instanceof JTextComponent) && !(target instanceof JComboBox<?>)) {
                voice.press(); return true;
            }
            return false;
        };
        focus = new WindowAdapter() { public void windowLostFocus(WindowEvent e) { voice.release(); } };
        owner.addWindowFocusListener(focus); KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyboard);
    }

    void startAfter(CompletableFuture<Void> previous) { previous.whenComplete((ignored, error) -> { if (!closed) voice.start(); }); }
    CompletableFuture<Void> stopped() { return voice.stopped(); }
    public void close() {
        if (closed) return; closed = true;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyboard);
        owner.removeWindowFocusListener(focus); voice.close();
    }
}
