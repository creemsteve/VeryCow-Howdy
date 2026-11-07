package chat.vch;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;

public final class Theme {
    public static final Color INK = new Color(22,22,22), NAV = new Color(38,38,38), BLUE = new Color(15,98,254), PAPER = new Color(244,244,244), MUTED = new Color(111,111,111);
    public static void install() {
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch (Exception ignored) {}
        Font font = new Font("Segoe UI",Font.PLAIN,14);
        for (Object key : UIManager.getDefaults().keySet()) if (UIManager.get(key) instanceof Font) UIManager.put(key,font);
        UIManager.put("Panel.background",PAPER); UIManager.put("Label.foreground",INK); UIManager.put("TextField.background",Color.WHITE);
        UIManager.put("TabbedPane.selected",Color.WHITE); UIManager.put("List.selectionBackground",BLUE); UIManager.put("List.selectionForeground",Color.WHITE);
    }
    public static JPanel panel(LayoutManager layout) { JPanel p = new JPanel(layout); p.setBorder(new EmptyBorder(20,20,20,20)); return p; }
    public static JLabel title(String text, int size) { JLabel l = new JLabel();l.putClientProperty("html.disable",true);l.setText(text); l.setFont(new Font("Segoe UI",Font.PLAIN,size)); return l; }
    public static JButton button(String text, Runnable action) { JButton b = new JButton(text); b.setBackground(BLUE); b.setForeground(Color.WHITE); b.setFocusPainted(false); b.setBorder(new EmptyBorder(12,18,12,18)); b.addActionListener(e -> action.run()); return b; }
    public static JPanel form(Object... fields) {
        JPanel p = new JPanel(new GridBagLayout()); p.setBorder(new EmptyBorder(18,18,18,18)); GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(7,4,7,4); c.fill = GridBagConstraints.HORIZONTAL;
        for (int i=0;i<fields.length;i+=2) { c.gridx=0;c.gridy=i/2;c.weightx=0;p.add(new JLabel(fields[i].toString()),c);c.gridx=1;c.weightx=1;p.add((Component)fields[i+1],c); } return p;
    }
    public static void error(Component parent, Throwable error) { JOptionPane.showMessageDialog(parent,error.getMessage()==null?error.toString():error.getMessage(),"VCH",JOptionPane.ERROR_MESSAGE); }
}
