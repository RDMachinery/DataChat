import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseEvent;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DataChat - load a CSV, ask questions in plain English, get an answer and a chart.
 *
 * How it works
 *   1. Your question + a compact description of the table (column names, types, value ranges)
 *      goes to Claude, which replies with a small JSON "analysis plan" (what to group, measure,
 *      filter, compare, and which chart to draw).
 *   2. The app executes that plan itself on the FULL dataset, so the numbers are exact and the
 *      raw rows never leave your machine (only the schema, optional sample rows, and the small
 *      aggregated result table are sent to the API).
 *   3. Claude writes a plain-English explanation of the computed result; the app draws the chart.
 *
 * No API key? Demo / offline mode uses a built-in rule-based planner so everything still works.
 *
 * Requires Java 17+.   Run:   java DataChat.java      (or: javac DataChat.java && java DataChat)
 * Optional env vars: ANTHROPIC_API_KEY, ANTHROPIC_MODEL
 */
public class DataChat extends JFrame {

    // =====================================================================================
    //  Entry point
    // =====================================================================================
    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            DataChat app = new DataChat();
            app.setVisible(true);
            app.loadDemo();
        });
    }

    // =====================================================================================
    //  UI
    // =====================================================================================
    private static final String BLUE = "#1a5fb4", GREEN = "#26734d", RED = "#b3261e", GREY = "#777777";

    private DataTable table;
    private boolean demoLoaded;
    private final List<String[]> turns = new ArrayList<>();      // {question, planJson}
    private final StringBuilder chatHtml = new StringBuilder();
    private File lastDir;

    private final JEditorPane chat = new JEditorPane("text/html", "");
    private final JTextField input = new JTextField();
    private final JButton sendBtn = new JButton("Ask");
    private final JPasswordField keyField = new JPasswordField(16);
    private final JTextField modelField = new JTextField("claude-sonnet-5-5", 14);
    private final JCheckBox offlineBox = new JCheckBox("Offline mode");
    private final JCheckBox samplesBox = new JCheckBox("Send sample rows to AI");
    private final JLabel status = new JLabel(" ");
    private final JPanel chartHost = new JPanel(new GridLayout(0, 1, 0, 8));
    private final JTable resultTable = new JTable();
    private final JTable dataTable = new JTable();
    private final JTextArea planArea = new JTextArea();
    private final JPanel examples = new JPanel(new GridLayout(0, 1, 0, 3));
    private final JTabbedPane tabs = new JTabbedPane();
    private final JButton saveCsvBtn = new JButton("Save result as CSV...");
    private Result lastResult;

    public DataChat() {
        super("DataChat - ask your CSV anything");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1320, 820);
        setLocationRelativeTo(null);

        String envKey = System.getenv("ANTHROPIC_API_KEY");
        if (envKey != null) keyField.setText(envKey);
        String envModel = System.getenv("ANTHROPIC_MODEL");
        if (envModel != null && !envModel.isBlank()) modelField.setText(envModel);
        keyField.setToolTipText("Optional. Without a key the built-in offline engine answers instead.");
        samplesBox.setToolTipText("Off by default: only column names, types and value summaries are sent to Claude.");

        // ---- toolbar
        JButton load = new JButton("Load CSV...");
        load.addActionListener(e -> chooseFile());
        JButton demo = new JButton("Demo data");
        demo.addActionListener(e -> loadDemo());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        bar.add(load);
        bar.add(demo);
        bar.add(Box.createHorizontalStrut(14));
        bar.add(new JLabel("Claude API key:"));
        bar.add(keyField);
        bar.add(new JLabel("Model:"));
        bar.add(modelField);
        bar.add(offlineBox);
        bar.add(samplesBox);

        // ---- chat column
        chat.setEditable(false);
        chat.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        JScrollPane chatScroll = new JScrollPane(chat);
        examples.setBorder(BorderFactory.createTitledBorder("Try asking"));
        JPanel inputRow = new JPanel(new BorderLayout(6, 0));
        inputRow.add(input, BorderLayout.CENTER);
        inputRow.add(sendBtn, BorderLayout.EAST);
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.add(examples, BorderLayout.NORTH);
        south.add(inputRow, BorderLayout.SOUTH);
        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 4));
        left.add(chatScroll, BorderLayout.CENTER);
        left.add(south, BorderLayout.SOUTH);
        input.addActionListener(e -> ask(input.getText()));
        sendBtn.addActionListener(e -> ask(input.getText()));

        // ---- result tabs
        JButton save = new JButton("Save chart as PNG...");
        save.addActionListener(e -> saveChart());
        JPanel chartBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        chartBar.add(save);
        JPanel chartTab = new JPanel(new BorderLayout());
        chartTab.add(chartBar, BorderLayout.NORTH);
        chartHost.setBackground(Color.WHITE);
        chartTab.add(chartHost, BorderLayout.CENTER);
        planArea.setEditable(false);
        planArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        resultTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        resultTable.setFillsViewportHeight(true);
        dataTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        dataTable.setFillsViewportHeight(true);
        tabs.addTab("Chart", chartTab);
        saveCsvBtn.setEnabled(false);
        saveCsvBtn.addActionListener(e -> saveCsv());
        JPanel resultBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        resultBar.add(saveCsvBtn);
        JPanel resultTab = new JPanel(new BorderLayout());
        resultTab.add(resultBar, BorderLayout.NORTH);
        resultTab.add(new JScrollPane(resultTable), BorderLayout.CENTER);
        tabs.addTab("Result table", resultTab);
        tabs.addTab("Your data", new JScrollPane(dataTable));
        tabs.addTab("How it was computed", new JScrollPane(planArea));
        JPanel right = new JPanel(new BorderLayout());
        right.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 8));
        right.add(tabs, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(430);
        split.setResizeWeight(0.0);

        status.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(bar, BorderLayout.NORTH);
        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(status, BorderLayout.SOUTH);

        // refresh engine label when key / mode changes
        keyField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updateStatus(); }
            public void removeUpdate(DocumentEvent e) { updateStatus(); }
            public void changedUpdate(DocumentEvent e) { updateStatus(); }
        });
        offlineBox.addActionListener(e -> updateStatus());

        // drag & drop a CSV anywhere on the window
        TransferHandler th = new TransferHandler() {
            public boolean canImport(TransferSupport s) { return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor); }

            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport s) {
                try {
                    List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty()) loadFile(files.get(0));
                    return true;
                } catch (Exception ex) {
                    return false;
                }
            }
        };
        ((JComponent) getContentPane()).setTransferHandler(th);
        left.setTransferHandler(th);
        tabs.setTransferHandler(th);
        updateStatus();
    }

    private boolean useAI() {
        return !offlineBox.isSelected() && keyField.getPassword().length > 0;
    }

    private void updateStatus() {
        String eng = useAI() ? "Claude (" + modelField.getText().trim() + ")" : "offline engine (no API calls)";
        String data = table == null ? "no data loaded"
                : (demoLoaded ? "DEMO data - " : "") + table.name + ": " + String.format(Locale.US, "%,d", table.rows) + " rows x " + table.cols.size() + " columns";
        status.setText("  " + data + "   |   Engine: " + eng);
    }

    private void setBusy(boolean busy, String msg) {
        input.setEnabled(!busy);
        sendBtn.setEnabled(!busy);
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
        if (busy && msg != null) status.setText("  " + msg);
        if (!busy) {
            updateStatus();
            input.requestFocusInWindow();
        }
    }

    // ---------------------------------------------------------------- chat transcript
    private void say(String who, String htmlBody, String color) {
        chatHtml.append("<div style='margin:6px 0 10px 0'><b style='color:").append(color).append("'>")
                .append(who).append("</b><br>").append(htmlBody).append("</div>");
        chat.setText("<html><body style='font-family:SansSerif;font-size:12px'>" + chatHtml + "</body></html>");
        SwingUtilities.invokeLater(() -> chat.setCaretPosition(chat.getDocument().getLength()));
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    static String mdLite(String s) {
        return esc(s).replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
    }

    // ---------------------------------------------------------------- loading data
    private void chooseFile() {
        JFileChooser fc = new JFileChooser(lastDir);
        fc.setFileFilter(new FileNameExtensionFilter("CSV / TSV / text files", "csv", "tsv", "txt"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) loadFile(fc.getSelectedFile());
    }

    private void loadFile(File f) {
        lastDir = f.getParentFile();
        setBusy(true, "Loading " + f.getName() + "...");
        new SwingWorker<DataTable, Void>() {
            protected DataTable doInBackground() throws Exception {
                byte[] b = Files.readAllBytes(f.toPath());
                String s = new String(b, StandardCharsets.UTF_8);
                if (s.indexOf('\uFFFD') >= 0) s = new String(b, Charset.forName("windows-1252"));
                return DataTable.fromCsv(f.getName(), s);
            }

            protected void done() {
                try {
                    setTable(get(), false);
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    say("Analyst", "I couldn't read that file: " + esc(c.getMessage()), RED);
                } finally {
                    setBusy(false, null);
                }
            }
        }.execute();
    }

    void loadDemo() {
        setTable(DemoData.create(), true);
    }

    private void setTable(DataTable t, boolean demo) {
        table = t;
        demoLoaded = demo;
        turns.clear();
        chatHtml.setLength(0);
        lastResult = null;
        saveCsvBtn.setEnabled(false);
        dataTable.setModel(new AbstractTableModel() {
            public int getRowCount() { return t.rows; }
            public int getColumnCount() { return t.cols.size(); }
            public String getColumnName(int c) { return t.cols.get(c).name; }
            public Object getValueAt(int r, int c) { return t.cols.get(c).text[r]; }
        });
        packColumns(dataTable);
        resultTable.setModel(new DefaultTableModel());
        planArea.setText("");
        showCharts(List.of());
        rebuildExamples();

        StringBuilder cols = new StringBuilder();
        for (Column c : t.cols) {
            if (cols.length() > 0) cols.append(", ");
            cols.append(esc(c.name)).append(" <span style='color:").append(GREY).append("'>(").append(c.type.name().toLowerCase()).append(")</span>");
        }
        String intro = demo
                ? "This is <b>demo data</b>: " + String.format(Locale.US, "%,d", t.rows) + " synthetic financial transactions (Jan-Jun 2026) with a deliberate slump in March. "
                + "Click an example below or type your own question."
                : "Loaded <b>" + esc(t.name) + "</b>: " + String.format(Locale.US, "%,d", t.rows) + " rows. Ask me anything about it.";
        say("Analyst", intro + "<br><br><b>Columns:</b> " + cols, GREEN);
        updateStatus();
    }

    private void rebuildExamples() {
        examples.removeAll();
        for (String q : DemoData.examplesFor(table, demoLoaded)) {
            JButton b = new JButton("<html>" + esc(q) + "</html>");
            b.setHorizontalAlignment(SwingConstants.LEFT);
            b.setFocusPainted(false);
            b.addActionListener(e -> ask(q));
            examples.add(b);
        }
        examples.revalidate();
        examples.repaint();
    }

    // ---------------------------------------------------------------- asking questions
    static final class Outcome {
        Plan plan;
        Result result;
        String answer, warning, error;
    }

    private void ask(String question) {
        final String q = question == null ? "" : question.trim();
        if (q.isEmpty()) return;
        if (table == null) {
            say("Analyst", "Load a CSV first, or click <b>Demo data</b>.", RED);
            return;
        }
        say("You", esc(q), BLUE);
        input.setText("");
        final DataTable t = table;
        final boolean ai = useAI();
        final Claude claude = ai ? new Claude(new String(keyField.getPassword()).trim(), modelField.getText().trim()) : null;
        final boolean samples = samplesBox.isSelected();
        final List<String[]> history = new ArrayList<>(turns);
        setBusy(true, ai ? "Asking Claude..." : "Analysing...");

        new SwingWorker<Outcome, Void>() {
            protected Outcome doInBackground() {
                Outcome o = new Outcome();
                Plan plan = null;
                Result result = null;
                if (claude != null) {
                    try {
                        plan = claude.plan(q, t, history, samples);
                        result = Engine.execute(t, plan);
                    } catch (Exception ex) {
                        o.warning = "Claude's plan could not be used (" + ex.getMessage() + "). I fell back to the offline engine.";
                        plan = null;
                        result = null;
                    }
                }
                if (plan == null) {
                    try {
                        plan = LocalPlanner.plan(q, t);
                        result = Engine.execute(t, plan);
                    } catch (AnalysisException ex) {
                        o.error = ex.getMessage();
                        return o;
                    } catch (RuntimeException ex) {
                        o.error = "Something went wrong: " + ex;
                        return o;
                    }
                    o.answer = LocalPlanner.narrate(plan, result);
                } else {
                    try {
                        o.answer = claude.narrate(q, plan, result);
                    } catch (Exception ex) {
                        o.warning = "Claude could not write the summary (" + ex.getMessage() + "); showing the computed facts instead.";
                        o.answer = LocalPlanner.narrate(plan, result);
                    }
                }
                o.plan = plan;
                o.result = result;
                return o;
            }

            protected void done() {
                try {
                    Outcome o = get();
                    if (o.warning != null) say("Note", esc(o.warning), GREY);
                    if (o.error != null) {
                        say("Analyst", esc(o.error), RED);
                    } else {
                        showResult(o.plan, o.result);
                        turns.add(new String[]{q, Json.write(o.plan.toJson())});
                        say("Analyst", mdLite(o.answer) + "<br><span style='color:" + GREY + "'>Based on "
                                + String.format(Locale.US, "%,d", o.result.matched) + " of " + String.format(Locale.US, "%,d", o.result.total)
                                + " rows.</span>", GREEN);
                    }
                } catch (Exception ex) {
                    say("Analyst", "Unexpected error: " + esc(String.valueOf(ex)), RED);
                } finally {
                    setBusy(false, null);
                }
            }
        }.execute();
    }

    private void showResult(Plan plan, Result r) {
        lastResult = r;
        saveCsvBtn.setEnabled(true);
        DefaultTableModel m = new DefaultTableModel(r.headers.toArray(), 0) {
            public boolean isCellEditable(int row, int col) { return false; }
        };
        for (String[] row : r.rows) m.addRow(row);
        resultTable.setModel(m);
        packColumns(resultTable);
        planArea.setText("Plan (what was executed on your full dataset)\n\n" + Json.pretty(plan.toJson())
                + "\n\nRows used: " + r.matched + " of " + r.total
                + (r.facts.isEmpty() ? "" : "\n\nComputed facts\n- " + String.join("\n- ", r.facts)));
        planArea.setCaretPosition(0);
        showCharts(r.charts);
        tabs.setSelectedIndex(r.charts.isEmpty() ? 1 : 0);
    }

    private void showCharts(List<ChartData> charts) {
        chartHost.removeAll();
        if (charts.isEmpty()) {
            JLabel l = new JLabel("No chart for this answer - see the Result table tab.", SwingConstants.CENTER);
            l.setForeground(Color.GRAY);
            chartHost.add(l);
        } else {
            for (ChartData c : charts) chartHost.add(new ChartPanel(c));
        }
        chartHost.revalidate();
        chartHost.repaint();
    }

    private void saveCsv() {
        if (lastResult == null) return;
        String base = lastResult.title == null || lastResult.title.isBlank() ? "result" : lastResult.title;
        base = base.replaceAll("[^A-Za-z0-9._ -]+", "").trim().replaceAll("\\s+", "_");
        if (base.isEmpty()) base = "result";
        if (base.length() > 60) base = base.substring(0, 60);
        JFileChooser fc = new JFileChooser(lastDir);
        fc.setFileFilter(new FileNameExtensionFilter("CSV file", "csv"));
        fc.setSelectedFile(new File(base + ".csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".csv")) f = new File(f.getPath() + ".csv");
        if (f.exists() && JOptionPane.showConfirmDialog(this, f.getName() + " already exists. Overwrite it?", "Save result",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try {
            Files.writeString(f.toPath(), "\uFEFF" + lastResult.toCsv(), StandardCharsets.UTF_8);   // BOM so Excel reads UTF-8
            lastDir = f.getParentFile();
            say("Analyst", "Saved the result table (" + lastResult.rows.size() + " rows) to <b>" + esc(f.getName()) + "</b>.", GREEN);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage());
        }
    }

    private void saveChart() {
        if (chartHost.getWidth() <= 0 || chartHost.getComponentCount() == 0) return;
        JFileChooser fc = new JFileChooser(lastDir);
        fc.setSelectedFile(new File("chart.png"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        BufferedImage img = new BufferedImage(chartHost.getWidth(), chartHost.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        chartHost.paint(g);
        g.dispose();
        try {
            File f = fc.getSelectedFile();
            if (!f.getName().toLowerCase().endsWith(".png")) f = new File(f.getPath() + ".png");
            ImageIO.write(img, "png", f);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage());
        }
    }

    static void packColumns(JTable tb) {
        FontMetrics fm = tb.getFontMetrics(tb.getFont());
        for (int c = 0; c < tb.getColumnCount(); c++) {
            int w = fm.stringWidth(String.valueOf(tb.getColumnName(c))) + 24;
            for (int r = 0; r < Math.min(tb.getRowCount(), 60); r++) {
                Object v = tb.getValueAt(r, c);
                if (v != null) w = Math.max(w, fm.stringWidth(v.toString()) + 16);
            }
            tb.getColumnModel().getColumn(c).setPreferredWidth(Math.max(60, Math.min(w, 320)));
        }
    }

    // =====================================================================================
    //  Data model + CSV parsing + type detection
    // =====================================================================================
    enum ColType { NUMBER, DATE, TEXT }

    static final class Column {
        final String name;
        ColType type = ColType.TEXT;
        String[] text;
        double[] num;
        LocalDate[] date;
        double nmin = Double.NaN, nmax = Double.NaN, nmean = Double.NaN;
        LocalDate dmin, dmax;
        private Set<String> distinct;

        Column(String name) { this.name = name; }

        Set<String> distinct() {
            if (distinct == null) {
                distinct = new LinkedHashSet<>();
                for (String s : text) if (s != null && !s.isBlank()) distinct.add(s.trim());
            }
            return distinct;
        }

        int distinctCount() { return distinct().size(); }

        void detect() {
            int n = 0, nn = 0;
            for (String s : text) {
                if (s.isEmpty()) continue;
                n++;
                if (!Double.isNaN(parseNum(s))) nn++;
            }
            if (n == 0) return;
            if (nn >= 0.95 * n && !idLike(name)) {
                num = new double[text.length];
                double sum = 0, mn = Double.POSITIVE_INFINITY, mx = Double.NEGATIVE_INFINITY;
                int cnt = 0;
                for (int i = 0; i < text.length; i++) {
                    num[i] = parseNum(text[i]);
                    if (!Double.isNaN(num[i])) {
                        sum += num[i];
                        cnt++;
                        mn = Math.min(mn, num[i]);
                        mx = Math.max(mx, num[i]);
                    }
                }
                type = ColType.NUMBER;
                nmin = mn;
                nmax = mx;
                nmean = cnt == 0 ? Double.NaN : sum / cnt;
                return;
            }
            List<String> sample = new ArrayList<>();
            for (String s : text) {
                if (!s.isEmpty()) sample.add(s);
                if (sample.size() >= 300) break;
            }
            for (Function<String, LocalDate> f : DATE_PARSERS) {
                int ok = 0;
                for (String s : sample) if (f.apply(s) != null) ok++;
                if (ok >= 0.95 * sample.size()) {
                    date = new LocalDate[text.length];
                    for (int i = 0; i < text.length; i++) {
                        date[i] = text[i].isEmpty() ? null : f.apply(text[i]);
                        if (date[i] != null) {
                            if (dmin == null || date[i].isBefore(dmin)) dmin = date[i];
                            if (dmax == null || date[i].isAfter(dmax)) dmax = date[i];
                        }
                    }
                    type = ColType.DATE;
                    return;
                }
            }
        }

        static boolean idLike(String name) {
            String l = name.toLowerCase(Locale.ROOT);
            return l.matches("(.*[\\s_\\-])?id([\\s_\\-].*)?") || name.matches(".*[a-z]ID$") || l.contains("zip")
                    || l.contains("postcode") || l.contains("phone");
        }
    }

    static final Pattern NUM = Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");

    static double parseNum(String s) {
        if (s == null) return Double.NaN;
        s = s.trim();
        if (s.isEmpty()) return Double.NaN;
        boolean neg = false;
        if (s.startsWith("(") && s.endsWith(")")) {
            neg = true;
            s = s.substring(1, s.length() - 1);
        }
        s = s.replaceAll("[\u00a3$\u20ac,\\s%]", "");
        if (s.isEmpty() || !NUM.matcher(s).matches()) return Double.NaN;
        try {
            double v = Double.parseDouble(s);
            return neg ? -v : v;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static final List<Function<String, LocalDate>> DATE_PARSERS = List.of(
            s -> {
                s = trimTime(s);
                if (s.matches("\\d{4}-\\d{2}")) s = s + "-01";
                return parseWith(s, "yyyy-MM-dd");
            },
            s -> parseWith(trimTime(s), "yyyy/MM/dd"),
            s -> parseWith(trimTime(s), "d/M/yyyy"),
            s -> parseWith(trimTime(s), "M/d/yyyy"),
            s -> parseWith(trimTime(s), "d-M-yyyy"),
            s -> parseWith(trimTime(s), "d.M.yyyy"),
            s -> parseWith(trimTime(s), "d MMM yyyy"),
            s -> parseWith(trimTime(s), "d MMMM yyyy"),
            s -> parseWith(trimTime(s), "MMM d, yyyy"),
            s -> parseWith(trimTime(s), "MMMM d, yyyy"));

    static String trimTime(String s) {
        s = s.trim();
        int t = s.indexOf('T');
        if (t == 10) return s.substring(0, 10);
        int sp = s.indexOf(' ');
        if (sp > 0 && s.indexOf(':', sp) > 0) return s.substring(0, sp);
        return s;
    }

    private static final Map<String, DateTimeFormatter> FMT_CACHE = new HashMap<>();

    static LocalDate parseWith(String s, String pattern) {
        try {
            DateTimeFormatter f;
            synchronized (FMT_CACHE) {
                f = FMT_CACHE.computeIfAbsent(pattern, p -> DateTimeFormatter.ofPattern(p, Locale.ENGLISH));
            }
            return LocalDate.parse(s, f);
        } catch (Exception e) {
            return null;
        }
    }

    static final class DataTable {
        final String name;
        final int rows;
        final List<Column> cols = new ArrayList<>();

        DataTable(String name, int rows) {
            this.name = name;
            this.rows = rows;
        }

        static DataTable fromCsv(String name, String csv) throws IOException {
            if (csv.startsWith("\uFEFF")) csv = csv.substring(1);
            List<String[]> recs = parseCsv(csv);
            return fromRecords(name, recs);
        }

        static DataTable fromRecords(String name, List<String[]> recs) throws IOException {
            if (recs.size() < 2) throw new IOException("The file needs a header row and at least one data row.");
            String[] head = recs.get(0);
            int nc = head.length, nr = recs.size() - 1;
            Set<String> used = new HashSet<>();
            DataTable t = new DataTable(name, nr);
            for (int c = 0; c < nc; c++) {
                String h = head[c].trim();
                if (h.isEmpty()) h = "Column " + (c + 1);
                String base = h;
                int k = 2;
                while (!used.add(h.toLowerCase(Locale.ROOT))) h = base + "_" + (k++);
                Column col = new Column(h);
                col.text = new String[nr];
                for (int r = 0; r < nr; r++) {
                    String[] rec = recs.get(r + 1);
                    col.text[r] = c < rec.length ? rec[c].trim() : "";
                }
                col.detect();
                t.cols.add(col);
            }
            return t;
        }

        Column find(String name) {
            if (name == null) return null;
            String n = name.trim();
            for (Column c : cols) if (c.name.equals(n)) return c;
            for (Column c : cols) if (c.name.equalsIgnoreCase(n)) return c;
            String k = key(n);
            for (Column c : cols) if (key(c.name).equals(k)) return c;
            for (Column c : cols) {
                String ck = key(c.name);
                if (ck.length() >= 3 && k.length() >= 3 && (ck.contains(k) || k.contains(ck))) return c;
            }
            return null;
        }

        static String key(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }

        Column firstOfType(ColType t) {
            for (Column c : cols) if (c.type == t) return c;
            return null;
        }

        List<Column> colsOfType(ColType t) {
            List<Column> out = new ArrayList<>();
            for (Column c : cols) if (c.type == t) out.add(c);
            return out;
        }

        String columnNames() {
            StringBuilder sb = new StringBuilder();
            for (Column c : cols) sb.append(sb.length() == 0 ? "" : ", ").append(c.name);
            return sb.toString();
        }

        /** Compact schema description handed to the language model. */
        String describe(boolean sampleRows) {
            StringBuilder sb = new StringBuilder();
            sb.append("Table \"").append(name).append("\": ").append(rows).append(" rows, ").append(cols.size()).append(" columns.\nColumns:\n");
            for (Column c : cols) {
                sb.append("- ").append(c.name).append(" [").append(c.type).append("]");
                switch (c.type) {
                    case NUMBER -> sb.append(" min ").append(Fmt.full(c.nmin)).append(", max ").append(Fmt.full(c.nmax)).append(", mean ").append(Fmt.full(c.nmean));
                    case DATE -> sb.append(" ").append(c.dmin).append(" .. ").append(c.dmax)
                            .append(" (month labels ").append(Engine.bucketLabel(c.dmin, "month")).append(" .. ").append(Engine.bucketLabel(c.dmax, "month")).append(")");
                    default -> {
                        int dc = c.distinctCount();
                        sb.append(" ").append(dc).append(" distinct");
                        if (dc <= 25) sb.append(": ").append(String.join(", ", c.distinct()));
                        else {
                            Iterator<String> it = c.distinct().iterator();
                            sb.append(", e.g. ");
                            for (int i = 0; i < 6 && it.hasNext(); i++) sb.append(i > 0 ? ", " : "").append(it.next());
                        }
                    }
                }
                sb.append("\n");
            }
            if (sampleRows) {
                sb.append("Sample rows:\n");
                for (int r = 0; r < Math.min(3, rows); r++) {
                    for (int c = 0; c < cols.size(); c++) sb.append(c > 0 ? " | " : "").append(cols.get(c).text[r]);
                    sb.append("\n");
                }
            }
            return sb.toString();
        }
    }

    static List<String[]> parseCsv(String text) {
        // delimiter sniffing on the first line
        int eol = text.indexOf('\n');
        String first = eol < 0 ? text : text.substring(0, eol);
        char delim = ',';
        int best = -1;
        for (char c : new char[]{',', ';', '\t', '|'}) {
            int cnt = 0;
            boolean inQ = false;
            for (int i = 0; i < first.length(); i++) {
                char ch = first.charAt(i);
                if (ch == '"') inQ = !inQ;
                else if (ch == c && !inQ) cnt++;
            }
            if (cnt > best) {
                best = cnt;
                delim = c;
            }
        }
        List<String[]> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQ) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        f.append('"');
                        i++;
                    } else inQ = false;
                } else f.append(c);
            } else if (c == '"') {
                inQ = true;
            } else if (c == delim) {
                row.add(f.toString());
                f.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(f.toString());
                f.setLength(0);
                if (!(row.size() == 1 && row.get(0).isBlank())) out.add(row.toArray(new String[0]));
                row = new ArrayList<>();
            } else f.append(c);
        }
        if (f.length() > 0 || !row.isEmpty()) {
            row.add(f.toString());
            if (!(row.size() == 1 && row.get(0).isBlank())) out.add(row.toArray(new String[0]));
        }
        return out;
    }

    // =====================================================================================
    //  Analysis plan (what the AI produces) and result (what the engine computes)
    // =====================================================================================
    record Filter(String column, String op, List<String> values) {}

    static final class Plan {
        String intent = "aggregate", title, metric, agg = "sum", x, bucket, series, sort = "none", chart = "auto",
                from, to, direction = "drop", message;
        Integer limit;
        List<Filter> filters = new ArrayList<>();

        @SuppressWarnings("unchecked")
        static Plan fromJson(Map<String, Object> m) {
            Plan p = new Plan();
            String in = lower(nz(m.get("intent")));
            p.intent = in != null && Set.of("aggregate", "drivers", "rows", "describe").contains(in) ? in : "aggregate";
            p.title = nz(m.get("title"));
            p.metric = nz(m.get("metric"));
            String a = lower(nz(m.get("agg")));
            if (a != null) p.agg = a;
            p.x = nz(m.get("x"));
            p.bucket = lower(nz(m.get("bucket")));
            p.series = nz(m.get("series"));
            String s = lower(nz(m.get("sort")));
            if (s != null) p.sort = s;
            String ch = lower(nz(m.get("chart")));
            if (ch != null) p.chart = ch;
            p.from = nz(m.get("from"));
            p.to = nz(m.get("to"));
            String dir = lower(nz(m.get("direction")));
            if (dir != null) p.direction = dir;
            p.message = nz(m.get("message"));
            Object lim = m.get("limit");
            if (lim instanceof Number n && n.intValue() > 0) p.limit = n.intValue();
            else if (lim instanceof String ls && ls.trim().matches("\\d+")) p.limit = Integer.parseInt(ls.trim());
            if (m.get("filters") instanceof List<?> fl) {
                for (Object o : fl) {
                    if (!(o instanceof Map<?, ?> fm)) continue;
                    String col = nz(fm.get("column"));
                    if (col == null) continue;
                    String op = nz(fm.get("op"));
                    Object v = fm.get("value");
                    List<String> vals = new ArrayList<>();
                    if (v instanceof List<?> vl) for (Object e : vl) vals.add(nz(e));
                    else if (v != null) vals.add(nz(v));
                    vals.removeIf(Objects::isNull);
                    p.filters.add(new Filter(col, op == null ? "=" : op, vals));
                }
            }
            return p;
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("intent", intent);
            if (title != null) m.put("title", title);
            m.put("metric", metric);
            m.put("agg", agg);
            m.put("x", x);
            if (bucket != null) m.put("bucket", bucket);
            if (series != null) m.put("series", series);
            if (!filters.isEmpty()) {
                List<Object> fl = new ArrayList<>();
                for (Filter f : filters) {
                    Map<String, Object> fm = new LinkedHashMap<>();
                    fm.put("column", f.column());
                    fm.put("op", f.op());
                    fm.put("value", f.values().size() == 1 && !f.op().contains("in") ? (Object) f.values().get(0) : f.values());
                    fl.add(fm);
                }
                m.put("filters", fl);
            }
            m.put("sort", sort);
            if (limit != null) m.put("limit", limit);
            m.put("chart", chart);
            if (from != null) m.put("from", from);
            if (to != null) m.put("to", to);
            if (intent.equals("drivers")) m.put("direction", direction);
            if (message != null) m.put("message", message);
            return m;
        }

        static String lower(String s) { return s == null ? null : s.toLowerCase(Locale.ROOT); }

        static String nz(Object o) {
            if (o == null) return null;
            String s = (o instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) ? String.valueOf((long) (double) d) : o.toString();
            s = s.trim();
            return (s.isEmpty() || s.equalsIgnoreCase("null")) ? null : s;
        }
    }

    enum ChartType { BAR, HBAR, LINE, PIE }

    static final class ChartData {
        ChartType type = ChartType.BAR;
        String title = "", xLabel = "", yLabel = "";
        List<String> labels = new ArrayList<>();
        List<String> series = new ArrayList<>();
        List<double[]> values = new ArrayList<>();
        boolean signColors;
        Set<Integer> highlight = new HashSet<>();
    }

    static final class Result {
        String title = "";
        List<String> headers = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        List<ChartData> charts = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        int matched, total;
        boolean rawCells;   // true when cells are original text (row listings); false when they are formatted numbers

        /** RFC 4180 CSV. Formatted numbers like "+12,503.58" are turned back into plain numbers for spreadsheets. */
        String toCsv() {
            StringBuilder sb = new StringBuilder();
            appendCsvRow(sb, headers.toArray(new String[0]), false);
            for (String[] row : rows) appendCsvRow(sb, row, !rawCells);
            return sb.toString();
        }

        private static void appendCsvRow(StringBuilder sb, String[] cells, boolean unformat) {
            for (int i = 0; i < cells.length; i++) {
                String c = cells[i] == null ? "" : cells[i];
                if (unformat && c.matches("[-+]?\\d{1,3}(,\\d{3})*(\\.\\d+)?")) c = c.replace(",", "").replaceFirst("^\\+", "");
                if (i > 0) sb.append(',');
                if (c.contains(",") || c.contains("\"") || c.contains("\n") || c.contains("\r"))
                    sb.append('"').append(c.replace("\"", "\"\"")).append('"');
                else sb.append(c);
            }
            sb.append("\r\n");
        }

        String toText(int maxRows) {
            StringBuilder sb = new StringBuilder(String.join("\t", headers)).append("\n");
            for (int i = 0; i < Math.min(maxRows, rows.size()); i++) sb.append(String.join("\t", rows.get(i))).append("\n");
            if (rows.size() > maxRows) sb.append("... (").append(rows.size() - maxRows).append(" more rows)\n");
            return sb.toString();
        }
    }

    static final class AnalysisException extends Exception {
        AnalysisException(String m) { super(m); }
    }

    // =====================================================================================
    //  Number formatting
    // =====================================================================================
    static final class Fmt {
        static String full(double v) {
            if (Double.isNaN(v) || Double.isInfinite(v)) return "";
            if (Math.abs(v) >= 1e15) return String.format(Locale.US, "%.3e", v);
            if (v == Math.rint(v)) return String.format(Locale.US, "%,.0f", v);
            return String.format(Locale.US, "%,.2f", v);
        }

        static String signed(double v) { return (v > 0 ? "+" : "") + full(v); }

        static String pct(double frac) {
            if (Double.isNaN(frac) || Double.isInfinite(frac)) return "n/a";
            return String.format(Locale.US, "%+.1f%%", frac * 100);
        }

        static String share(double frac) {
            if (Double.isNaN(frac) || Double.isInfinite(frac)) return "";
            return String.format(Locale.US, "%.0f%%", frac * 100);
        }

        static String compact(double v) {
            double a = Math.abs(v);
            if (a == 0) return "0";
            if (a >= 1e9) return new DecimalFormat("#.##").format(v / 1e9) + "B";
            if (a >= 1e6) return new DecimalFormat("#.##").format(v / 1e6) + "M";
            if (a >= 1e4) return new DecimalFormat("#.#").format(v / 1e3) + "K";
            if (a >= 100) return new DecimalFormat("#,##0").format(v);
            if (a >= 10) return new DecimalFormat("#,##0.#").format(v);
            return new DecimalFormat("#,##0.##").format(v);
        }
    }

    // =====================================================================================
    //  Engine: executes a plan on the full data
    // =====================================================================================
    static final class Acc {
        double sum;
        long n;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        ArrayList<Double> vals;

        void add(double v, boolean keep) {
            sum += v;
            n++;
            if (v < min) min = v;
            if (v > max) max = v;
            if (keep) {
                if (vals == null) vals = new ArrayList<>();
                vals.add(v);
            }
        }

        double get(String agg) {
            if (n == 0) return agg.equals("count") || agg.equals("sum") ? 0 : Double.NaN;
            switch (agg) {
                case "avg": return sum / n;
                case "count": return n;
                case "min": return min;
                case "max": return max;
                case "median": {
                    if (vals == null) return Double.NaN;
                    Collections.sort(vals);
                    int k = vals.size();
                    return k % 2 == 1 ? vals.get(k / 2) : (vals.get(k / 2 - 1) + vals.get(k / 2)) / 2;
                }
                default: return sum;
            }
        }
    }

    record DriverRow(int level, String label, double from, double to, double delta, double excess, double share) {}

    static final class Engine {
        static Result execute(DataTable t, Plan p) throws AnalysisException {
            boolean[] keep = mask(t, p.filters);
            int matched = 0;
            for (boolean b : keep) if (b) matched++;
            if (matched == 0)
                throw new AnalysisException("No rows match the filters" + (p.filters.isEmpty() ? "" : " (" + describeFilters(p) + ")") + ".");
            Result r = new Result();
            r.matched = matched;
            r.total = t.rows;
            switch (p.intent) {
                case "describe" -> describe(t, keep, r);
                case "rows" -> rows(t, p, keep, r);
                case "drivers" -> drivers(t, p, keep, r);
                default -> aggregate(t, p, keep, r);
            }
            if (p.message != null) r.facts.add(p.message);
            return r;
        }

        static String describeFilters(Plan p) {
            List<String> parts = new ArrayList<>();
            for (Filter f : p.filters) parts.add(f.column() + " " + f.op() + " " + String.join("/", f.values()));
            return String.join("; ", parts);
        }

        // ------------------------------------------------------------ helpers
        static Column need(DataTable t, String name, String what) throws AnalysisException {
            Column c = t.find(name);
            if (c == null)
                throw new AnalysisException("I couldn't find a column called \"" + name + "\" (" + what + "). Available columns: " + t.columnNames() + ".");
            return c;
        }

        static Column optional(DataTable t, String name, String what) throws AnalysisException {
            return name == null ? null : need(t, name, what);
        }

        static Column metricOf(DataTable t, Plan p) throws AnalysisException {
            if (p.metric == null) return null;
            Column c = need(t, p.metric, "measure");
            if (c.type != ColType.NUMBER) {
                if ("count".equals(p.agg)) return null;
                throw new AnalysisException("Column \"" + c.name + "\" isn't numeric, so I can't " + p.agg + " it. Numeric columns: "
                        + t.colsOfType(ColType.NUMBER).stream().map(x -> x.name).toList() + ".");
            }
            return c;
        }

        static String aggOf(Plan p, Column m) {
            if (m == null) return "count";
            String a = p.agg == null ? "sum" : p.agg;
            return Set.of("sum", "avg", "count", "min", "max", "median").contains(a) ? a : "sum";
        }

        static String measureLabel(String agg, Column m) {
            if (m == null) return "Number of rows";
            return switch (agg) {
                case "avg" -> "Average " + m.name;
                case "count" -> "Count of " + m.name;
                case "min" -> "Minimum " + m.name;
                case "max" -> "Maximum " + m.name;
                case "median" -> "Median " + m.name;
                default -> "Total " + m.name;
            };
        }

        static boolean additive(String agg) { return agg.equals("sum") || agg.equals("count"); }

        static double nz0(double v) { return Double.isNaN(v) ? 0 : v; }

        static final List<String> WEEKDAYS = List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun");

        static String bucketLabel(LocalDate d, String bucket) {
            switch (bucket == null ? "month" : bucket) {
                case "day": return d.toString();
                case "weekday": return WEEKDAYS.get(d.getDayOfWeek().getValue() - 1);
                case "week": return String.format("%d-W%02d", d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
                case "quarter": return d.getYear() + "-Q" + d.get(IsoFields.QUARTER_OF_YEAR);
                case "year": return String.valueOf(d.getYear());
                default: return String.format("%04d-%02d", d.getYear(), d.getMonthValue());
            }
        }

        static String bucketOf(String wanted, Column c, boolean[] keep) {
            if (wanted != null && Set.of("day", "weekday", "week", "month", "quarter", "year").contains(wanted)) return wanted;
            LocalDate mn = null, mx = null;
            for (int i = 0; i < keep.length; i++) {
                if (!keep[i] || c.date[i] == null) continue;
                if (mn == null || c.date[i].isBefore(mn)) mn = c.date[i];
                if (mx == null || c.date[i].isAfter(mx)) mx = c.date[i];
            }
            if (mn == null) return "month";
            long days = ChronoUnit.DAYS.between(mn, mx);
            if (days <= 62) return "day";
            if (days <= 1100) return "month";
            if (days <= 3650) return "quarter";
            return "year";
        }

        static String key(Column c, int i, String bucket) {
            if (c.type == ColType.DATE) {
                LocalDate d = c.date[i];
                return d == null ? null : bucketLabel(d, bucket);
            }
            String s = c.text[i];
            return (s == null || s.isBlank()) ? null : s.trim();
        }

        // ------------------------------------------------------------ filtering
        static boolean[] mask(DataTable t, List<Filter> fs) throws AnalysisException {
            boolean[] keep = new boolean[t.rows];
            Arrays.fill(keep, true);
            for (Filter f : fs) {
                Column c = need(t, f.column(), "filter");
                String op = f.op() == null ? "=" : f.op().trim().toLowerCase(Locale.ROOT);
                if (op.equals("==") || op.equals("equals") || op.equals("is")) op = "=";
                if (op.equals("<>") || op.equals("not")) op = "!=";
                for (int i = 0; i < t.rows; i++) if (keep[i]) keep[i] = test(c, i, op, f.values());
            }
            return keep;
        }

        static boolean test(Column c, int i, String op, List<String> vals) {
            if (vals.isEmpty()) return true;
            boolean negate = op.equals("!=") || op.equals("not in");
            switch (c.type) {
                case NUMBER: {
                    double x = c.num[i];
                    if (Double.isNaN(x)) return false;
                    String v0 = vals.get(0);
                    double n0 = parseNum(v0);
                    switch (op) {
                        case ">": return x > n0;
                        case ">=": return x >= n0;
                        case "<": return x < n0;
                        case "<=": return x <= n0;
                        case "contains": return c.text[i].contains(v0);
                        default: {
                            boolean any = false;
                            for (String v : vals) if (parseNum(v) == x) any = true;
                            return negate != any;
                        }
                    }
                }
                case DATE: {
                    LocalDate d = c.date[i];
                    if (d == null) return false;
                    String ds = d.toString(), v0 = vals.get(0);
                    String cut = ds.length() > v0.length() ? ds.substring(0, v0.length()) : ds;
                    int k = cut.compareTo(v0);
                    switch (op) {
                        case ">": return k > 0;
                        case ">=": return k >= 0;
                        case "<": return k < 0;
                        case "<=": return k <= 0;
                        default: {
                            boolean any = false;
                            for (String v : vals) if (ds.startsWith(v)) any = true;
                            return negate != any;
                        }
                    }
                }
                default: {
                    String s = c.text[i] == null ? "" : c.text[i].trim().toLowerCase(Locale.ROOT);
                    String v0 = vals.get(0).toLowerCase(Locale.ROOT);
                    switch (op) {
                        case "contains": return s.contains(v0);
                        case ">": return s.compareTo(v0) > 0;
                        case ">=": return s.compareTo(v0) >= 0;
                        case "<": return s.compareTo(v0) < 0;
                        case "<=": return s.compareTo(v0) <= 0;
                        default: {
                            boolean any = false;
                            for (String v : vals) if (s.equals(v.toLowerCase(Locale.ROOT).trim())) any = true;
                            return negate != any;
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------ describe
        static void describe(DataTable t, boolean[] keep, Result r) {
            r.title = "Overview of " + t.name;
            r.headers = List.of("Column", "Type", "Filled", "Distinct", "Min / earliest", "Max / latest", "Mean / most common");
            for (Column c : t.cols) {
                int filled = 0;
                Set<String> dist = new HashSet<>();
                Map<String, Integer> freq = new HashMap<>();
                double mn = Double.POSITIVE_INFINITY, mx = Double.NEGATIVE_INFINITY, sum = 0;
                LocalDate dmn = null, dmx = null;
                for (int i = 0; i < t.rows; i++) {
                    if (!keep[i]) continue;
                    String s = c.text[i];
                    if (s == null || s.isBlank()) continue;
                    filled++;
                    dist.add(s);
                    switch (c.type) {
                        case NUMBER -> {
                            double v = c.num[i];
                            if (!Double.isNaN(v)) {
                                mn = Math.min(mn, v);
                                mx = Math.max(mx, v);
                                sum += v;
                            }
                        }
                        case DATE -> {
                            LocalDate d = c.date[i];
                            if (d != null) {
                                if (dmn == null || d.isBefore(dmn)) dmn = d;
                                if (dmx == null || d.isAfter(dmx)) dmx = d;
                            }
                        }
                        default -> freq.merge(s, 1, Integer::sum);
                    }
                }
                String a = "", b = "", m = "";
                if (c.type == ColType.NUMBER && filled > 0) {
                    a = Fmt.full(mn);
                    b = Fmt.full(mx);
                    m = Fmt.full(sum / filled);
                } else if (c.type == ColType.DATE && dmn != null) {
                    a = dmn.toString();
                    b = dmx.toString();
                } else if (!freq.isEmpty()) {
                    var top = Collections.max(freq.entrySet(), Map.Entry.comparingByValue());
                    m = top.getKey() + " (" + top.getValue() + ")";
                }
                r.rows.add(new String[]{c.name, c.type.name().toLowerCase(), String.valueOf(filled), String.valueOf(dist.size()), a, b, m});
            }
            r.facts.add("The table has " + r.matched + " rows and " + t.cols.size() + " columns: " + t.columnNames() + ".");
        }

        // ------------------------------------------------------------ rows
        static void rows(DataTable t, Plan p, boolean[] keep, Result r) throws AnalysisException {
            Column sc = p.metric != null ? t.find(p.metric) : null;
            if (sc == null) sc = t.firstOfType(ColType.NUMBER);
            final Column sortCol = sc;
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < t.rows; i++) if (keep[i]) idx.add(i);
            boolean asc = "asc".equals(p.sort);
            if (sortCol != null) {
                Comparator<Integer> cmp;
                if (sortCol.type == ColType.NUMBER)
                    cmp = Comparator.comparingDouble(i -> Double.isNaN(sortCol.num[i]) ? Double.NEGATIVE_INFINITY : sortCol.num[i]);
                else if (sortCol.type == ColType.DATE)
                    cmp = Comparator.comparing(i -> sortCol.date[i] == null ? LocalDate.MIN : sortCol.date[i]);
                else cmp = Comparator.comparing(i -> sortCol.text[i]);
                idx.sort(asc ? cmp : cmp.reversed());
            }
            int lim = Math.min(p.limit != null ? p.limit : 20, 200);
            r.rawCells = true;
            r.title = (p.title != null ? p.title : (asc ? "Lowest " : "Top ") + lim + " rows" + (sortCol != null ? " by " + sortCol.name : ""));
            for (Column c : t.cols) r.headers.add(c.name);
            for (int k = 0; k < Math.min(lim, idx.size()); k++) {
                String[] row = new String[t.cols.size()];
                for (int c = 0; c < row.length; c++) row[c] = t.cols.get(c).text[idx.get(k)];
                r.rows.add(row);
            }
            r.facts.add("Showing " + r.rows.size() + " rows" + (sortCol != null ? " sorted by " + sortCol.name + (asc ? " ascending" : " descending") : "") + ".");
        }

        // ------------------------------------------------------------ aggregate
        static void aggregate(DataTable t, Plan p, boolean[] keep, Result r) throws AnalysisException {
            Column m = metricOf(t, p);
            String agg = aggOf(p, m);
            Column xc = optional(t, p.x, "x-axis");
            Column sc = optional(t, p.series, "series");
            if (sc != null && sc == xc) sc = null;
            boolean timeX = xc != null && xc.type == ColType.DATE;
            String xb = timeX ? bucketOf(p.bucket, xc, keep) : null;
            String sb = (sc != null && sc.type == ColType.DATE) ? bucketOf(p.bucket, sc, keep) : null;
            String measure = measureLabel(agg, m);

            Map<String, Map<String, Acc>> grid = new LinkedHashMap<>();
            for (int i = 0; i < t.rows; i++) {
                if (!keep[i]) continue;
                double v = 1;
                if (m != null) {
                    v = m.num[i];
                    if (Double.isNaN(v)) continue;
                }
                String xk = xc == null ? "Total" : key(xc, i, xb);
                if (xk == null) continue;
                String sk = sc == null ? measure : key(sc, i, sb);
                if (sk == null) continue;
                grid.computeIfAbsent(xk, k -> new LinkedHashMap<>()).computeIfAbsent(sk, k -> new Acc()).add(v, agg.equals("median"));
            }
            if (grid.isEmpty()) throw new AnalysisException("There were no usable values for that combination of columns.");

            // series
            List<String> ss = new ArrayList<>();
            if (sc == null) ss.add(measure);
            else {
                Map<String, Double> st = new HashMap<>();
                for (var row : grid.values())
                    for (var e : row.entrySet()) st.merge(e.getKey(), nz0(e.getValue().get(agg)), Double::sum);
                ss.addAll(st.keySet());
                if (sc.type == ColType.DATE) Collections.sort(ss);
                else ss.sort((a, b) -> Double.compare(st.get(b), st.get(a)));
                if (ss.size() > 8) {
                    r.facts.add("Showing the 8 largest of " + ss.size() + " " + sc.name + " groups.");
                    ss = new ArrayList<>(ss.subList(0, 8));
                }
            }

            // x order
            Map<String, Double> tot = new HashMap<>();
            for (var e : grid.entrySet()) {
                double s = 0;
                for (String sk : ss) {
                    Acc a = e.getValue().get(sk);
                    if (a != null) s += nz0(a.get(agg));
                }
                tot.put(e.getKey(), s);
            }
            List<String> xs = new ArrayList<>(grid.keySet());
            String sort = p.sort == null ? "none" : p.sort;
            int totalX = xs.size();
            boolean weekday = "weekday".equals(xb);
            if (timeX && sort.equals("none")) {
                if (weekday) xs.sort(Comparator.comparingInt(WEEKDAYS::indexOf));
                else Collections.sort(xs);
                if (p.limit != null && xs.size() > p.limit) xs = new ArrayList<>(xs.subList(xs.size() - p.limit, xs.size()));
            } else if (xc != null && xc.type == ColType.NUMBER && sort.equals("none")) {
                xs.sort(Comparator.comparingDouble(s -> { double d = parseNum(s); return Double.isNaN(d) ? 0 : d; }));
                if (p.limit != null && xs.size() > p.limit) xs = new ArrayList<>(xs.subList(0, p.limit));
            } else {
                Comparator<String> cmp = Comparator.comparingDouble(tot::get);
                xs.sort(sort.equals("asc") ? cmp : cmp.reversed());
                int lim = p.limit != null ? p.limit : (timeX ? Integer.MAX_VALUE : 20);
                if (xs.size() > lim) xs = new ArrayList<>(xs.subList(0, lim));
            }
            if (xs.size() < totalX && p.limit == null)
                r.facts.add("Showing the top " + xs.size() + " of " + totalX + " " + (xc == null ? "" : xc.name) + " values.");

            double[][] vals = new double[ss.size()][xs.size()];
            for (int si = 0; si < ss.size(); si++)
                for (int xi = 0; xi < xs.size(); xi++) {
                    Acc a = grid.get(xs.get(xi)).get(ss.get(si));
                    vals[si][xi] = a == null ? (additive(agg) ? 0 : Double.NaN) : a.get(agg);
                }

            // table
            String xName = xc == null ? "" : (timeX ? xc.name + " (" + xb + ")" : xc.name);
            r.headers.add(xc == null ? "Result" : xName);
            r.headers.addAll(ss);
            for (int xi = 0; xi < xs.size(); xi++) {
                String[] row = new String[ss.size() + 1];
                row[0] = xs.get(xi);
                for (int si = 0; si < ss.size(); si++) row[si + 1] = Fmt.full(vals[si][xi]);
                r.rows.add(row);
            }

            r.title = p.title != null ? p.title
                    : measure + (xc != null ? " by " + (timeX ? xb : xc.name) : "") + (sc != null ? " and " + sc.name : "");

            // chart
            if (xc != null) {
                boolean neg = false;
                int maxLabel = 0;
                for (double[] row : vals) for (double v : row) if (v < 0) neg = true;
                for (String s : xs) maxLabel = Math.max(maxLabel, s.length());
                ChartData cd = new ChartData();
                cd.type = chartType(p.chart, timeX && !weekday, xs.size(), ss.size(), neg, maxLabel);
                cd.title = r.title;
                cd.xLabel = xName;
                cd.yLabel = measure;
                cd.labels = xs;
                cd.series = ss;
                for (double[] row : vals) cd.values.add(row);
                if (!r.rows.isEmpty()) r.charts.add(cd);
            }

            facts(r, p, xc, timeX && !weekday, xb, xs, ss, vals, agg, measure, m, matchedNote(r, p));
        }

        static String matchedNote(Result r, Plan p) {
            return p.filters.isEmpty() ? "" : " (filters: " + describeFilters(p) + ")";
        }

        static ChartType chartType(String want, boolean timeX, int nx, int ns, boolean neg, int maxLabel) {
            String w = want == null ? "auto" : want;
            if (w.equals("pie") && ns == 1 && nx >= 2 && nx <= 10 && !neg) return ChartType.PIE;
            if (w.equals("line")) return ChartType.LINE;
            if (w.equals("bar")) return !timeX && (nx > 12 || maxLabel > 14) ? ChartType.HBAR : ChartType.BAR;
            if (timeX) return nx >= 4 ? ChartType.LINE : ChartType.BAR;
            return nx > 8 || maxLabel > 14 ? ChartType.HBAR : ChartType.BAR;
        }

        static void facts(Result r, Plan p, Column xc, boolean timeX, String xb, List<String> xs, List<String> ss,
                          double[][] vals, String agg, String measure, Column m, String filt) {
            if (xc == null) {
                r.facts.add(measure + " is " + Fmt.full(vals[0][0]) + " across " + r.matched + " rows" + filt + ".");
                return;
            }
            double[] v;
            if (ss.size() == 1) v = vals[0];
            else if (additive(agg)) {
                v = new double[xs.size()];
                for (double[] row : vals) for (int i = 0; i < v.length; i++) v[i] += nz0(row[i]);
                int big = 0;
                double[] sums = new double[ss.size()];
                double all = 0;
                for (int si = 0; si < ss.size(); si++) {
                    for (double x : vals[si]) sums[si] += nz0(x);
                    all += sums[si];
                    if (sums[si] > sums[big]) big = si;
                }
                if (all != 0)
                    r.facts.add(ss.get(big) + " is the largest group overall at " + Fmt.full(sums[big]) + " (" + Fmt.share(sums[big] / all) + " of " + Fmt.full(all) + ").");
            } else {
                int bs = 0, bx = 0, ws = 0, wx = 0;
                double bv = Double.NEGATIVE_INFINITY, wv = Double.POSITIVE_INFINITY;
                for (int si = 0; si < ss.size(); si++)
                    for (int xi = 0; xi < xs.size(); xi++) {
                        double x = vals[si][xi];
                        if (Double.isNaN(x)) continue;
                        if (x > bv) { bv = x; bs = si; bx = xi; }
                        if (x < wv) { wv = x; ws = si; wx = xi; }
                    }
                if (bv > Double.NEGATIVE_INFINITY)
                    r.facts.add("Highest: " + xs.get(bx) + " / " + ss.get(bs) + " at " + Fmt.full(bv) + ". Lowest: " + xs.get(wx) + " / " + ss.get(ws) + " at " + Fmt.full(wv) + filt + ".");
                return;
            }
            int n = v.length;
            if (n == 0) return;
            int hi = 0, lo = 0;
            double total = 0;
            for (int i = 0; i < n; i++) {
                if (v[i] > v[hi]) hi = i;
                if (v[i] < v[lo]) lo = i;
                total += nz0(v[i]);
            }
            boolean add = additive(agg);
            if (timeX) {
                r.facts.add(measure + " peaked in " + xs.get(hi) + " at " + Fmt.full(v[hi]) + " and was lowest in " + xs.get(lo) + " at " + Fmt.full(v[lo]) + filt + ".");
                if (n >= 2 && v[0] != 0)
                    r.facts.add("From " + xs.get(0) + " to " + xs.get(n - 1) + " it moved " + Fmt.pct((v[n - 1] - v[0]) / Math.abs(v[0])) + " (" + Fmt.full(v[0]) + " to " + Fmt.full(v[n - 1]) + ").");
                if (n >= 3) {
                    int worst = -1;
                    double wd = 0;
                    for (int i = 1; i < n; i++) {
                        double d = v[i] - v[i - 1];
                        if (d < wd) {
                            wd = d;
                            worst = i;
                        }
                    }
                    if (worst > 0 && v[worst - 1] != 0)
                        r.facts.add("The sharpest period-on-period drop was " + xs.get(worst - 1) + " to " + xs.get(worst) + " (" + Fmt.pct(wd / Math.abs(v[worst - 1])) + "). Ask \"what's driving the drop in " + xs.get(worst) + "?\" to dig in.");
                }
            } else {
                if (add && total != 0 && n > 1) {
                    r.facts.add(measure + " totals " + Fmt.full(total) + " across the " + n + " " + xc.name + " values shown" + filt + ".");
                    r.facts.add(xs.get(hi) + " is highest at " + Fmt.full(v[hi]) + " (" + Fmt.share(v[hi] / total) + " of the total); " + xs.get(lo) + " is lowest at " + Fmt.full(v[lo]) + " (" + Fmt.share(v[lo] / total) + ").");
                } else if (n > 1) {
                    r.facts.add(xs.get(hi) + " has the highest value (" + Fmt.full(v[hi]) + ") and " + xs.get(lo) + " the lowest (" + Fmt.full(v[lo]) + ")" + filt + ".");
                } else {
                    r.facts.add(measure + " for " + xs.get(0) + " is " + Fmt.full(v[0]) + filt + ".");
                }
            }
        }

        // ------------------------------------------------------------ drivers
        static String matchLabel(List<String> labels, String want) {
            if (want == null) return null;
            String w = want.trim();
            for (String l : labels) if (l.equals(w)) return l;
            for (String l : labels) if (l.equalsIgnoreCase(w)) return l;
            String lw = w.toLowerCase(Locale.ROOT);
            if (lw.length() >= 3) {
                int mi = "janfebmaraprmayjunjulaugsepoctnovdec".indexOf(lw.substring(0, 3));
                if (mi >= 0 && mi % 3 == 0) {
                    String suffix = String.format("-%02d", mi / 3 + 1);
                    String found = null;
                    for (String l : labels) if (l.endsWith(suffix) && l.length() == 7) found = l;
                    if (found != null) return found;
                }
            }
            for (String l : labels) if (l.startsWith(w)) return l;
            for (String l : labels) if (l.contains(w)) return l;
            return null;
        }

        static void drivers(DataTable t, Plan p, boolean[] keep, Result r) throws AnalysisException {
            Column m = metricOf(t, p);
            String agg = aggOf(p, m);
            if (!agg.equals("sum") && !agg.equals("count") && !agg.equals("avg")) agg = "sum";
            Column tc = p.x != null ? t.find(p.x) : null;
            if (tc == null || tc.type != ColType.DATE) tc = t.firstOfType(ColType.DATE);
            if (tc == null)
                throw new AnalysisException("Finding what drives a change needs a date column, and this table doesn't have one. Try a grouped question instead, e.g. \"total by region\".");
            String bucket = bucketOf(p.bucket, tc, keep);
            boolean add = additive(agg);
            String measure = measureLabel(agg, m);

            TreeMap<String, Acc> byB = new TreeMap<>();
            for (int i = 0; i < t.rows; i++) {
                if (!keep[i]) continue;
                String lab = key(tc, i, bucket);
                if (lab == null) continue;
                double v = 1;
                if (m != null) {
                    v = m.num[i];
                    if (Double.isNaN(v)) continue;
                }
                byB.computeIfAbsent(lab, k -> new Acc()).add(v, false);
            }
            if (byB.size() < 2) throw new AnalysisException("I need at least two " + bucket + "s of data to compare.");
            List<String> labels = new ArrayList<>(byB.keySet());

            String to = matchLabel(labels, p.to), from = matchLabel(labels, p.from);
            if (p.to != null && to == null) throw new AnalysisException("I couldn't find the period \"" + p.to + "\". Periods in the data: " + labels + ".");
            if (p.from != null && from == null) throw new AnalysisException("I couldn't find the period \"" + p.from + "\". Periods in the data: " + labels + ".");
            if (to != null && from == null) {
                int ix = labels.indexOf(to);
                if (ix == 0) throw new AnalysisException(to + " is the first period in the data, so there is nothing earlier to compare it with.");
                from = labels.get(ix - 1);
            } else if (from != null && to == null) {
                int ix = labels.indexOf(from);
                if (ix == labels.size() - 1) throw new AnalysisException(from + " is the last period in the data.");
                to = labels.get(ix + 1);
            } else if (from == null) {
                boolean rise = "rise".equals(p.direction);
                double bestD = rise ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
                for (int k = 1; k < labels.size(); k++) {
                    double d = byB.get(labels.get(k)).get(agg) - byB.get(labels.get(k - 1)).get(agg);
                    if (rise ? d > bestD : d < bestD) {
                        bestD = d;
                        from = labels.get(k - 1);
                        to = labels.get(k);
                    }
                }
            }
            final String fromL = from, toL = to;
            double vFrom = byB.get(fromL).get(agg), vTo = byB.get(toL).get(agg);
            double totalDelta = vTo - vFrom;
            double sign = totalDelta >= 0 ? 1 : -1;

            // dimensions
            List<Column> dims = new ArrayList<>();
            for (Column c : t.cols) {
                if (c.type != ColType.TEXT) continue;
                int dc = c.distinctCount();
                if (dc >= 2 && dc <= 40 && dc <= Math.max(12, t.rows * 0.5)) dims.add(c);
            }
            List<Map<String, Acc[]>> single = new ArrayList<>();
            for (int d = 0; d < dims.size(); d++) single.add(new HashMap<>());
            List<Integer> cd = new ArrayList<>();
            for (int d = 0; d < dims.size(); d++) if (dims.get(d).distinctCount() <= 12) cd.add(d);
            Map<String, Acc[]> combo = new HashMap<>();
            for (int i = 0; i < t.rows; i++) {
                if (!keep[i]) continue;
                String lab = key(tc, i, bucket);
                if (lab == null) continue;
                int side = lab.equals(fromL) ? 0 : (lab.equals(toL) ? 1 : -1);
                if (side < 0) continue;
                double v = 1;
                if (m != null) {
                    v = m.num[i];
                    if (Double.isNaN(v)) continue;
                }
                for (int d = 0; d < dims.size(); d++) {
                    String val = dims.get(d).text[i];
                    if (val == null || val.isBlank()) continue;
                    Acc[] a = single.get(d).computeIfAbsent(val.trim(), k -> new Acc[2]);
                    if (a[side] == null) a[side] = new Acc();
                    a[side].add(v, false);
                }
                if (add) {
                    for (int x = 0; x < cd.size(); x++)
                        for (int y = x + 1; y < cd.size(); y++) {
                            String va = dims.get(cd.get(x)).text[i], vb = dims.get(cd.get(y)).text[i];
                            if (va == null || va.isBlank() || vb == null || vb.isBlank()) continue;
                            String k = cd.get(x) + "\u0001" + va.trim() + "\u0001" + cd.get(y) + "\u0001" + vb.trim();
                            Acc[] a = combo.computeIfAbsent(k, kk -> new Acc[2]);
                            if (a[side] == null) a[side] = new Acc();
                            a[side].add(v, false);
                        }
                }
            }
            double ratio = vFrom != 0 ? vTo / vFrom : Double.NaN;
            List<DriverRow> singles = new ArrayList<>(), combos = new ArrayList<>();
            for (int d = 0; d < dims.size(); d++)
                for (var e : single.get(d).entrySet()) {
                    DriverRow dr = driverRow(1, dims.get(d).name + " = " + e.getKey(), e.getValue(), agg, add, totalDelta, ratio);
                    if (dr != null) singles.add(dr);
                }
            Map<String, DriverRow> singleMap = new HashMap<>();
            for (DriverRow d : singles) singleMap.put(d.label(), d);
            for (var e : combo.entrySet()) {
                String[] parts = e.getKey().split("\u0001");
                String la = dims.get(Integer.parseInt(parts[0])).name + " = " + parts[1];
                String lb = dims.get(Integer.parseInt(parts[2])).name + " = " + parts[3];
                DriverRow dr = driverRow(2, la + " & " + lb, e.getValue(), agg, add, totalDelta, ratio);
                if (dr == null || dr.from() == 0) continue;
                // keep a combination only if it moved clearly more (in %) than BOTH of its parts: it adds information
                DriverRow da = singleMap.get(la), db = singleMap.get(lb);
                double pc = dr.delta() / Math.abs(dr.from()) * sign;
                boolean sharper = true;
                for (DriverRow part : new DriverRow[]{da, db})
                    if (part != null && part.from() != 0 && pc < part.delta() / Math.abs(part.from()) * sign + 0.10) sharper = false;
                if (sharper) combos.add(dr);
            }
            Comparator<DriverRow> byScore = Comparator.comparingDouble((DriverRow d) -> d.excess() * sign).reversed();
            singles.sort(byScore);
            combos.sort(byScore);
            double thresh = Math.abs(totalDelta);
            int limit = p.limit != null ? p.limit : 8;
            List<DriverRow> main = new ArrayList<>(), offsets = new ArrayList<>(), pairs = new ArrayList<>();
            for (DriverRow d : singles) if (d.excess() * sign >= 0.03 * thresh && d.excess() * sign > 0 && main.size() < limit) main.add(d);
            for (DriverRow d : combos) if (d.excess() * sign >= 0.10 * thresh && pairs.size() < 4) pairs.add(d);
            for (int i = singles.size() - 1; i >= 0 && offsets.size() < 2; i--) {
                DriverRow d = singles.get(i);
                if (d.excess() * sign < 0 && Math.abs(d.excess()) >= 0.05 * thresh) offsets.add(d);
            }

            // table
            r.title = p.title != null ? p.title : "What changed in " + measure.toLowerCase(Locale.ROOT) + ": " + fromL + " to " + toL;
            r.headers = new ArrayList<>(List.of("Segment", fromL, toL, "Change", "Change %", "Share of change"));
            r.rows.add(new String[]{"TOTAL", Fmt.full(vFrom), Fmt.full(vTo), Fmt.signed(totalDelta), Fmt.pct(vFrom == 0 ? Double.NaN : totalDelta / Math.abs(vFrom)), "100%"});
            List<DriverRow> shown = new ArrayList<>(main);
            shown.addAll(pairs);
            shown.addAll(offsets);
            for (DriverRow d : shown)
                r.rows.add(new String[]{(d.level() == 2 ? "[combo] " : "") + d.label(), Fmt.full(d.from()), Fmt.full(d.to()), Fmt.signed(d.delta()),
                        Fmt.pct(d.from() == 0 ? Double.NaN : d.delta() / Math.abs(d.from())), add ? Fmt.share(d.share()) : ""});

            // chart 1: the trend with both periods highlighted
            ChartData trend = new ChartData();
            trend.title = measure + " by " + bucket;
            trend.yLabel = measure;
            trend.type = labels.size() > 24 ? ChartType.LINE : ChartType.BAR;
            double[] tv = new double[labels.size()];
            for (int i = 0; i < labels.size(); i++) tv[i] = byB.get(labels.get(i)).get(agg);
            trend.labels = labels;
            trend.series = List.of(measure);
            trend.values.add(tv);
            trend.highlight.add(labels.indexOf(fromL));
            trend.highlight.add(labels.indexOf(toL));
            r.charts.add(trend);

            // chart 2: contributions
            ChartData bars = new ChartData();
            bars.type = ChartType.HBAR;
            bars.signColors = true;
            bars.title = "Change in " + measure.toLowerCase(Locale.ROOT) + ", " + fromL + " to " + toL + ", by segment";
            List<DriverRow> plot = new ArrayList<>(main.subList(0, Math.min(main.size(), 7)));
            plot.addAll(pairs.subList(0, Math.min(pairs.size(), 2)));
            plot.addAll(offsets);
            double[] dv = new double[plot.size()];
            for (int i = 0; i < plot.size(); i++) {
                bars.labels.add(plot.get(i).label());
                dv[i] = plot.get(i).delta();
            }
            bars.series = List.of("Change");
            bars.values.add(dv);
            if (!plot.isEmpty()) r.charts.add(bars);

            // facts
            r.facts.add(measure + " " + (totalDelta < 0 ? "fell " : "rose ") + Fmt.pct(vFrom == 0 ? Double.NaN : Math.abs(totalDelta) / Math.abs(vFrom)).replace("+", "")
                    + " from " + fromL + " (" + Fmt.full(vFrom) + ") to " + toL + " (" + Fmt.full(vTo) + "), a change of " + Fmt.signed(totalDelta) + ".");
            if (m != null && agg.equals("sum")) {
                double n0 = byB.get(fromL).n, n1 = byB.get(toL).n;
                double a0 = n0 == 0 ? 0 : vFrom / n0, a1 = n1 == 0 ? 0 : vTo / n1;
                r.facts.add("Row count went from " + Fmt.full(n0) + " to " + Fmt.full(n1) + " (" + Fmt.pct(n0 == 0 ? Double.NaN : (n1 - n0) / n0)
                        + ") while the average value per row went from " + Fmt.full(a0) + " to " + Fmt.full(a1) + " (" + Fmt.pct(a0 == 0 ? Double.NaN : (a1 - a0) / a0) + ").");
            }
            if (!main.isEmpty()) {
                StringBuilder sb = new StringBuilder("Biggest drivers (segments that moved more than the overall trend): ");
                for (int i = 0; i < Math.min(3, main.size()); i++) {
                    DriverRow d = main.get(i);
                    sb.append(i > 0 ? "; " : "").append(d.label()).append(" (").append(Fmt.signed(d.delta()));
                    if (d.from() != 0) sb.append(", ").append(Fmt.pct(d.delta() / Math.abs(d.from())));
                    if (add && !Double.isNaN(d.share())) sb.append(", ").append(Fmt.share(d.share())).append(" of the total change");
                    sb.append(")");
                }
                r.facts.add(sb + ".");
            } else {
                r.facts.add("No single segment stands out: the change is spread evenly across the data.");
            }
            if (!pairs.isEmpty())
                r.facts.add("The sharpest combination is " + pairs.get(0).label() + ", which moved " + Fmt.signed(pairs.get(0).delta())
                        + (pairs.get(0).from() != 0 ? " (" + Fmt.pct(pairs.get(0).delta() / Math.abs(pairs.get(0).from())) + ")" : "") + ".");
            if (!offsets.isEmpty()) {
                StringBuilder sb = new StringBuilder("Lagging the trend or moving the other way: ");
                for (int i = 0; i < offsets.size(); i++)
                    sb.append(i > 0 ? "; " : "").append(offsets.get(i).label()).append(" (").append(Fmt.signed(offsets.get(i).delta())).append(")");
                r.facts.add(sb + ".");
            }
            if (add) r.facts.add("Shares are computed within each dimension separately, so they can overlap across dimensions.");
            if (bucket.equals("month") && toL.equals(labels.get(labels.size() - 1))) {
                LocalDate mx = tc.dmax;
                if (mx != null && bucketLabel(mx, "month").equals(toL) && mx.getDayOfMonth() < mx.lengthOfMonth())
                    r.facts.add("Careful: the data for " + toL + " stops on " + mx + ", so that month may be incomplete.");
            }
        }

        /**
         * "excess" = how much the segment moved beyond what its size alone would explain if it had moved
         * in line with the overall trend. This stops huge, stable segments (e.g. Status = Completed) from
         * topping the list just because they are big.
         */
        static DriverRow driverRow(int level, String label, Acc[] a, String agg, boolean add, double totalDelta, double ratio) {
            double f = a[0] == null ? (add ? 0 : Double.NaN) : a[0].get(agg);
            double t = a[1] == null ? (add ? 0 : Double.NaN) : a[1].get(agg);
            if (Double.isNaN(f) || Double.isNaN(t)) return null;
            double d = t - f;
            if (d == 0) return null;
            double excess = add && !Double.isNaN(ratio) && !Double.isInfinite(ratio) ? t - f * ratio : d;
            return new DriverRow(level, label, f, t, d, excess, add && totalDelta != 0 ? d / totalDelta : Double.NaN);
        }
    }

    // =====================================================================================
    //  Offline planner: rule-based natural-language -> Plan (used in demo / no-API mode)
    // =====================================================================================
    static final class LocalPlanner {
        static final Set<String> STOP = Set.of("the", "and", "id", "of", "for", "per");
        static final String MONTH_RE = "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)";
        static final Pattern MONTH_P = Pattern.compile("(?<= )(" + MONTH_RE + ")(?= )");
        static final Pattern QUARTER_P = Pattern.compile("(?<= )q([1-4])(?= )");
        static final Pattern YEAR_P = Pattern.compile("(?<= )(20\\d\\d|19\\d\\d)(?= )");

        static String norm(String s) { return " " + s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim() + " "; }

        static boolean has(String qn, String regex) { return Pattern.compile(regex).matcher(qn).find(); }

        static int monthOf(String s) { return "janfebmaraprmayjunjulaugsepoctnovdec".indexOf(s.substring(0, 3)) / 3 + 1; }

        /** Position of a column mention in the (normalised) question, or -1. Matches the full name or its last word. */
        static int mention(String qn, String name) {
            String full = norm(name).trim();
            List<String> cands = new ArrayList<>();
            cands.add(full);
            String[] words = full.split(" ");
            String last = words[words.length - 1];
            if (words.length > 1 && last.length() >= 3 && !STOP.contains(last)) cands.add(last);
            int best = -1;
            for (String c : cands) {
                String[] forms = {c, c + "s", c + "es", c.endsWith("s") ? c.substring(0, c.length() - 1) : c,
                        c.endsWith("y") ? c.substring(0, c.length() - 1) + "ies" : c};
                for (String f : forms) {
                    int ix = qn.indexOf(" " + f + " ");
                    if (ix >= 0 && (best < 0 || ix < best)) best = ix;
                }
            }
            return best;
        }

        static Plan plan(String question, DataTable t) {
            String qn = norm(question);
            Plan p = new Plan();
            Column time = t.firstOfType(ColType.DATE);
            List<Column> nums = t.colsOfType(ColType.NUMBER);
            List<Column> cats = new ArrayList<>();
            for (Column c : t.cols) {
                int dc = c.type == ColType.TEXT ? c.distinctCount() : 0;
                if (c.type == ColType.TEXT && dc >= 2 && dc <= 200 && (dc <= 12 || dc <= 0.6 * t.rows)) cats.add(c);
            }

            if (has(qn, " (describe|overview|profile|summari[sz]e (the |this )?(data|dataset|file|csv|table)|what columns|which columns|what is in (this|the) (data|file|csv)|about this data|data summary) ")) {
                p.intent = "describe";
                return p;
            }

            // ---- value filters
            for (Column c : cats) {
                List<String> inc = new ArrayList<>(), exc = new ArrayList<>();
                for (String v : c.distinct()) {
                    String nv = norm(v).trim();
                    if (nv.length() < 2 || nv.length() > 40) continue;
                    String stem = nv.length() >= 7 ? nv.substring(0, nv.length() - 3) : nv;
                    String body = Pattern.quote(stem) + (nv.length() >= 7 ? "[a-z0-9]*" : "(?:s|es)?");
                    if (!Pattern.compile(" " + body + " ").matcher(qn).find()) continue;
                    if (Pattern.compile("(?:excluding|exclude|without|except|ignoring|ignore|not|no|minus)\\s+(?:the\\s+|any\\s+)?" + body + " ").matcher(qn).find()) exc.add(v);
                    else inc.add(v);
                }
                if (!inc.isEmpty()) p.filters.add(new Filter(c.name, inc.size() == 1 ? "=" : "in", inc));
                if (!exc.isEmpty()) p.filters.add(new Filter(c.name, exc.size() == 1 ? "!=" : "not in", exc));
            }
            Set<String> filteredCols = new HashSet<>();
            for (Filter f : p.filters) filteredCols.add(f.column());

            // ---- periods mentioned (months, quarters, years)
            List<Integer> months = new ArrayList<>(), quarters = new ArrayList<>();
            Matcher mm = MONTH_P.matcher(qn);
            while (mm.find()) months.add(monthOf(mm.group(1)));
            Matcher qm = QUARTER_P.matcher(qn);
            while (qm.find()) quarters.add(Integer.parseInt(qm.group(1)));
            Matcher ym = YEAR_P.matcher(qn);
            Integer askedYear = ym.find() ? Integer.parseInt(ym.group(1)) : null;

            // ---- intent
            boolean periodPair = months.size() + quarters.size() >= 2;
            boolean change = time != null && has(qn, " (drop|dropped|drops|decline|declined|declining|fall|fell|fallen|falling|decrease|decreased|dip|dipped|slump|slumped|"
                    + "increase|increased|spike|spiked|jump|jumped|surge|surged|rise|rose|grew|why|driving|driver|drivers|drove|cause|caused|reason|reasons|behind|explain|what changed|went down|went up"
                    + (periodPair ? "|compare|compared|versus|vs|difference|differ" : "") + ") ");
            boolean rise = has(qn, " (increase|increased|spike|spiked|jump|jumped|surge|surged|rise|rose|grew|went up) ") && !has(qn, " (drop|dropped|decline|declined|fall|fell|dip|dipped|slump|decrease|decreased|went down) ");
            boolean rowsIntent = has(qn, " (largest|biggest|highest|smallest|lowest|top|bottom|latest|earliest|newest|oldest) ")
                    && has(qn, " (rows|records|transactions|orders|entries|items|individual|single|lines) ")
                    && !has(qn, " (by|per|each|across) ");

            // ---- measure + aggregation
            Column metric = null;
            boolean wantCount = has(qn, " (how many|count|number of|no of|how often|frequency) ");
            int bestPos = Integer.MAX_VALUE;
            for (Column c : nums) {
                int pos = mention(qn, c.name);
                if (pos >= 0 && pos < bestPos) {
                    bestPos = pos;
                    metric = c;
                }
            }
            boolean mentioned = metric != null;
            if (metric == null && !nums.isEmpty()) {
                for (Column c : nums)
                    if (c.name.toLowerCase(Locale.ROOT).matches(".*(amount|revenue|sales|total|price|value|cost|profit|income|spend|payment).*")) {
                        metric = c;
                        break;
                    }
                if (metric == null) metric = nums.get(0);
            }
            String agg = "sum";
            if (wantCount && !mentioned) agg = "count";
            else if (has(qn, " (average|avg|mean|typical) ")) agg = "avg";
            else if (has(qn, " median ")) agg = "median";
            else if (has(qn, " (maximum|max|peak) ")) agg = "max";
            else if (has(qn, " (minimum|min) ")) agg = "min";
            if (agg.equals("count") || metric == null) {
                metric = null;
                agg = "count";
            }
            p.metric = metric == null ? null : metric.name;
            p.agg = agg;
            if (metric != null && !mentioned && nums.size() > 1)
                p.message = "I used \"" + metric.name + "\" as the measure since you didn't name one.";

            // ---- time granularity
            String bucket = null;
            if (has(qn, " (daily|per day|by day|each day|every day|day by day) ")) bucket = "day";
            else if (has(qn, " (weekly|per week|by week|each week|every week) ")) bucket = "week";
            else if (has(qn, " (monthly|per month|by month|each month|every month|month by month|month over month) ")) bucket = "month";
            else if (has(qn, " (quarterly|per quarter|by quarter|each quarter) ")) bucket = "quarter";
            else if (has(qn, " (yearly|annual|annually|per year|by year|each year|year over year) ")) bucket = "year";
            if (has(qn, " (weekday|weekdays|day of the week|day of week|days of the week|by weekday|each weekday) ")) bucket = "weekday";
            boolean timeWord = bucket != null || has(qn, " (over time|trend|trends|timeline|history|evolution|time series|across time) ");

            // ---- sort / limit
            Matcher lm = Pattern.compile(" (top|bottom|best|worst|highest|lowest|first|last) (\\d+) ").matcher(qn);
            if (lm.find()) {
                p.limit = Integer.parseInt(lm.group(2));
                p.sort = has(" " + lm.group(1) + " ", " (bottom|worst|lowest) ") ? "asc" : "desc";
            } else if (has(qn, " top ")) {
                p.limit = 5;
                p.sort = "desc";
            } else if (has(qn, " (worst|bottom|lowest|least|smallest|weakest) ")) {
                p.sort = "asc";
            } else if (has(qn, " (best|highest|most|biggest|largest|strongest) ")) {
                p.sort = "desc";
            }

            // ---- chart type
            if (has(qn, " (pie|donut|doughnut|share|shares|proportion|proportions|percentage|percentages|composition|split) ")) p.chart = "pie";
            else if (has(qn, " (line|lines|trend|trends|over time) ")) p.chart = "line";
            else if (has(qn, " (bar|bars|column|columns|histogram) ")) p.chart = "bar";

            // ---- drivers
            if (change && !rowsIntent) {
                p.intent = "drivers";
                p.x = time.name;
                p.direction = rise ? "rise" : "drop";
                p.chart = "auto";
                p.limit = null;
                p.sort = "none";
                List<String> labels = new ArrayList<>();
                if (!quarters.isEmpty()) {
                    p.bucket = "quarter";
                    for (int q : quarters) labels.add(pickYear(time, askedYear, (q - 1) * 3 + 1) + "-Q" + q);
                } else if (!months.isEmpty()) {
                    p.bucket = bucket != null ? bucket : "month";
                    for (int mo : months) labels.add(String.format("%04d-%02d", pickYear(time, askedYear, mo), mo));
                } else {
                    p.bucket = bucket;
                }
                Collections.sort(labels);
                if (labels.size() >= 2) {
                    p.from = labels.get(0);
                    p.to = labels.get(labels.size() - 1);
                } else if (labels.size() == 1) p.to = labels.get(0);
                return p;
            }

            // ---- date window for ordinary questions
            if (time != null && !periodPair && (!months.isEmpty() || !quarters.isEmpty() || askedYear != null)) {
                String lo, hi;
                if (!months.isEmpty()) {
                    int mo = months.get(0), y = pickYear(time, askedYear, mo);
                    lo = hi = String.format("%04d-%02d", y, mo);
                } else if (!quarters.isEmpty()) {
                    int q = quarters.get(0), y = pickYear(time, askedYear, (q - 1) * 3 + 1);
                    lo = String.format("%04d-%02d", y, (q - 1) * 3 + 1);
                    hi = String.format("%04d-%02d", y, q * 3);
                } else {
                    lo = hi = String.valueOf(askedYear);
                }
                p.filters.add(new Filter(time.name, ">=", List.of(lo)));
                p.filters.add(new Filter(time.name, "<=", List.of(hi)));
            }

            if (rowsIntent) {
                p.intent = "rows";
                p.chart = "none";
                if (p.sort.equals("none")) p.sort = "desc";
                return p;
            }

            // ---- grouping columns
            List<Column> cand = new ArrayList<>(cats);
            if (time != null) cand.add(time);
            int cue = -1;
            Matcher cm = Pattern.compile(" (by|per|across|each|against|versus|vs|split by|grouped by|broken down by|breakdown by|segmented by|for each|in each) ").matcher(qn);
            if (cm.find()) cue = cm.start();
            List<Column> chosen = new ArrayList<>();
            List<int[]> hits = new ArrayList<>();
            for (int i = 0; i < cand.size(); i++) {
                Column c = cand.get(i);
                if (c == metric) continue;
                int pos = mention(qn, c.name);
                if (pos < 0) continue;
                boolean afterCue = cue >= 0 && pos > cue;
                if (!afterCue && filteredCols.contains(c.name)) continue;
                hits.add(new int[]{afterCue ? 0 : 1, pos, i});
            }
            hits.sort(Comparator.<int[]>comparingInt(h -> h[0]).thenComparingInt(h -> h[1]));
            for (int[] h : hits) chosen.add(cand.get(h[2]));

            Column x = null, series = null;
            boolean timeRequested = time != null && (timeWord || chosen.contains(time));
            if (timeRequested) {
                x = time;
                for (Column c : chosen) if (c != time) { series = c; break; }
                p.bucket = bucket;
            } else if (!chosen.isEmpty()) {
                x = chosen.get(0);
                if (chosen.size() > 1) series = chosen.get(1);
                if (x.type == ColType.DATE) p.bucket = bucket;
            }
            p.x = x == null ? null : x.name;
            p.series = series == null ? null : series.name;
            if (x != null && series != null && series.type == ColType.DATE) p.bucket = bucket;
            if (x == null && (p.chart.equals("pie") || p.chart.equals("bar") || p.chart.equals("line"))) {
                // user wants a chart but named no grouping: pick the most useful default
                if (!cats.isEmpty()) {
                    p.x = cats.get(0).name;
                    p.message = (p.message == null ? "" : p.message + " ") + "You didn't say how to group, so I grouped by \"" + p.x + "\".";
                }
            }
            if (x == null && p.x == null) p.chart = "none";
            return p;
        }

        /** Most recent year in the data that contains the given month (or the explicitly asked year). */
        static int pickYear(Column time, Integer asked, int month) {
            if (asked != null) return asked;
            if (time == null || time.dmax == null) return LocalDate.now().getYear();
            for (int y = time.dmax.getYear(); y >= time.dmin.getYear(); y--) {
                LocalDate d = LocalDate.of(y, month, 1);
                if (!d.isBefore(time.dmin.withDayOfMonth(1)) && !d.isAfter(time.dmax)) return y;
            }
            return time.dmax.getYear();
        }

        static String narrate(Plan p, Result r) {
            if (r.facts.isEmpty()) return "Here is the result.";
            return String.join(" ", r.facts);
        }
    }

    // =====================================================================================
    //  Claude (Anthropic Messages API) - plans queries and writes the plain-English answer
    // =====================================================================================
    static final class Claude {
        static final String PLAN_SYSTEM = """
                You are the query planner inside a desktop data-analysis app. The user has loaded a CSV table and asks questions in plain English. \
                You do NOT compute answers yourself: you translate the question into a JSON analysis plan; the app executes it on the full data and draws the chart.

                Reply with ONE JSON object and nothing else (no prose, no markdown fences). Fields:
                {
                 "intent": "aggregate" | "drivers" | "rows" | "describe",
                 "title": short chart title,
                 "metric": exact numeric column name, or null to count rows,
                 "agg": "sum" | "avg" | "count" | "min" | "max" | "median",
                 "x": exact column name for the x-axis / grouping, or null for one overall number,
                 "bucket": "day" | "weekday" | "week" | "month" | "quarter" | "year" | null  (only when x or series is a date column; "weekday" = Mon..Sun),
                 "series": exact text column to split into several series, or null,
                 "filters": [ {"column": "...", "op": "=" | "!=" | ">" | "<" | ">=" | "<=" | "contains" | "in" | "not in", "value": "..." or ["a","b"] for in / not in} ],
                 "sort": "asc" | "desc" | "none",
                 "limit": integer or null,
                 "chart": "bar" | "line" | "pie" | "none",
                 "from": period label or null, "to": period label or null, "direction": "drop" | "rise",
                 "message": optional one-sentence note about an assumption you made
                }
                Guidance:
                - "aggregate": group + aggregate, drawn as a chart. Time on x => line; categories => bar; shares/proportions => pie (single series, at most 10 categories).
                - "drivers": use for "why did X drop/rise/change", "what is driving ...". Set x to the date column, bucket to the period size, and from/to to period labels \
                (month "2026-03", quarter "2026-Q1", week "2026-W09", year "2026", day "2026-03-15"). If the user names one period, set only "to" (the app compares it with the previous period). \
                If no period is named, omit both and set "direction". The app ranks which segments of every text column explain the change, so do not add filters for the segments.
                - "rows": list individual rows ("the ten biggest transactions"): metric = column to sort by, limit = how many, sort desc/asc, chart "none".
                - "describe": overview of all columns.
                - Use exact column names, and for text filters exact values from the column summary. Date filters use ISO text ("2026-03" or "2026-03-15") with ">=" / "<=" / "=".
                - When the user does not name a measure, pick the most natural numeric column (money-like columns for revenue, sales, spend, transactions) with agg "sum".
                - If the question refers to the previous question ("now split that by channel", "only North"), start from the previous plan and modify it.
                - If the question cannot be answered from this table, return the closest sensible plan and say so in "message".
                """;

        static final String NARRATE_SYSTEM = """
                You are a sharp, friendly data analyst. The app has already computed the result below from the user's full dataset. \
                Answer the user's question in plain English in 2-5 sentences, leading with the answer. Quote concrete numbers that appear in the result; never invent numbers. \
                For driver analyses, name the main contributors and how much of the change they explain, and mention that shares overlap across dimensions. \
                Mention assumptions noted in the plan. If useful, end with one suggested follow-up question. Use plain text: no headings, no bullet lists, at most a little **bold**.
                """;

        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        private final String apiKey, model;

        Claude(String apiKey, String model) {
            this.apiKey = apiKey;
            this.model = model;
        }

        @SuppressWarnings("unchecked")
        Plan plan(String question, DataTable t, List<String[]> history, boolean samples) throws Exception {
            StringBuilder u = new StringBuilder(t.describe(samples)).append("\n");
            if (!history.isEmpty()) {
                u.append("Previous turns (oldest first):\n");
                for (String[] h : history.subList(Math.max(0, history.size() - 3), history.size()))
                    u.append("Q: ").append(h[0]).append("\nPlan: ").append(h[1]).append("\n");
                u.append("\n");
            }
            u.append("Today's date: ").append(LocalDate.now()).append("\nQuestion: ").append(question);
            String text = complete(PLAN_SYSTEM, u.toString(), 1200);
            int a = text.indexOf('{'), b = text.lastIndexOf('}');
            if (a < 0 || b < a) throw new IOException("the model did not return a plan");
            return Plan.fromJson((Map<String, Object>) Json.parse(text.substring(a, b + 1)));
        }

        String narrate(String question, Plan plan, Result r) throws Exception {
            StringBuilder u = new StringBuilder();
            u.append("Question: ").append(question).append("\n\nPlan executed: ").append(Json.write(plan.toJson()))
                    .append("\nRows used: ").append(r.matched).append(" of ").append(r.total).append("\n\nComputed facts:\n");
            for (String f : r.facts) u.append("- ").append(f).append("\n");
            u.append("\nResult table (tab separated):\n").append(r.toText(30));
            return complete(NARRATE_SYSTEM, u.toString(), 700).trim();
        }

        @SuppressWarnings("unchecked")
        String complete(String system, String user, int maxTokens) throws IOException, InterruptedException {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", maxTokens);
            body.put("system", system);
            body.put("messages", List.of(Map.of("role", "user", "content", user)));
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                    .timeout(Duration.ofSeconds(90))
                    .header("content-type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Object parsed;
            try {
                parsed = Json.parse(resp.body());
            } catch (RuntimeException e) {
                throw new IOException("HTTP " + resp.statusCode() + ": " + resp.body());
            }
            Map<String, Object> m = (Map<String, Object>) parsed;
            if (resp.statusCode() / 100 != 2) {
                Object err = m.get("error");
                String msg = err instanceof Map<?, ?> em && em.get("message") != null ? em.get("message").toString() : resp.body();
                throw new IOException("HTTP " + resp.statusCode() + ": " + msg);
            }
            StringBuilder sb = new StringBuilder();
            if (m.get("content") instanceof List<?> blocks)
                for (Object o : blocks)
                    if (o instanceof Map<?, ?> bm && "text".equals(bm.get("type"))) sb.append(bm.get("text"));
            return sb.toString();
        }
    }

    // =====================================================================================
    //  Minimal JSON reader / writer (no dependencies)
    // =====================================================================================
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        static Object parse(String s) {
            Json j = new Json(s);
            Object v = j.value();
            j.ws();
            return v;
        }

        private RuntimeException err(String m) { return new IllegalArgumentException("JSON error at " + i + ": " + m); }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private Object value() {
            ws();
            if (i >= s.length()) throw err("unexpected end");
            char c = s.charAt(i);
            switch (c) {
                case '{': return obj();
                case '[': return arr();
                case '"': return str();
                case 't': lit("true"); return Boolean.TRUE;
                case 'f': lit("false"); return Boolean.FALSE;
                case 'n': lit("null"); return null;
                default: return num();
            }
        }

        private void lit(String w) {
            if (!s.startsWith(w, i)) throw err("expected " + w);
            i += w.length();
        }

        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = str();
                ws();
                if (i >= s.length() || s.charAt(i++) != ':') throw err("expected ':'");
                m.put(k, value());
                ws();
                if (i >= s.length()) throw err("unterminated object");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw err("expected ',' or '}'");
            }
        }

        private List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                if (i >= s.length()) throw err("unterminated array");
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw err("expected ',' or ']'");
            }
        }

        private String str() {
            if (i >= s.length() || s.charAt(i) != '"') throw err("expected string");
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) break;
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: sb.append(e);
                    }
                } else sb.append(c);
            }
            throw err("unterminated string");
        }

        private Object num() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw err("unexpected character '" + s.charAt(i) + "'");
            return Double.parseDouble(s.substring(st, i));
        }

        // ---- writer
        static String write(Object o) {
            StringBuilder sb = new StringBuilder();
            w(o, sb, -1, 0);
            return sb.toString();
        }

        static String pretty(Object o) {
            StringBuilder sb = new StringBuilder();
            w(o, sb, 2, 0);
            return sb.toString();
        }

        private static void nl(StringBuilder sb, int ind, int lvl) {
            if (ind < 0) return;
            sb.append('\n').append(" ".repeat(ind * lvl));
        }

        private static void w(Object o, StringBuilder sb, int ind, int lvl) {
            if (o == null) sb.append("null");
            else if (o instanceof String s) quote(s, sb);
            else if (o instanceof Number n) {
                double d = n.doubleValue();
                if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
                else sb.append(d);
            } else if (o instanceof Boolean b) sb.append(b);
            else if (o instanceof Map<?, ?> m) {
                if (m.isEmpty()) { sb.append("{}"); return; }
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    nl(sb, ind, lvl + 1);
                    quote(String.valueOf(e.getKey()), sb);
                    sb.append(ind >= 0 ? ": " : ":");
                    w(e.getValue(), sb, ind, lvl + 1);
                }
                nl(sb, ind, lvl);
                sb.append('}');
            } else if (o instanceof Collection<?> c) {
                if (c.isEmpty()) { sb.append("[]"); return; }
                sb.append('[');
                boolean first = true;
                for (Object e : c) {
                    if (!first) sb.append(',');
                    first = false;
                    nl(sb, ind, lvl + 1);
                    w(e, sb, ind, lvl + 1);
                }
                nl(sb, ind, lvl);
                sb.append(']');
            } else quote(o.toString(), sb);
        }

        private static void quote(String s, StringBuilder sb) {
            sb.append('"');
            for (int k = 0; k < s.length(); k++) {
                char c = s.charAt(k);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            sb.append('"');
        }
    }

    // =====================================================================================
    //  Chart rendering (pure Java2D - bar, horizontal bar, line, pie)
    // =====================================================================================
    static final class ChartPanel extends JPanel {
        record Hit(Shape shape, String tip) {}

        static final Color INK = new Color(0x222222), GRID = new Color(0xE3E3E3), AXIS = new Color(0x888888);
        static final Color POS = new Color(0x2CA58D), NEG = new Color(0xE15759);
        static final Color[] PALETTE = {new Color(0x2E6FDB), new Color(0xF28E2B), new Color(0x2CA58D), new Color(0xE15759),
                new Color(0x8E6BBF), new Color(0xEDC948), new Color(0x76B7B2), new Color(0xFF9DA7), new Color(0x9C755F), new Color(0xBAB0AC)};

        private final ChartData d;
        private final List<Hit> hits = new ArrayList<>();

        ChartPanel(ChartData d) {
            this.d = d;
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(520, 300));
            setBorder(BorderFactory.createLineBorder(new Color(0xDDDDDD)));
            setToolTipText("");
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).shape().contains(e.getPoint())) return hits.get(i).tip();
            return null;
        }

        static Color color(int s) { return PALETTE[s % PALETTE.length]; }

        static String shorten(String s, int n) { return s.length() > n ? s.substring(0, Math.max(1, n - 1)) + "\u2026" : s; }

        static String clip(Graphics2D g, String s, int maxW) {
            FontMetrics fm = g.getFontMetrics();
            if (fm.stringWidth(s) <= maxW) return s;
            while (s.length() > 1 && fm.stringWidth(s + "\u2026") > maxW) s = s.substring(0, s.length() - 1);
            return s + "\u2026";
        }

        static double niceStep(double raw) {
            if (raw <= 0 || Double.isNaN(raw)) return 1;
            double e = Math.floor(Math.log10(raw)), f = raw / Math.pow(10, e);
            double nf = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10;
            return nf * Math.pow(10, e);
        }

        private double[] range(boolean pad) {
            double lo = 0, hi = 0;
            for (double[] row : d.values) for (double v : row) if (!Double.isNaN(v)) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
            if (hi == lo) hi = lo + 1;
            if (pad) {
                double span = hi - lo;
                if (lo < 0) lo -= span * 0.14;
                if (hi > 0) hi += span * 0.14;
            }
            return new double[]{lo, hi};
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            hits.clear();
            int w = getWidth(), h = getHeight();
            Font base = UIManager.getFont("Label.font");
            if (base == null) base = g.getFont();
            base = base.deriveFont(Font.PLAIN, 12f);
            g.setFont(base.deriveFont(Font.BOLD, 14f));
            g.setColor(INK);
            g.drawString(clip(g, d.title, w - 24), 12, 22);
            g.setFont(base);
            if (d.values.isEmpty() || d.labels.isEmpty()) {
                g.dispose();
                return;
            }
            switch (d.type) {
                case PIE -> pie(g, w, h, 34);
                case HBAR -> hbar(g, w, h, 34);
                default -> cartesian(g, w, h, 34);
            }
            g.dispose();
        }

        private int legend(Graphics2D g, int w, int y) {
            if (d.series.size() < 2 || d.type == ChartType.PIE) return y;
            FontMetrics fm = g.getFontMetrics();
            int x = 12, rowH = fm.getHeight() + 4, cy = y;
            for (int s = 0; s < d.series.size(); s++) {
                String name = shorten(d.series.get(s), 24);
                int tw = fm.stringWidth(name);
                if (x + 14 + tw > w - 10 && x > 12) {
                    x = 12;
                    cy += rowH;
                }
                g.setColor(color(s));
                g.fillRect(x, cy + 2, 10, 10);
                g.setColor(INK);
                g.drawString(name, x + 14, cy + fm.getAscent());
                x += 14 + tw + 14;
            }
            return cy + rowH + 4;
        }

        private String tip(int i, int s, double v) {
            String ser = d.series.size() > 1 ? "  [" + d.series.get(s) + "]" : "";
            return d.labels.get(i) + ser + ": " + Fmt.full(v);
        }

        // ---------------------------------------------------------------- vertical bars and lines
        private void cartesian(Graphics2D g, int w, int h, int top0) {
            FontMetrics fm = g.getFontMetrics();
            int n = d.labels.size(), ns = d.values.size();
            int top = legend(g, w, top0);
            double[] rg = range(false);
            double step = niceStep((rg[1] - rg[0]) / 5);
            final double lo = Math.floor(rg[0] / step + 1e-9) * step;
            double hi0 = Math.ceil(rg[1] / step - 1e-9) * step;
            final double hi = hi0 <= lo ? lo + step : hi0;
            int ticks = (int) Math.round((hi - lo) / step);
            int tickW = 0;
            for (int k = 0; k <= ticks; k++) tickW = Math.max(tickW, fm.stringWidth(Fmt.compact(lo + k * step)));
            int left = tickW + 18, right = 16;
            int plotW = w - left - right;
            if (plotW < 40) return;
            double slot = plotW / (double) n;
            int maxLab = 0;
            for (String s : d.labels) maxLab = Math.max(maxLab, fm.stringWidth(shorten(s, 18)));
            boolean rotate = maxLab > slot - 6;
            int bottom = rotate ? Math.min(110, (int) (maxLab * 0.72) + 18) : fm.getHeight() + 12;
            int plotH = h - top - bottom;
            if (plotH < 40) return;
            final int plotY = top;
            java.util.function.DoubleUnaryOperator yOf = v -> plotY + (hi - v) / (hi - lo) * plotH;

            // highlight bands
            g.setColor(new Color(255, 193, 7, 55));
            for (int idx : d.highlight) if (idx >= 0 && idx < n) g.fill(new Rectangle2D.Double(left + idx * slot, plotY, slot, plotH));

            // grid + y labels
            g.setStroke(new BasicStroke(1f));
            for (int k = 0; k <= ticks; k++) {
                double v = lo + k * step;
                int y = (int) Math.round(yOf.applyAsDouble(v));
                g.setColor(GRID);
                g.drawLine(left, y, left + plotW, y);
                g.setColor(AXIS);
                String lab = Fmt.compact(v);
                g.drawString(lab, left - 6 - fm.stringWidth(lab), y + fm.getAscent() / 2 - 1);
            }
            int y0 = (int) Math.round(yOf.applyAsDouble(0));
            g.setColor(AXIS);
            g.drawLine(left, y0, left + plotW, y0);

            if (d.type == ChartType.LINE) {
                for (int s = 0; s < ns; s++) {
                    Path2D.Double path = new Path2D.Double();
                    boolean pen = false;
                    double[] vs = d.values.get(s);
                    for (int i = 0; i < n; i++) {
                        if (Double.isNaN(vs[i])) { pen = false; continue; }
                        double x = left + (i + 0.5) * slot, y = yOf.applyAsDouble(vs[i]);
                        if (!pen) { path.moveTo(x, y); pen = true; } else path.lineTo(x, y);
                    }
                    g.setColor(color(s));
                    g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(path);
                    g.setStroke(new BasicStroke(1f));
                    for (int i = 0; i < n; i++) {
                        if (Double.isNaN(vs[i])) continue;
                        double x = left + (i + 0.5) * slot, y = yOf.applyAsDouble(vs[i]);
                        if (n <= 60) g.fill(new Ellipse2D.Double(x - 3, y - 3, 6, 6));
                        hits.add(new Hit(new Ellipse2D.Double(x - 7, y - 7, 14, 14), tip(i, s, vs[i])));
                    }
                }
            } else {
                double groupW = slot * 0.78, barW = groupW / ns;
                for (int s = 0; s < ns; s++) {
                    double[] vs = d.values.get(s);
                    for (int i = 0; i < n; i++) {
                        double v = vs[i];
                        if (Double.isNaN(v)) continue;
                        double x = left + i * slot + (slot - groupW) / 2 + s * barW;
                        double y = yOf.applyAsDouble(v), yz = yOf.applyAsDouble(0);
                        Rectangle2D.Double rr = new Rectangle2D.Double(x, Math.min(y, yz), Math.max(1, barW - 1), Math.max(1, Math.abs(y - yz)));
                        g.setColor(d.signColors ? (v >= 0 ? POS : NEG) : color(s));
                        g.fill(rr);
                        hits.add(new Hit(rr, tip(i, s, v)));
                    }
                }
            }

            // x labels
            g.setColor(INK);
            int every = rotate ? Math.max(1, (int) Math.ceil((fm.getHeight() + 2) / slot)) : Math.max(1, (int) Math.ceil((maxLab + 8) / slot));
            for (int i = 0; i < n; i += every) {
                String lab = shorten(d.labels.get(i), 18);
                double cx = left + (i + 0.5) * slot;
                int ly = plotY + plotH + 6;
                if (rotate) {
                    Graphics2D gr = (Graphics2D) g.create();
                    gr.translate(cx + 3, ly + 4);
                    gr.rotate(-Math.PI / 4);
                    gr.drawString(lab, -fm.stringWidth(lab), 0);
                    gr.dispose();
                } else {
                    g.drawString(lab, (int) (cx - fm.stringWidth(lab) / 2.0), ly + fm.getAscent());
                }
            }
        }

        // ---------------------------------------------------------------- horizontal bars
        private void hbar(Graphics2D g, int w, int h, int top0) {
            FontMetrics fm = g.getFontMetrics();
            int n = d.labels.size(), ns = d.values.size();
            int top = legend(g, w, top0);
            double[] rg = range(true);
            double step = niceStep((rg[1] - rg[0]) / 5);
            final double lo = Math.floor(rg[0] / step + 1e-9) * step;
            double hi0 = Math.ceil(rg[1] / step - 1e-9) * step;
            final double hi = hi0 <= lo ? lo + step : hi0;
            int ticks = (int) Math.round((hi - lo) / step);
            int maxLab = 0;
            for (String s : d.labels) maxLab = Math.max(maxLab, fm.stringWidth(s));
            int labW = Math.min(Math.max(80, (int) (w * 0.42)), maxLab);
            int left = labW + 16, right = 20;
            int plotW = w - left - right, bottom = fm.getHeight() + 10;
            int plotH = h - top - bottom;
            if (plotW < 40 || plotH < 30) return;
            final int plotX = left;
            java.util.function.DoubleUnaryOperator xOf = v -> plotX + (v - lo) / (hi - lo) * plotW;
            double slot = plotH / (double) n;

            for (int k = 0; k <= ticks; k++) {
                double v = lo + k * step;
                int x = (int) Math.round(xOf.applyAsDouble(v));
                g.setColor(GRID);
                g.drawLine(x, top, x, top + plotH);
                g.setColor(AXIS);
                String lab = Fmt.compact(v);
                g.drawString(lab, x - fm.stringWidth(lab) / 2, top + plotH + 4 + fm.getAscent());
            }
            int x0 = (int) Math.round(xOf.applyAsDouble(0));
            g.setColor(AXIS);
            g.drawLine(x0, top, x0, top + plotH);

            double groupH = Math.min(slot * 0.74, 30 * ns), barH = groupH / ns;
            for (int i = 0; i < n; i++) {
                double cy = top + (i + 0.5) * slot;
                g.setColor(INK);
                String lab = clip(g, d.labels.get(i), labW);
                g.drawString(lab, left - 8 - fm.stringWidth(lab), (int) (cy + fm.getAscent() / 2.0 - 1));
                for (int s = 0; s < ns; s++) {
                    double v = d.values.get(s)[i];
                    if (Double.isNaN(v)) continue;
                    double y = cy - groupH / 2 + s * barH;
                    double xe = xOf.applyAsDouble(v), xz = xOf.applyAsDouble(0);
                    Rectangle2D.Double rr = new Rectangle2D.Double(Math.min(xe, xz), y, Math.max(1, Math.abs(xe - xz)), Math.max(1, barH - 1));
                    g.setColor(d.signColors ? (v >= 0 ? POS : NEG) : color(s));
                    g.fill(rr);
                    hits.add(new Hit(rr, tip(i, s, v)));
                    g.setColor(INK);
                    String vl = Fmt.compact(v);
                    int tw = fm.stringWidth(vl);
                    int tx = v >= 0 ? (int) (xe + 4) : (int) (xe - 4 - tw);
                    g.drawString(vl, tx, (int) (y + barH / 2 + fm.getAscent() / 2.0 - 1));
                }
            }
        }

        // ---------------------------------------------------------------- pie
        private void pie(Graphics2D g, int w, int h, int top) {
            FontMetrics fm = g.getFontMetrics();
            double[] vs = d.values.get(0);
            double sum = 0;
            for (double v : vs) if (!Double.isNaN(v) && v > 0) sum += v;
            if (sum <= 0) return;
            int legendW = Math.min(260, (int) (w / 2.2));
            int size = Math.min(w - legendW - 40, h - top - 20);
            if (size < 60) return;
            int cx = 20, cy = top + (h - top - size) / 2;
            double start = 90;
            for (int i = 0; i < vs.length; i++) {
                if (Double.isNaN(vs[i]) || vs[i] <= 0) continue;
                double ext = -360.0 * vs[i] / sum;
                Arc2D.Double arc = new Arc2D.Double(cx, cy, size, size, start, ext, Arc2D.PIE);
                g.setColor(color(i));
                g.fill(arc);
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(1.5f));
                g.draw(arc);
                hits.add(new Hit(arc, d.labels.get(i) + ": " + Fmt.full(vs[i]) + " (" + Fmt.share(vs[i] / sum) + ")"));
                start += ext;
            }
            int lx = cx + size + 24, ly = top + Math.max(0, (h - top - vs.length * (fm.getHeight() + 6)) / 2);
            for (int i = 0; i < vs.length; i++) {
                if (Double.isNaN(vs[i]) || vs[i] <= 0) continue;
                g.setColor(color(i));
                g.fillRect(lx, ly + 2, 10, 10);
                g.setColor(INK);
                String txt = clip(g, d.labels.get(i), legendW - 70) + "  " + Fmt.share(vs[i] / sum);
                g.drawString(txt, lx + 16, ly + fm.getAscent());
                ly += fm.getHeight() + 6;
            }
        }
    }

    // =====================================================================================
    //  Demo data (synthetic transactions with a planted March slump) and example questions
    // =====================================================================================
    static final class DemoData {
        static DataTable create() {
            Random rnd = new Random(42);
            String[] regions = {"North", "South", "East", "West", "Central"};
            double[] regionW = {1.0, 1.25, 0.9, 1.1, 0.75};
            String[] cats = {"Hardware", "Software", "Services", "Subscriptions"};
            double[] catW = {0.30, 0.25, 0.15, 0.30};
            double[] catMean = {420, 260, 650, 90};
            String[] channels = {"Online", "Retail", "Partner"};
            double[] chanW = {0.50, 0.30, 0.20};
            List<String[]> recs = new ArrayList<>();
            recs.add(new String[]{"TransactionID", "Date", "Region", "Category", "Channel", "Status", "Amount"});
            int id = 10000;
            LocalDate start = LocalDate.of(2026, 1, 1), end = LocalDate.of(2026, 6, 30);
            long span = ChronoUnit.DAYS.between(start, end);
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                double growth = 1 + 0.20 * ChronoUnit.DAYS.between(start, d) / (double) span;
                double dow = d.getDayOfWeek().getValue() >= 6 ? 0.55 : 1.0;
                int n = (int) Math.max(0, Math.round(14 * growth * dow + rnd.nextGaussian() * 2.5));
                for (int k = 0; k < n; k++) {
                    int ri = pick(rnd, regionW), ci = pick(rnd, catW), chi = pick(rnd, chanW);
                    double keepProb = 1;
                    if (d.getMonthValue() == 3) {
                        if (ri == 0) keepProb *= 0.35;      // North: store closures
                        if (ci == 0) keepProb *= 0.70;      // Hardware: supply shortage
                        if (ri == 0 && chi == 1) keepProb *= 0.6;
                    }
                    if (rnd.nextDouble() > keepProb) continue;
                    double sigma = 0.45;
                    double amount = catMean[ci] * Math.exp(sigma * rnd.nextGaussian() - sigma * sigma / 2);
                    double u = rnd.nextDouble();
                    String status = u < 0.04 ? "Refunded" : u < 0.07 ? "Pending" : "Completed";
                    recs.add(new String[]{"T" + (id++), d.toString(), regions[ri], cats[ci], channels[chi], status,
                            String.format(Locale.US, "%.2f", amount)});
                }
            }
            try {
                return DataTable.fromRecords("demo_transactions.csv", recs);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        static int pick(Random rnd, double[] w) {
            double s = 0;
            for (double x : w) s += x;
            double u = rnd.nextDouble() * s;
            for (int i = 0; i < w.length; i++) {
                u -= w[i];
                if (u <= 0) return i;
            }
            return w.length - 1;
        }

        static List<String> examplesFor(DataTable t, boolean demo) {
            if (demo) {
                return List.of("What's driving the drop in March?",
                        "Chart the financial transactions by region",
                        "Monthly revenue by category",
                        "Share of revenue by channel as a pie chart",
                        "Average amount by category, excluding refunded",
                        "Top 5 largest transactions");
            }
            List<String> out = new ArrayList<>();
            out.add("Describe this data");
            Column metric = null;
            for (Column c : t.colsOfType(ColType.NUMBER)) {
                if (metric == null) metric = c;
            }
            Column dim = null, dim2 = null;
            for (Column c : t.cols) {
                int dc = c.type == ColType.TEXT ? c.distinctCount() : 0;
                if (c.type == ColType.TEXT && dc >= 2 && dc <= 30) {
                    if (dim == null) dim = c;
                    else if (dim2 == null) dim2 = c;
                }
            }
            Column time = t.firstOfType(ColType.DATE);
            if (metric != null && dim != null) out.add("Chart total " + metric.name + " by " + dim.name);
            if (metric != null && time != null) out.add("Show " + metric.name + " per month");
            if (metric != null && time != null) out.add("What's driving the biggest drop in " + metric.name + "?");
            if (metric != null && dim2 != null) out.add("Average " + metric.name + " by " + dim2.name);
            if (metric != null && dim != null) out.add("Top 5 " + dim.name + " by " + metric.name);
            return out;
        }
    }
}
