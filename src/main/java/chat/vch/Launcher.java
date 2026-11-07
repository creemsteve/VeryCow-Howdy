package chat.vch;

import javax.swing.*;
import java.nio.file.*;
import java.util.*;

public final class Launcher {
    public static Path dataDir(String role) { return Path.of(System.getProperty("vch.data",Path.of(System.getProperty("user.home"),".vch").toString()),role); }
    public static Vault unlock(String role) {
        Path file = dataDir(role).resolve("data.vault");
        for (;;) {
            JPasswordField password = new JPasswordField(26);
            JPanel form = Theme.form(Files.exists(file)?"Decryption password":"Create a password (12+ characters)",password);
            if (JOptionPane.showConfirmDialog(null,form,"VCH / " + role + " vault",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)!=JOptionPane.OK_OPTION) return null;
            char[] p = password.getPassword(); password.setText("");
            try {
                if (!Files.exists(file)) {
                    JPasswordField confirm = new JPasswordField(26);
                    if (JOptionPane.showConfirmDialog(null,Theme.form("Confirm password",confirm),"Confirm vault password",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION) { Arrays.fill(p,'\0'); continue; }
                    char[] q = confirm.getPassword(); boolean same = Arrays.equals(p,q); Arrays.fill(q,'\0'); confirm.setText("");
                    if (!same) { Arrays.fill(p,'\0'); throw new Exception("Passwords do not match"); }
                }
                return new Vault(file,p);
            } catch (Exception e) { Theme.error(null,e); }
        }
    }
}
