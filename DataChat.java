import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

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
    private Plan lastPlan;   // the plan behind the latest answer: lets the offline engine handle follow-ups
    private final JButton pdfBtn = new JButton("Export PDF report...");
    private final JButton chartBtn = new JButton("Save chart as PNG...");
    private final JCheckBox darkBox = new JCheckBox("Dark mode");
    private static final ChartType[] SWITCH_TYPES = {ChartType.BAR, ChartType.HBAR, ChartType.LINE, ChartType.PIE};
    private static final String[] SWITCH_NAMES = {"Bars", "Horizontal bars", "Line", "Pie"};
    private final JToggleButton[] typeBtns = new JToggleButton[SWITCH_TYPES.length];
    private final ButtonGroup typeGroup = new ButtonGroup();
    private boolean dark;
    private static String lightLaf;
    private final JButton copyBtn = new JButton("Copy chart");
    private List<ChartData> shownCharts = List.of();
    private final List<ReportEntry> reportLog = new ArrayList<>();
    private List<Insight> insights = List.of();

    record ReportEntry(String question, String answer, Result result, String filters) {}

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
        JButton load = new JButton("Load CSV / Excel...");
        load.addActionListener(e -> chooseFile());
        JButton demo = new JButton("Demo data");
        demo.addActionListener(e -> loadDemo());
        // Two rows so nothing is clipped on narrower windows:
        //   row 1: file + export actions     row 2: Claude / privacy settings
        pdfBtn.setEnabled(false);
        pdfBtn.addActionListener(e -> exportPdf());
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row1.add(load);
        row1.add(demo);
        row1.add(Box.createHorizontalStrut(14));
        row1.add(pdfBtn);
        row1.add(Box.createHorizontalStrut(14));
        darkBox.setToolTipText("Dark colour scheme to reduce eye strain. Exports and PDF reports stay light.");
        darkBox.addActionListener(e -> setDark(darkBox.isSelected(), true));
        row1.add(darkBox);
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row2.add(new JLabel("Claude API key:"));
        row2.add(keyField);
        row2.add(new JLabel("Model:"));
        row2.add(modelField);
        row2.add(offlineBox);
        row2.add(samplesBox);
        JPanel bar = new JPanel(new GridLayout(2, 1));
        bar.add(row1);
        bar.add(row2);

        // ---- chat column
        chat.setEditable(false);
        chat.addHyperlinkListener(e -> {
            if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED || e.getDescription() == null) return;
            String d = e.getDescription();
            if (d.startsWith("insight:")) {
                try {
                    runInsight(Integer.parseInt(d.substring(8)));
                } catch (NumberFormatException ignored) {
                }
            }
        });
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
        chartBtn.setEnabled(false);
        chartBtn.setToolTipText("Save the chart as a PNG image (right-click a chart for the same option)");
        chartBtn.addActionListener(e -> exportCharts());
        copyBtn.setEnabled(false);
        copyBtn.setToolTipText("Copy the chart to the clipboard so you can paste it into a slide, email or document");
        copyBtn.addActionListener(e -> {
            if (!shownCharts.isEmpty()) ChartExport.copy(this, ChartExport.combined(shownCharts));
        });
        JPanel typeBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        typeBar.add(new JLabel("Show as:"));
        for (int i = 0; i < SWITCH_TYPES.length; i++) {
            final ChartType ty = SWITCH_TYPES[i];
            JToggleButton tb = new JToggleButton(SWITCH_NAMES[i]);
            tb.setFocusPainted(false);
            tb.setEnabled(false);
            tb.setToolTipText("Redraw the current chart as " + SWITCH_NAMES[i].toLowerCase(Locale.ROOT) + " (greyed out when the data doesn't suit that type)");
            tb.addActionListener(e -> switchChartType(ty));
            typeGroup.add(tb);
            typeBar.add(tb);
            typeBtns[i] = tb;
        }
        JPanel actionBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        actionBar.add(copyBtn);
        actionBar.add(chartBtn);
        JPanel chartBar = new JPanel(new BorderLayout());
        chartBar.add(typeBar, BorderLayout.WEST);
        chartBar.add(actionBar, BorderLayout.EAST);
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

        lightLaf = UIManager.getLookAndFeel().getClass().getName();
        boolean wantDark = false;
        try {
            wantDark = java.util.prefs.Preferences.userNodeForPackage(DataChat.class).getBoolean("darkMode", false);
        } catch (Exception ignored) {
            // preferences can be unavailable (locked-down systems): just start light
        }
        if (wantDark) {
            darkBox.setSelected(true);
            setDark(true, false);
        }
    }

    // ---------------------------------------------------------------- dark mode
    /** A dark Metal colour theme (Metal draws everything from these few colours, so the whole UI follows). */
    static final class DarkTheme extends javax.swing.plaf.metal.DefaultMetalTheme {
        static final Color BG = new Color(0x1E1E1E);
        private static final javax.swing.plaf.ColorUIResource
                P1 = new javax.swing.plaf.ColorUIResource(0x7AA2D8), P2 = new javax.swing.plaf.ColorUIResource(0x3D5A80), P3 = new javax.swing.plaf.ColorUIResource(0x35516F),
                S1 = new javax.swing.plaf.ColorUIResource(0x8A8A8A), S2 = new javax.swing.plaf.ColorUIResource(0x5A5A5A), S3 = new javax.swing.plaf.ColorUIResource(0x303234),
                WHITE = new javax.swing.plaf.ColorUIResource(0x1E1E1E), BLACK = new javax.swing.plaf.ColorUIResource(0xE4E4E4),
                DISABLED = new javax.swing.plaf.ColorUIResource(0x808080);

        @Override public String getName() { return "DataChat Dark"; }
        @Override protected javax.swing.plaf.ColorUIResource getPrimary1() { return P1; }
        @Override protected javax.swing.plaf.ColorUIResource getPrimary2() { return P2; }
        @Override protected javax.swing.plaf.ColorUIResource getPrimary3() { return P3; }
        @Override protected javax.swing.plaf.ColorUIResource getSecondary1() { return S1; }
        @Override protected javax.swing.plaf.ColorUIResource getSecondary2() { return S2; }
        @Override protected javax.swing.plaf.ColorUIResource getSecondary3() { return S3; }
        @Override protected javax.swing.plaf.ColorUIResource getWhite() { return WHITE; }
        @Override protected javax.swing.plaf.ColorUIResource getBlack() { return BLACK; }
        @Override public javax.swing.plaf.ColorUIResource getInactiveControlTextColor() { return DISABLED; }
        @Override public javax.swing.plaf.ColorUIResource getControlDisabled() { return DISABLED; }
    }

    private void setDark(boolean on, boolean save) {
        dark = on;
        try {
            if (on || (lightLaf != null && lightLaf.equals("javax.swing.plaf.metal.MetalLookAndFeel"))) {
                javax.swing.plaf.metal.MetalLookAndFeel.setCurrentTheme(on ? new DarkTheme() : new javax.swing.plaf.metal.OceanTheme());
                UIManager.setLookAndFeel(new javax.swing.plaf.metal.MetalLookAndFeel());
            } else if (lightLaf != null) {
                UIManager.setLookAndFeel(lightLaf);
            }
        } catch (Exception ex) {
            say("Analyst", "I couldn't switch the colour scheme: " + esc(String.valueOf(ex.getMessage())), RED);
        }
        for (Window w : Window.getWindows()) SwingUtilities.updateComponentTreeUI(w);
        chartHost.setBackground(on ? DarkTheme.BG : Color.WHITE);
        renderChat();
        showCharts(shownCharts);
        if (save) {
            try {
                java.util.prefs.Preferences.userNodeForPackage(DataChat.class).putBoolean("darkMode", on);
            } catch (Exception ignored) {
                // not being able to remember the choice is harmless
            }
        }
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
        renderChat();
    }

    /** Rebuilds the transcript for the current colour scheme (the four text colours are swapped for readable dark-mode ones). */
    private void renderChat() {
        String html = chatHtml.toString();
        String body = "font-family:SansSerif;font-size:12px";
        String head = "";
        if (dark) {
            html = html.replace(BLUE, "#7fb2ff").replace(GREEN, "#6fd3a0").replace(RED, "#ff8a80").replace(GREY, "#a3a3a3");
            body += ";color:#e4e4e4;background-color:#1e1e1e";
            head = "<style>a{color:#8ab4f8}</style>";
        }
        chat.setText("<html><head>" + head + "</head><body style='" + body + "'>" + html + "</body></html>");
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
        fc.setFileFilter(new FileNameExtensionFilter("CSV, TSV, text and Excel files", "csv", "tsv", "txt", "xlsx", "xlsm"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) loadFile(fc.getSelectedFile());
    }

    private void loadFile(File f) {
        lastDir = f.getParentFile();
        ChartExport.dir = lastDir;
        String lname = f.getName().toLowerCase(Locale.ROOT);
        if (Xlsx.isExcel(f)) {
            loadExcel(f);
            return;
        }
        if (lname.endsWith(".xls")) {
            say("Analyst", "<b>" + esc(f.getName()) + "</b> is an old-style Excel (.xls) file, which I can't read. Open it in Excel and use Save As &gt; Excel Workbook (.xlsx) or CSV, then load that.", RED);
            return;
        }
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

    private void loadExcel(File f) {
        setBusy(true, "Opening " + f.getName() + "...");
        new SwingWorker<List<Xlsx.SheetRef>, Void>() {
            protected List<Xlsx.SheetRef> doInBackground() throws Exception {
                return Xlsx.sheets(f);
            }

            protected void done() {
                List<Xlsx.SheetRef> all;
                try {
                    all = get();
                } catch (Exception ex) {
                    setBusy(false, null);
                    excelError(ex);
                    return;
                }
                setBusy(false, null);
                List<Xlsx.SheetRef> visible = new ArrayList<>();
                for (Xlsx.SheetRef s : all) if (!s.hidden()) visible.add(s);
                if (visible.isEmpty()) visible = all;
                Xlsx.SheetRef pick = visible.get(0);
                if (visible.size() > 1) {
                    String[] names = new String[visible.size()];
                    for (int i = 0; i < names.length; i++) names[i] = visible.get(i).name();
                    Object choice = JOptionPane.showInputDialog(DataChat.this, "\"" + f.getName() + "\" has " + names.length + " sheets. Which one should I load?",
                            "Choose a sheet", JOptionPane.QUESTION_MESSAGE, null, names, names[0]);
                    if (choice == null) return;
                    pick = visible.get(Arrays.asList(names).indexOf(choice));
                }
                readSheet(f, pick, visible.size() > 1);
            }
        }.execute();
    }

    private void readSheet(File f, Xlsx.SheetRef sheet, boolean multi) {
        setBusy(true, "Reading " + sheet.name() + "...");
        new SwingWorker<DataTable, Void>() {
            String note;

            protected DataTable doInBackground() throws Exception {
                Xlsx.Loaded l = Xlsx.read(f, sheet);
                note = l.note();
                return DataTable.fromRecords(f.getName() + (multi ? " [" + sheet.name() + "]" : ""), l.records());
            }

            protected void done() {
                try {
                    setTable(get(), false);
                    if (note != null) say("Analyst", esc(note), GREY);
                } catch (Exception ex) {
                    excelError(ex);
                } finally {
                    setBusy(false, null);
                }
            }
        }.execute();
    }

    private void excelError(Exception ex) {
        Throwable c = ex.getCause() != null ? ex.getCause() : ex;
        say("Analyst", "I couldn't read that workbook: " + esc(String.valueOf(c.getMessage())), RED);
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
        reportLog.clear();
        pdfBtn.setEnabled(false);
        insights = List.of();
        lastPlan = null;
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
        findInsights(t);
    }

    // ---------------------------------------------------------------- auto-insights
    private void findInsights(DataTable t) {
        new SwingWorker<List<Insight>, Void>() {
            protected List<Insight> doInBackground() { return Insights.generate(t); }

            protected void done() {
                try {
                    if (table != t) return;   // another file was loaded meanwhile
                    insights = get();
                    if (insights.isEmpty()) return;
                    StringBuilder sb = new StringBuilder("I scanned the data and noticed:<br>");
                    for (int i = 0; i < insights.size(); i++)
                        sb.append("&bull; ").append(esc(insights.get(i).headline())).append(" &mdash; <a href='insight:").append(i).append("'>explore</a><br>");
                    say("Auto-insights", sb.toString(), BLUE);
                } catch (Exception ignored) {
                }
            }
        }.execute();
    }

    private void runInsight(int i) {
        if (i < 0 || i >= insights.size() || !input.isEnabled()) return;
        Insight in = insights.get(i);
        ask("Tell me more: " + in.headline(), in.plan());
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

    private void ask(String question) { ask(question, null); }

    private void ask(String question, Plan preset) {
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
        final Plan prevPlan = lastPlan;
        setBusy(true, ai ? "Asking Claude..." : "Analysing...");

        new SwingWorker<Outcome, Void>() {
            protected Outcome doInBackground() {
                Outcome o = new Outcome();
                Plan plan = null;
                Result result = null;
                if (preset != null) {   // an auto-insight: the plan is already known
                    try {
                        plan = preset;
                        result = Engine.execute(t, plan);
                    } catch (AnalysisException ex) {
                        o.error = ex.getMessage();
                        return o;
                    }
                    if (claude != null) {
                        try {
                            o.answer = claude.narrate(q, plan, result);
                        } catch (Exception ex) {
                            o.warning = "Claude could not write the summary (" + ex.getMessage() + "); showing the computed facts instead.";
                        }
                    }
                    if (o.answer == null) o.answer = LocalPlanner.narrate(plan, result);
                    o.plan = plan;
                    o.result = result;
                    return o;
                }
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
                        plan = LocalPlanner.plan(q, t, prevPlan);
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
                        lastPlan = o.plan;
                        turns.add(new String[]{q, Json.write(o.plan.toJson())});
                        reportLog.add(new ReportEntry(q, o.answer.replace("**", ""), o.result, Engine.describeFilters(o.plan)));
                        pdfBtn.setEnabled(true);
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
        shownCharts = List.copyOf(charts);
        chartBtn.setEnabled(!charts.isEmpty());
        copyBtn.setEnabled(!charts.isEmpty());
        chartHost.removeAll();
        if (charts.isEmpty()) {
            JLabel l = new JLabel("No chart for this answer - see the Result table tab.", SwingConstants.CENTER);
            l.setForeground(Color.GRAY);
            chartHost.add(l);
        } else {
            for (ChartData c : charts) {
                ChartPanel cp = new ChartPanel(c, c.basePlan == null ? null : (li, si) -> drill(c, li, si));
                cp.setDark(dark);
                chartHost.add(cp);
            }
        }
        chartHost.revalidate();
        chartHost.repaint();
        syncTypeButtons();
    }

    /** True if this chart's data can sensibly be drawn as the given type. */
    static boolean canShowAs(ChartData cd, ChartType t) {
        if (cd.fixedType || cd.type == ChartType.SCATTER || !cd.sx.isEmpty() || cd.fit != null) return false;
        int nx = cd.labels.size(), ns = cd.values.size();
        if (nx == 0 || ns == 0) return false;
        if (cd.signColors) return t == ChartType.BAR || t == ChartType.HBAR;
        switch (t) {
            case PIE: {
                for (double[] row : cd.values) for (double v : row) if (v < 0) return false;
                return ns == 1 && nx >= 2 && nx <= 12;
            }
            case LINE:
                return nx >= 2;
            case BAR:
            case HBAR:
                return true;
            default:
                return false;
        }
    }

    private void switchChartType(ChartType t) {
        for (ChartData cd : shownCharts) if (canShowAs(cd, t)) cd.type = t;
        chartHost.revalidate();
        chartHost.repaint();
        syncTypeButtons();
    }

    /** Enables the "Show as" buttons the current charts can use and ticks the one in use. */
    private void syncTypeButtons() {
        ChartData first = null;
        for (ChartData cd : shownCharts) if (!cd.fixedType && cd.type != ChartType.SCATTER && (first == null)) first = cd;
        boolean any = false;
        for (int i = 0; i < typeBtns.length; i++) {
            boolean ok = false;
            for (ChartData cd : shownCharts) if (canShowAs(cd, SWITCH_TYPES[i])) ok = true;
            typeBtns[i].setEnabled(ok);
            any |= ok;
            if (ok && first != null && first.type == SWITCH_TYPES[i]) typeBtns[i].setSelected(true);
        }
        if (!any || first == null) typeGroup.clearSelection();
    }

    private void exportCharts() {
        if (shownCharts.isEmpty()) return;
        ChartExport.dir = lastDir;
        if (shownCharts.size() == 1) {
            File f = ChartExport.saveDialog(this, shownCharts.get(0));
            if (f != null) {
                lastDir = f.getParentFile();
                say("Analyst", "Saved the chart to <b>" + esc(f.getName()) + "</b>.", GREEN);
            }
            return;
        }
        File base = ChartExport.choose(this, ChartExport.fileName(shownCharts.get(0)), "Save " + shownCharts.size() + " charts (a number is added to each file name)");
        if (base == null) return;
        String stem = base.getName().replaceFirst("(?i)\\.png$", "");
        try {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < shownCharts.size(); i++) {
                File f = new File(base.getParentFile(), stem + "-" + (i + 1) + ".png");
                ChartExport.writePng(shownCharts.get(i), f);
                names.append(i > 0 ? ", " : "").append(f.getName());
            }
            lastDir = base.getParentFile();
            ChartExport.dir = lastDir;
            say("Analyst", "Saved " + shownCharts.size() + " charts: <b>" + esc(names.toString()) + "</b>.", GREEN);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not save the charts: " + ex.getMessage());
        }
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

    private void exportPdf() {
        if (table == null || reportLog.isEmpty()) return;
        JFileChooser fc = new JFileChooser(lastDir);
        fc.setFileFilter(new FileNameExtensionFilter("PDF document", "pdf"));
        fc.setSelectedFile(new File("datachat-report.pdf"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".pdf")) f = new File(f.getPath() + ".pdf");
        if (f.exists() && JOptionPane.showConfirmDialog(this, f.getName() + " already exists. Overwrite it?", "Export PDF report",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        try {
            List<String> lines = new ArrayList<>();
            for (Insight in : insights) lines.add(in.headline());
            Files.write(f.toPath(), ReportBuilder.build(table, demoLoaded, lines, new ArrayList<>(reportLog)));
            lastDir = f.getParentFile();
            say("Analyst", "Exported a " + reportLog.size() + "-question report to <b>" + esc(f.getName()) + "</b>.", GREEN);
        } catch (IOException | RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Could not create the report: " + ex.getMessage());
        } finally {
            setCursor(Cursor.getDefaultCursor());
        }
    }

    /** A bar, point or slice was clicked: run the follow-up analysis it implies. */
    private void drill(ChartData cd, int li, int si) {
        if (table == null || !input.isEnabled()) return;
        Drill.Drilled d = Drill.plan(table, cd, li, si);
        if (d == null) {
            say("Analyst", "I can't drill into that part of the chart.", GREY);
            return;
        }
        ask(d.question(), d.plan());
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
        boolean euro;   // decimal comma ("1.234,56") detected for this column
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

        void detect() { detect(false); }

        /** preferEuro: when a column's style is ambiguous, follow the rest of the file. */
        void detect(boolean preferEuro) {
            type = ColType.TEXT;
            num = null;
            date = null;
            dmin = dmax = null;
            int n = 0, nn = 0, votes = 0;
            for (String s : text) if (!s.isEmpty()) votes += numStyle(s);
            euro = votes > 0 || (votes == 0 && preferEuro);
            for (String s : text) {
                if (s.isEmpty()) continue;
                n++;
                if (!Double.isNaN(parseNum(s, euro))) nn++;
            }
            if (n == 0) return;
            if (nn >= 0.95 * n && !idLike(name)) {
                num = new double[text.length];
                double sum = 0, mn = Double.POSITIVE_INFINITY, mx = Double.NEGATIVE_INFINITY;
                int cnt = 0;
                for (int i = 0; i < text.length; i++) {
                    num[i] = parseNum(text[i], euro);
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

    static double parseNum(String s) { return parseNum(s, false); }

    /**
     * Vote on a value's number style: +1 = European ("1.234,56", "12,5"), -1 = US ("1,234.56", "1,234,567"),
     * 0 = ambiguous or not a number ("1,234", "12.5", "7").
     */
    static final Pattern EU_GROUPED = Pattern.compile("\\d{1,3}(\\.\\d{3})+(,\\d+)?");
    static final Pattern US_GROUPED = Pattern.compile("\\d{1,3}(,\\d{3})+(\\.\\d+)?");

    static int numStyle(String s) {
        String t = s.replaceAll("[\u00a3$\u20ac%\\s\u00a0()+\\-]", "");
        if (t.isEmpty() || !t.matches("[0-9.,]+")) return 0;
        if (EU_GROUPED.matcher(t).matches()) return t.contains(",") || t.indexOf('.') != t.lastIndexOf('.') ? 1 : 0;   // "1.234" alone is ambiguous
        if (US_GROUPED.matcher(t).matches()) return t.contains(".") || t.indexOf(',') != t.lastIndexOf(',') ? -1 : 0;   // "1,234" alone is ambiguous
        Matcher m = Pattern.compile("(\\d+),(\\d+)").matcher(t);
        if (m.matches() && (m.group(2).length() != 3 || m.group(1).length() > 3)) return 1;   // "12,5", "3,14", "1234,567"
        return 0;
    }

    static double parseNum(String s, boolean euro) {
        if (s == null) return Double.NaN;
        s = s.trim();
        if (s.isEmpty()) return Double.NaN;
        boolean neg = false;
        if (s.startsWith("(") && s.endsWith(")")) {
            neg = true;
            s = s.substring(1, s.length() - 1);
        }
        s = s.replaceAll("[\u00a3$\u20ac%\\s\u00a0]", "");
        String body = s.replaceFirst("^[-+]", "");
        if (euro) {
            if (body.contains(".") && !EU_GROUPED.matcher(body).matches() && !body.matches("\\d+\\.\\d+")) return Double.NaN;   // e.g. a dd.MM.yyyy date
            if (body.matches("\\d+\\.\\d+") && !EU_GROUPED.matcher(body).matches()) body = body.replace('.', ',');           // stray US-style decimal
            s = (s.startsWith("-") ? "-" : "") + body.replace(".", "").replace(",", ".");
        } else {
            if (body.contains(",") && !US_GROUPED.matcher(body).matches()) return Double.NaN;
            s = s.replace(",", "");
        }
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
            boolean fileEuro = false;
            for (Column c : t.cols) if (c.type == ColType.NUMBER && c.euro) fileEuro = true;
            if (fileEuro) for (Column c : t.cols) if (c.type != ColType.DATE && !c.euro) c.detect(true);   // e.g. "1.200" in a European file
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

    // =====================================================================================
    //  Excel (.xlsx / .xlsm) reader: a small dependency-free parser (zip + streaming XML)
    // =====================================================================================
    static final class Xlsx {
        record SheetRef(String name, String path, boolean hidden) {}

        record Loaded(List<String[]> records, String note) {}

        static final long MAX_SHEET_BYTES = 1L << 30;      // uncompressed XML limit (zip-bomb guard)
        static final long MAX_SMALL_BYTES = 256L << 20;    // shared strings / styles / workbook
        static final int MAX_ROWS = 1_000_000, MAX_COLS = 16_384;
        static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

        static boolean isExcel(File f) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            return n.endsWith(".xlsx") || n.endsWith(".xlsm") || n.endsWith(".xltx") || n.endsWith(".xltm");
        }

        // ---------------------------------------------------------------- public API
        static List<SheetRef> sheets(File f) throws IOException {
            try (ZipFile z = open(f)) {
                String wbPath = workbookPath(z);
                Map<String, String> rels = relationships(z, relsPathFor(wbPath), wbPath);
                List<SheetRef> out = new ArrayList<>();
                try {
                    XMLStreamReader r = factory().createXMLStreamReader(stream(z, wbPath, MAX_SMALL_BYTES));
                    while (r.hasNext()) {
                        if (r.next() != XMLStreamConstants.START_ELEMENT || !r.getLocalName().equals("sheet")) continue;
                        String name = attr(r, "name"), id = relAttr(r), state = attr(r, "state");
                        String target = id == null ? null : rels.get(id);
                        if (name != null && target != null) out.add(new SheetRef(name, target, state != null && !state.equals("visible")));
                    }
                    r.close();
                } catch (XMLStreamException e) {
                    throw new IOException("The workbook structure is damaged: " + e.getMessage(), e);
                }
                if (out.isEmpty()) throw new IOException("This workbook has no worksheets.");
                return out;
            }
        }

        static Loaded read(File f, SheetRef sheet) throws IOException {
            try (ZipFile z = open(f)) {
                String wbPath = workbookPath(z);
                Map<String, String> typeToPath = new HashMap<>();
                relationshipsByType(z, relsPathFor(wbPath), wbPath, typeToPath);
                boolean date1904 = false;
                try {
                    XMLStreamReader r = factory().createXMLStreamReader(stream(z, wbPath, MAX_SMALL_BYTES));
                    while (r.hasNext()) {
                        if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("workbookPr")) {
                            String v = attr(r, "date1904");
                            date1904 = v != null && (v.equals("1") || v.equalsIgnoreCase("true"));
                            break;
                        }
                    }
                    r.close();
                } catch (XMLStreamException e) {
                    throw new IOException("The workbook structure is damaged: " + e.getMessage(), e);
                }
                List<String> shared = typeToPath.containsKey("sharedStrings") ? sharedStrings(z, typeToPath.get("sharedStrings")) : List.of();
                int[] kinds = typeToPath.containsKey("styles") ? styleKinds(z, typeToPath.get("styles")) : new int[0];
                return parseSheet(z, sheet.path(), shared, kinds, date1904);
            }
        }

        // ---------------------------------------------------------------- zip / xml plumbing
        static ZipFile open(File f) throws IOException {
            try {
                return new ZipFile(f);
            } catch (java.util.zip.ZipException e) {
                try (java.io.InputStream in = Files.newInputStream(f.toPath())) {
                    byte[] h = in.readNBytes(8);
                    if (h.length >= 4 && (h[0] & 0xFF) == 0xD0 && (h[1] & 0xFF) == 0xCF && (h[2] & 0xFF) == 0x11 && (h[3] & 0xFF) == 0xE0)
                        throw new IOException("This looks like an old Excel (.xls) file or a password-protected workbook. "
                                + "Open it in Excel and use Save As > Excel Workbook (.xlsx) or CSV, then load that.");
                }
                throw new IOException("That file is not a valid .xlsx workbook (" + e.getMessage() + ").");
            }
        }

        static XMLInputFactory factory() {
            XMLInputFactory f = XMLInputFactory.newFactory();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, true);
            return f;
        }

        static final class Limited extends java.io.FilterInputStream {
            private long left;

            Limited(java.io.InputStream in, long limit) {
                super(in);
                left = limit;
            }

            @Override
            public int read() throws IOException {
                if (left <= 0) throw new IOException("A part of the workbook is unreasonably large, so I stopped reading it.");
                int b = super.read();
                if (b >= 0) left--;
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (left <= 0) throw new IOException("A part of the workbook is unreasonably large, so I stopped reading it.");
                int n = super.read(b, off, (int) Math.min(len, left));
                if (n > 0) left -= n;
                return n;
            }
        }

        static java.io.InputStream stream(ZipFile z, String path, long limit) throws IOException {
            ZipEntry e = z.getEntry(path);
            if (e == null) e = z.getEntry(path.startsWith("/") ? path.substring(1) : path);
            if (e == null) throw new IOException("The workbook is missing \"" + path + "\".");
            return new Limited(z.getInputStream(e), limit);
        }

        static String attr(XMLStreamReader r, String local) {
            for (int i = 0; i < r.getAttributeCount(); i++) if (r.getAttributeLocalName(i).equals(local)) return r.getAttributeValue(i);
            return null;
        }

        /** The r:id attribute, whatever prefix the file uses for the relationships namespace. */
        static String relAttr(XMLStreamReader r) {
            String v = r.getAttributeValue(REL_NS, "id");
            return v != null ? v : attr(r, "id");
        }

        static String workbookPath(ZipFile z) throws IOException {
            if (z.getEntry("_rels/.rels") != null) {
                try {
                    XMLStreamReader r = factory().createXMLStreamReader(stream(z, "_rels/.rels", MAX_SMALL_BYTES));
                    while (r.hasNext()) {
                        if (r.next() != XMLStreamConstants.START_ELEMENT || !r.getLocalName().equals("Relationship")) continue;
                        String type = attr(r, "Type"), target = attr(r, "Target");
                        if (type != null && target != null && type.endsWith("/officeDocument"))
                            return target.startsWith("/") ? target.substring(1) : target;
                    }
                    r.close();
                } catch (XMLStreamException ignored) {
                    // fall through to the conventional location
                }
            }
            if (z.getEntry("xl/workbook.xml") == null) throw new IOException("That file does not look like an Excel workbook (no xl/workbook.xml).");
            return "xl/workbook.xml";
        }

        static String relsPathFor(String part) {
            int s = part.lastIndexOf('/');
            return (s < 0 ? "" : part.substring(0, s + 1)) + "_rels/" + part.substring(s + 1) + ".rels";
        }

        static String resolve(String basePart, String target) {
            if (target.startsWith("/")) return target.substring(1);
            int s = basePart.lastIndexOf('/');
            String dir = s < 0 ? "" : basePart.substring(0, s + 1);
            Deque<String> parts = new ArrayDeque<>();
            for (String seg : (dir + target).split("/")) {
                if (seg.equals("..")) {
                    if (!parts.isEmpty()) parts.removeLast();
                } else if (!seg.isEmpty() && !seg.equals(".")) parts.addLast(seg);
            }
            return String.join("/", parts);
        }

        /** relationship id -> resolved part path */
        static Map<String, String> relationships(ZipFile z, String relsPath, String basePart) throws IOException {
            Map<String, String> out = new HashMap<>();
            if (z.getEntry(relsPath) == null) return out;
            try {
                XMLStreamReader r = factory().createXMLStreamReader(stream(z, relsPath, MAX_SMALL_BYTES));
                while (r.hasNext()) {
                    if (r.next() != XMLStreamConstants.START_ELEMENT || !r.getLocalName().equals("Relationship")) continue;
                    String id = attr(r, "Id"), target = attr(r, "Target"), mode = attr(r, "TargetMode");
                    if (id != null && target != null && !"External".equals(mode)) out.put(id, resolve(basePart, target));
                }
                r.close();
            } catch (XMLStreamException e) {
                throw new IOException("The workbook structure is damaged: " + e.getMessage(), e);
            }
            return out;
        }

        /** relationship type suffix (sharedStrings, styles, ...) -> part path */
        static void relationshipsByType(ZipFile z, String relsPath, String basePart, Map<String, String> out) throws IOException {
            if (z.getEntry(relsPath) == null) {
                out.put("sharedStrings", "xl/sharedStrings.xml");
                out.put("styles", "xl/styles.xml");
                out.values().removeIf(p -> z.getEntry(p) == null);
                return;
            }
            try {
                XMLStreamReader r = factory().createXMLStreamReader(stream(z, relsPath, MAX_SMALL_BYTES));
                while (r.hasNext()) {
                    if (r.next() != XMLStreamConstants.START_ELEMENT || !r.getLocalName().equals("Relationship")) continue;
                    String type = attr(r, "Type"), target = attr(r, "Target");
                    if (type == null || target == null || "External".equals(attr(r, "TargetMode"))) continue;
                    String kind = type.substring(type.lastIndexOf('/') + 1);
                    if (kind.equals("sharedStrings") || kind.equals("styles")) out.put(kind, resolve(basePart, target));
                }
                r.close();
            } catch (XMLStreamException e) {
                throw new IOException("The workbook structure is damaged: " + e.getMessage(), e);
            }
        }

        // ---------------------------------------------------------------- shared strings and styles
        static String unescape(String s) {
            if (s.indexOf("_x") < 0) return s;
            Matcher m = Pattern.compile("_x([0-9A-Fa-f]{4})_").matcher(s);
            StringBuilder sb = new StringBuilder();
            while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1), 16))));
            m.appendTail(sb);
            return sb.toString();
        }

        static List<String> sharedStrings(ZipFile z, String path) throws IOException {
            List<String> out = new ArrayList<>();
            try {
                XMLStreamReader r = factory().createXMLStreamReader(stream(z, path, MAX_SMALL_BYTES));
                StringBuilder sb = null;
                boolean inT = false;
                int phantom = 0;
                while (r.hasNext()) {
                    int ev = r.next();
                    if (ev == XMLStreamConstants.START_ELEMENT) {
                        String n = r.getLocalName();
                        if (n.equals("si")) sb = new StringBuilder();
                        else if (n.equals("rPh")) phantom++;
                        else if (n.equals("t") && sb != null && phantom == 0) inT = true;
                    } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA || ev == XMLStreamConstants.SPACE) {
                        if (inT && sb != null) sb.append(r.getText());
                    } else if (ev == XMLStreamConstants.END_ELEMENT) {
                        String n = r.getLocalName();
                        if (n.equals("t")) inT = false;
                        else if (n.equals("rPh")) phantom--;
                        else if (n.equals("si") && sb != null) {
                            out.add(unescape(sb.toString()));
                            sb = null;
                        }
                    }
                }
                r.close();
            } catch (XMLStreamException e) {
                throw new IOException("The workbook's text table is damaged: " + e.getMessage(), e);
            }
            return out;
        }

        static final int K_GENERAL = 0, K_DATE = 1, K_TIME = 2, K_PERCENT = 3;

        static int builtinKind(int id) {
            if (id == 9 || id == 10) return K_PERCENT;
            if (id == 14 || (id >= 15 && id <= 17) || (id >= 27 && id <= 31) || (id >= 34 && id <= 36) || (id >= 50 && id <= 58)) return K_DATE;
            if (id == 22 || id == 32 || id == 33) return K_DATE;   // date + time
            if (id >= 18 && id <= 21) return K_TIME;
            if (id >= 45 && id <= 47) return K_TIME;
            return K_GENERAL;
        }

        static int formatKind(String code) {
            StringBuilder sb = new StringBuilder();
            boolean quote = false;
            for (int i = 0; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '"') quote = !quote;
                else if (!quote) {
                    if (c == '\\' || c == '_' || c == '*') i++;          // escaped / padding character
                    else if (c == '[') {                                  // [Red], [$-409], [h]: keep elapsed-time markers only
                        int e = code.indexOf(']', i);
                        if (e < 0) break;
                        String inner = code.substring(i + 1, e).toLowerCase(Locale.ROOT);
                        if (inner.matches("h+|m+|s+")) sb.append(inner.charAt(0));
                        i = e;
                    } else sb.append(Character.toLowerCase(c));
                }
            }
            String s = sb.toString();
            if (s.equals("general")) return K_GENERAL;
            boolean hasD = s.indexOf('d') >= 0 || s.indexOf('y') >= 0, hasT = s.indexOf('h') >= 0 || s.indexOf('s') >= 0;
            boolean hasM = s.indexOf('m') >= 0;
            if (hasD || (hasM && !hasT && s.indexOf('0') < 0 && s.indexOf('#') < 0)) return K_DATE;   // dd/mm, mmm-yy ...
            if (hasT) return K_TIME;
            if (s.indexOf('%') >= 0) return K_PERCENT;
            return K_GENERAL;
        }

        /** Index = style index (cellXfs); value = how its numbers should be read. */
        static int[] styleKinds(ZipFile z, String path) throws IOException {
            Map<Integer, String> custom = new HashMap<>();
            List<Integer> xfs = new ArrayList<>();
            try {
                XMLStreamReader r = factory().createXMLStreamReader(stream(z, path, MAX_SMALL_BYTES));
                boolean inXfs = false;
                while (r.hasNext()) {
                    int ev = r.next();
                    if (ev == XMLStreamConstants.START_ELEMENT) {
                        String n = r.getLocalName();
                        if (n.equals("numFmt")) {
                            String id = attr(r, "numFmtId"), code = attr(r, "formatCode");
                            if (id != null && code != null) custom.put(Integer.parseInt(id.trim()), code);
                        } else if (n.equals("cellXfs")) inXfs = true;
                        else if (n.equals("xf") && inXfs) {
                            String id = attr(r, "numFmtId");
                            xfs.add(id == null ? 0 : Integer.parseInt(id.trim()));
                        }
                    } else if (ev == XMLStreamConstants.END_ELEMENT && r.getLocalName().equals("cellXfs")) inXfs = false;
                }
                r.close();
            } catch (XMLStreamException | NumberFormatException e) {
                return new int[0];   // styles are a nicety: without them dates just show as serial numbers
            }
            int[] kinds = new int[xfs.size()];
            for (int i = 0; i < kinds.length; i++) {
                int id = xfs.get(i);
                kinds[i] = custom.containsKey(id) ? formatKind(custom.get(id)) : builtinKind(id);
            }
            return kinds;
        }

        // ---------------------------------------------------------------- values
        static String plainNumber(double v) {
            if (Double.isNaN(v) || Double.isInfinite(v)) return "";
            if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
            return new java.math.BigDecimal(v).round(new java.math.MathContext(15)).stripTrailingZeros().toPlainString();
        }

        static String formatValue(String raw, int kind, boolean date1904) {
            double v;
            try {
                v = Double.parseDouble(raw.trim());
            } catch (NumberFormatException e) {
                return raw.trim();
            }
            if (Double.isNaN(v) || Double.isInfinite(v)) return "";
            if (kind == K_PERCENT) return plainNumber(v * 100) + "%";
            if (kind == K_DATE && v == 0) return "";   // an empty date cell stored as zero
            if ((kind == K_DATE || kind == K_TIME) && v >= 0 && v < 2958466) {
                long days = (long) Math.floor(v);
                long secs = Math.round((v - days) * 86400);
                if (secs >= 86400) {
                    secs -= 86400;
                    days++;
                }
                java.time.LocalTime time = java.time.LocalTime.ofSecondOfDay(secs);
                if (days == 0 && !date1904) return time.toString().length() == 5 ? time + ":00" : time.toString();
                LocalDate base = date1904 ? LocalDate.of(1904, 1, 1) : (days < 60 ? LocalDate.of(1899, 12, 31) : LocalDate.of(1899, 12, 30));
                LocalDate d = base.plusDays(days);
                if (secs == 0) return d.toString();
                String hhmmss = time.toString().length() == 5 ? time + ":00" : time.toString();
                return d + " " + hhmmss;
            }
            return plainNumber(v);
        }

        static int colIndex(String ref) {
            int c = 0, i = 0;
            while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
                c = c * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
                i++;
            }
            return c - 1;
        }

        static String colLetters(int idx) {
            StringBuilder sb = new StringBuilder();
            for (int n = idx + 1; n > 0; n = (n - 1) / 26) sb.insert(0, (char) ('A' + (n - 1) % 26));
            return sb.toString();
        }

        // ---------------------------------------------------------------- the sheet itself
        static Loaded parseSheet(ZipFile z, String path, List<String> shared, int[] kinds, boolean date1904) throws IOException {
            List<String[]> rows = new ArrayList<>();
            int maxCol = 0;
            try {
                XMLStreamReader r = factory().createXMLStreamReader(stream(z, path, MAX_SHEET_BYTES));
                String[] cur = null;
                int curCol = -1;
                String type = null;
                int style = 0;
                StringBuilder val = new StringBuilder(), inline = new StringBuilder();
                boolean inV = false, inT = false, inIs = false;
                int phantom = 0;
                while (r.hasNext()) {
                    int ev = r.next();
                    if (ev == XMLStreamConstants.START_ELEMENT) {
                        String n = r.getLocalName();
                        switch (n) {
                            case "row" -> {
                                cur = new String[16];
                                curCol = -1;
                            }
                            case "c" -> {
                                String ref = attr(r, "r");
                                curCol = ref != null && !ref.isEmpty() ? colIndex(ref) : curCol + 1;
                                type = attr(r, "t");
                                String s = attr(r, "s");
                                try {
                                    style = s == null ? 0 : Integer.parseInt(s.trim());
                                } catch (NumberFormatException e) {
                                    style = 0;
                                }
                                val.setLength(0);
                                inline.setLength(0);
                            }
                            case "v" -> inV = true;
                            case "is" -> inIs = true;
                            case "rPh" -> phantom++;
                            case "t" -> {
                                if (inIs && phantom == 0) inT = true;
                            }
                            default -> {
                            }
                        }
                    } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA || ev == XMLStreamConstants.SPACE) {
                        if (inV) val.append(r.getText());
                        else if (inT) inline.append(r.getText());
                    } else if (ev == XMLStreamConstants.END_ELEMENT) {
                        String n = r.getLocalName();
                        switch (n) {
                            case "v" -> inV = false;
                            case "t" -> inT = false;
                            case "is" -> inIs = false;
                            case "rPh" -> phantom--;
                            case "c" -> {
                                if (cur != null && curCol >= 0 && curCol < MAX_COLS) {
                                    String text = cellText(type, val.toString(), inline.toString(), style, shared, kinds, date1904);
                                    if (!text.isEmpty()) {
                                        if (curCol >= cur.length) cur = Arrays.copyOf(cur, Math.max(curCol + 1, cur.length * 2));
                                        cur[curCol] = text;
                                        maxCol = Math.max(maxCol, curCol + 1);
                                    }
                                }
                            }
                            case "row" -> {
                                if (cur != null) {
                                    boolean any = false;
                                    for (String s : cur) if (s != null) { any = true; break; }
                                    if (any) {
                                        if (rows.size() >= MAX_ROWS) throw new IOException("That sheet has more than " + String.format(Locale.US, "%,d", MAX_ROWS) + " rows, which is more than DataChat will load.");
                                        rows.add(cur);
                                    }
                                }
                                cur = null;
                            }
                            default -> {
                            }
                        }
                    }
                }
                r.close();
            } catch (XMLStreamException e) {
                throw new IOException("The worksheet is damaged: " + e.getMessage(), e);
            }
            return tidy(rows, maxCol);
        }

        static String cellText(String type, String v, String inline, int style, List<String> shared, int[] kinds, boolean date1904) {
            String t = type == null ? "n" : type;
            switch (t) {
                case "s": {
                    try {
                        int i = Integer.parseInt(v.trim());
                        return i >= 0 && i < shared.size() ? shared.get(i) : "";
                    } catch (NumberFormatException e) {
                        return "";
                    }
                }
                case "inlineStr":
                    return unescape(inline);
                case "str":
                    return unescape(v);
                case "b":
                    return v.trim().equals("1") ? "TRUE" : "FALSE";
                case "e":
                    return "";
                case "d":
                    return v.trim();
                default: {
                    if (v.isBlank()) return "";
                    int kind = style >= 0 && style < kinds.length ? kinds[style] : K_GENERAL;
                    return formatValue(v, kind, date1904);
                }
            }
        }

        /** Squares the grid up, drops empty columns, skips title rows above the header and names unlabelled columns. */
        static Loaded tidy(List<String[]> rows, int maxCol) throws IOException {
            if (rows.isEmpty() || maxCol == 0) throw new IOException("That sheet is empty.");
            boolean[] used = new boolean[maxCol];
            for (String[] r : rows) for (int c = 0; c < Math.min(r.length, maxCol); c++) if (r[c] != null) used[c] = true;
            List<Integer> keep = new ArrayList<>();
            for (int c = 0; c < maxCol; c++) if (used[c]) keep.add(c);
            List<String[]> grid = new ArrayList<>();
            int[] counts = new int[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                String[] src = rows.get(i), dst = new String[keep.size()];
                for (int k = 0; k < dst.length; k++) {
                    int c = keep.get(k);
                    String s = c < src.length ? src[c] : null;
                    dst[k] = s == null ? "" : s;
                    if (s != null) counts[i]++;
                }
                grid.add(dst);
            }
            // title rows: leading rows holding far fewer cells than the table that follows them
            Map<Integer, Integer> freq = new HashMap<>();
            for (int i = 0; i < Math.min(grid.size(), 60); i++) freq.merge(counts[i], 1, Integer::sum);
            int modal = 0, best = 0;
            for (var e : freq.entrySet()) if (e.getValue() > best || (e.getValue() == best && e.getKey() > modal)) { best = e.getValue(); modal = e.getKey(); }
            int start = 0;
            if (modal >= 2) while (start < grid.size() - 1 && counts[start] * 2 < modal) start++;
            String note = start > 0 ? "Skipped " + start + " row" + (start == 1 ? "" : "s") + " above the header (they look like a title or notes)." : null;
            List<String[]> out = new ArrayList<>(grid.subList(start, grid.size()));
            String[] head = out.get(0);
            for (int k = 0; k < head.length; k++) if (head[k].isBlank()) head[k] = "Column " + colLetters(keep.get(k));
            return new Loaded(out, note);
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
                from, to, direction = "drop", message, time;
        Integer limit;
        List<Filter> filters = new ArrayList<>();

        @SuppressWarnings("unchecked")
        static Plan fromJson(Map<String, Object> m) {
            Plan p = new Plan();
            String in = lower(nz(m.get("intent")));
            p.intent = in != null && Set.of("aggregate", "drivers", "rows", "describe", "correlate", "anomalies", "compare", "forecast").contains(in) ? in : "aggregate";
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
            p.time = nz(m.get("time"));
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
            if (time != null) m.put("time", time);
            if (from != null) m.put("from", from);
            if (to != null) m.put("to", to);
            if (intent.equals("drivers")) m.put("direction", direction);
            if (message != null) m.put("message", message);
            return m;
        }

        Plan copy() {
            Plan c = new Plan();
            c.intent = intent;
            c.title = title;
            c.metric = metric;
            c.agg = agg;
            c.x = x;
            c.bucket = bucket;
            c.series = series;
            c.sort = sort;
            c.chart = chart;
            c.from = from;
            c.time = time;
            c.to = to;
            c.direction = direction;
            c.message = message;
            c.limit = limit;
            for (Filter f : filters) c.filters.add(new Filter(f.column(), f.op(), new ArrayList<>(f.values())));
            return c;
        }

        static String lower(String s) { return s == null ? null : s.toLowerCase(Locale.ROOT); }

        static String nz(Object o) {
            if (o == null) return null;
            String s = (o instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) ? String.valueOf((long) (double) d) : o.toString();
            s = s.trim();
            return (s.isEmpty() || s.equalsIgnoreCase("null")) ? null : s;
        }
    }

    enum ChartType { BAR, HBAR, LINE, PIE, SCATTER }

    static final class ChartData {
        ChartType type = ChartType.BAR;
        String title = "", xLabel = "", yLabel = "";
        List<String> labels = new ArrayList<>();
        List<String> series = new ArrayList<>();
        List<double[]> values = new ArrayList<>();
        boolean signColors;
        Set<Integer> highlight = new HashSet<>();
        boolean fixedType;                          // true: this chart can't be switched to another type (forecasts)
        double[] bandLo, bandHi;                    // optional shaded band behind a line chart (a forecast's range)
        int forecastFrom = -1;                      // first x index that is a projection (drawn on a shaded background)
        Set<Integer> dashed = new HashSet<>();      // series drawn as dashed lines
        String note;                                // small caption at the right of the legend row
        // ---- scatter plots: one x/y array per series, plus the fitted line {slope, intercept, r, n}
        List<double[]> sx = new ArrayList<>(), sy = new ArrayList<>();
        double[] fit;
        // ---- drill-down context (what a click on a bar / point / slice means)
        Plan basePlan;                              // the plan that produced this chart (null = not drillable)
        boolean periodX;                            // x labels are time periods
        String timeCol, bucket;
        List<List<Filter>> labelFilters;            // per x label: the filters that select it (category charts)
        List<List<Filter>> seriesFilters;           // per series: the filters that select it
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

    record DriverRow(int level, String label, double from, double to, double delta, double excess, double share, List<Filter> filters) {}

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
                case "correlate" -> correlate(t, p, keep, r);
                case "anomalies" -> anomalies(t, p, keep, r);
                case "compare" -> compare(t, p, keep, r);
                case "forecast" -> forecast(t, p, keep, r);
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

        // ------------------------------------------------------------ anomalies
        static final double ANOMALY_Z = 3.5;
        static final int MIN_GROUP = 8, MIN_PERIODS = 8;

        static double medianOf(double[] a) {
            if (a.length == 0) return Double.NaN;
            double[] s = a.clone();
            Arrays.sort(s);
            int n = s.length;
            return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
        }

        static double quantile(double[] sorted, double q) {
            double pos = q * (sorted.length - 1);
            int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
            return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
        }

        record Scored(double[] score, double typical, boolean logScale) {}

        /**
         * Robust z-scores based on the median and the median absolute deviation (Iglewicz-Hoaglin), so the outliers
         * themselves cannot hide the spread. All-positive, right-skewed columns (money) are judged on a log scale.
         * Returns null when the values have no spread at all, i.e. no test is meaningful.
         */
        static Scored robustScores(double[] x) {
            int n = x.length;
            double sum = 0;
            boolean positive = true;
            for (double v : x) {
                sum += v;
                if (!(v > 0)) positive = false;
            }
            double med = medianOf(x);
            boolean log = positive && med > 0 && sum / n > 1.5 * med;
            double[] y = new double[n];
            for (int i = 0; i < n; i++) y[i] = log ? Math.log(x[i]) : x[i];
            double m = medianOf(y);
            double[] dev = new double[n];
            for (int i = 0; i < n; i++) dev[i] = Math.abs(y[i] - m);
            double mad = medianOf(dev), scale;
            if (mad > 0) scale = mad / 0.6745;
            else {
                double[] s = y.clone();
                Arrays.sort(s);
                double iqr = quantile(s, 0.75) - quantile(s, 0.25);
                if (!(iqr > 0)) return null;
                scale = iqr / 1.349;
            }
            double[] sc = new double[n];
            for (int i = 0; i < n; i++) sc[i] = (y[i] - m) / scale;
            return new Scored(sc, med, log);
        }

        record Flag(int row, Column col, double value, double typical, double score, boolean log) {}

        static String rowLabel(DataTable t, int row) {
            StringBuilder sb = new StringBuilder();
            int used = 0;
            for (Column c : t.cols) {
                if (c.type != ColType.TEXT || used >= 2 || c.distinctCount() > 30) continue;   // categories, not IDs
                String s = c.text[row];
                if (s == null || s.isBlank()) continue;
                sb.append(used++ == 0 ? "" : " / ").append(s.trim());
            }
            String ctx = ChartPanel.shorten(sb.toString(), 28);
            return "#" + (row + 1) + (ctx.isEmpty() ? "" : " " + ctx);
        }

        static void anomalies(DataTable t, Plan p, boolean[] keep, Result r) throws AnalysisException {
            Column xc = p.x == null ? null : t.find(p.x);
            if (xc != null && xc.type == ColType.DATE) {
                periodAnomalies(t, p, keep, r, xc);
                return;
            }
            List<Column> cols = new ArrayList<>();
            if (p.metric != null) {
                Column m = need(t, p.metric, "column to scan");
                if (m.type != ColType.NUMBER)
                    throw new AnalysisException("Column \"" + m.name + "\" isn't numeric, so I can't look for unusual values in it. Numeric columns: "
                            + t.colsOfType(ColType.NUMBER).stream().map(c -> c.name).toList() + ".");
                cols.add(m);
            } else cols.addAll(t.colsOfType(ColType.NUMBER));
            if (cols.isEmpty()) throw new AnalysisException("This table has no numeric columns to scan for unusual values.");
            Column gc = optional(t, p.series, "group");
            if (gc != null && gc.type != ColType.TEXT) gc = null;

            // rows grouped for comparison ("unusual for its own category")
            Map<String, List<Integer>> groups = new LinkedHashMap<>();
            for (int i = 0; i < t.rows; i++) {
                if (!keep[i]) continue;
                String g = gc == null ? "" : (gc.text[i] == null || gc.text[i].isBlank() ? null : gc.text[i].trim());
                if (g != null) groups.computeIfAbsent(g, k -> new ArrayList<>()).add(i);
            }

            List<Flag> flags = new ArrayList<>();
            Map<String, int[]> perCol = new LinkedHashMap<>();   // column -> {tested, flagged}
            List<String> flat = new ArrayList<>(), tooSmall = new ArrayList<>();
            Set<String> logCols = new LinkedHashSet<>();
            for (Column c : cols) {
                int[] tally = new int[2];
                perCol.put(c.name, tally);
                boolean anyTested = false;
                for (List<Integer> g : groups.values()) {
                    List<Integer> idx = new ArrayList<>();
                    for (int i : g) if (!Double.isNaN(c.num[i])) idx.add(i);
                    if (idx.size() < MIN_GROUP) continue;
                    double[] x = new double[idx.size()];
                    for (int k = 0; k < x.length; k++) x[k] = c.num[idx.get(k)];
                    Scored s = robustScores(x);
                    if (s == null) continue;
                    anyTested = true;
                    tally[0] += x.length;
                    if (s.logScale()) logCols.add(c.name);
                    for (int k = 0; k < x.length; k++)
                        if (Math.abs(s.score()[k]) > ANOMALY_Z) {
                            flags.add(new Flag(idx.get(k), c, x[k], s.typical(), s.score()[k], s.logScale()));
                            tally[1]++;
                        }
                }
                if (!anyTested) {
                    int valid = 0;
                    for (List<Integer> g : groups.values()) for (int i : g) if (!Double.isNaN(c.num[i])) valid++;
                    (valid < MIN_GROUP ? tooSmall : flat).add(c.name);
                }
            }
            int tested = 0;
            for (int[] tl : perCol.values()) tested = Math.max(tested, tl[0]);
            boolean anyTested = false;
            for (int[] tl : perCol.values()) if (tl[0] > 0) anyTested = true;
            if (!anyTested)
                throw new AnalysisException("There isn't enough varied data to test"
                        + (cols.size() == 1 ? " " + cols.get(0).name : "") + " for unusual values (I need at least " + MIN_GROUP + " values"
                        + (gc != null ? " in a group" : "") + " that aren't all the same).");

            flags.sort((a, b) -> Double.compare(Math.abs(b.score()), Math.abs(a.score())));
            int total = flags.size(), lim = Math.max(1, Math.min(p.limit != null ? p.limit : 15, 200));
            int high = 0;
            for (Flag f : flags) if (f.score() > 0) high++;
            int low = total - high;

            // ---- table
            r.rawCells = true;
            r.headers.addAll(List.of("Data row", "Column", "Value", "Typical", "Score"));
            for (Column c : t.cols) r.headers.add(c.name);
            for (Flag f : flags.subList(0, Math.min(lim, total))) {
                List<String> row = new ArrayList<>(List.of(String.valueOf(f.row() + 1), f.col().name, f.col().text[f.row()],
                        Fmt.full(f.typical()), String.format(Locale.US, "%+.1f", f.score())));
                for (Column c : t.cols) row.add(c.text[f.row()]);
                r.rows.add(row.toArray(new String[0]));
            }
            String what = cols.size() == 1 ? cols.get(0).name + " values" : "values";
            r.title = p.title != null ? p.title : "Unusual " + what + (gc != null ? " (compared within each " + gc.name + ")" : "");

            // ---- chart
            if (total > 0) {
                ChartData cd = new ChartData();
                cd.title = r.title;
                if (cols.size() == 1) {
                    List<Flag> top = flags.subList(0, Math.min(12, total));
                    cd.type = ChartType.HBAR;
                    cd.yLabel = cols.get(0).name;
                    cd.series = new ArrayList<>(List.of(cols.get(0).name));
                    double[] v = new double[top.size()];
                    for (int i = 0; i < v.length; i++) {
                        cd.labels.add(rowLabel(t, top.get(i).row()));
                        v[i] = top.get(i).value();
                        if (v[i] < 0) cd.signColors = true;
                    }
                    cd.values.add(v);
                } else {
                    List<String> names = new ArrayList<>();
                    for (var e : perCol.entrySet()) if (e.getValue()[1] > 0) names.add(e.getKey());
                    names.sort((a, b) -> perCol.get(b)[1] - perCol.get(a)[1]);
                    cd.type = names.size() > 8 ? ChartType.HBAR : ChartType.BAR;
                    cd.yLabel = "Unusual values";
                    cd.series = new ArrayList<>(List.of("Unusual values"));
                    double[] v = new double[names.size()];
                    for (int i = 0; i < v.length; i++) v[i] = perCol.get(names.get(i))[1];
                    cd.labels = names;
                    cd.values.add(v);
                }
                r.charts.add(cd);
            }

            // ---- plain-English facts
            String filt = matchedNote(r, p);
            if (cols.size() == 1) {
                Column c = cols.get(0);
                if (total == 0)
                    r.facts.add("No unusual " + c.name + " values found among " + Fmt.full(tested) + " tested" + filt + ": everything sits within the normal range.");
                else
                    r.facts.add("Of " + Fmt.full(tested) + " " + c.name + " values" + filt + ", " + Fmt.full(total) + " (" + String.format(Locale.US, "%.1f%%", 100.0 * total / tested)
                            + ") look unusual: " + high + " unusually high and " + low + " unusually low.");
            } else {
                if (total == 0)
                    r.facts.add("Scanned " + perCol.size() + " numeric columns" + filt + " and found no unusual values.");
                else {
                    List<String> parts = new ArrayList<>();
                    perCol.entrySet().stream().filter(e -> e.getValue()[1] > 0)
                            .sorted((a, b) -> b.getValue()[1] - a.getValue()[1]).limit(5)
                            .forEach(e -> parts.add(e.getKey() + " (" + e.getValue()[1] + ")"));
                    r.facts.add("Scanned " + perCol.size() + " numeric columns" + filt + ": " + Fmt.full(total) + " unusual values in total, most in " + String.join(", ", parts) + ".");
                }
            }
            if (total > 0) {
                Flag top = flags.get(0);
                String how = top.log() && top.typical() > 0 && top.value() > 0
                        ? String.format(Locale.US, "%.1f", top.value() / top.typical()) + "x the typical " + Fmt.full(top.typical())
                        : "against a typical " + Fmt.full(top.typical());
                r.facts.add("The most extreme is data row " + (top.row() + 1) + ": " + top.col().name + " of " + top.col().text[top.row()] + " (" + how + ").");
                if (total > r.rows.size()) r.facts.add("Showing the " + r.rows.size() + " most extreme of " + total + ".");
            }
            if (!flat.isEmpty())
                r.facts.add("Not tested: " + String.join(", ", flat) + " (most values are identical, so \"unusual\" has no meaning there).");
            if (!tooSmall.isEmpty())
                r.facts.add("Not tested: " + String.join(", ", tooSmall) + " (fewer than " + MIN_GROUP + " values" + (gc != null ? " in each group" : "") + ").");
            if (total > 0 || !logCols.isEmpty())
                r.facts.add("Method: a value is flagged when its robust z-score (median and median absolute deviation) is beyond +/-" + ANOMALY_Z
                        + (logCols.isEmpty() ? "" : "; " + String.join(", ", logCols) + " is right-skewed, so it was judged on a log scale")
                        + ". Unusual does not mean wrong - check each one.");
            Column time = t.firstOfType(ColType.DATE);
            if (total > 0 && time != null)
                r.facts.add("Ask \"which weeks look unusual?\" to check for odd periods instead of odd rows.");
        }

        static final String[] BUCKET_ORDER = {"year", "quarter", "month", "week", "day"};

        static LocalDate periodStart(LocalDate d, String b) {
            switch (b) {
                case "week": return d.with(java.time.DayOfWeek.MONDAY);
                case "month": return d.withDayOfMonth(1);
                case "quarter": return d.withDayOfMonth(1).withMonth((d.get(IsoFields.QUARTER_OF_YEAR) - 1) * 3 + 1);
                case "year": return d.withDayOfYear(1);
                default: return d;
            }
        }

        static LocalDate periodEnd(LocalDate d, String b) {
            switch (b) {
                case "week": return d.with(java.time.DayOfWeek.SUNDAY);
                case "month": return d.withDayOfMonth(d.lengthOfMonth());
                case "quarter": return periodStart(d, b).plusMonths(3).minusDays(1);
                case "year": return d.withDayOfYear(d.lengthOfYear());
                default: return d;
            }
        }

        static void periodAnomalies(DataTable t, Plan p, boolean[] keep, Result r, Column xc) throws AnalysisException {
            Column m = metricOf(t, p);
            String agg = aggOf(p, m);
            String measure = measureLabel(agg, m);
            String bucket = bucketOf(p.bucket, xc, keep);
            if (bucket.equals("weekday")) bucket = "day";
            LocalDate mn = null, mx = null;
            for (int i = 0; i < t.rows; i++) {
                if (!keep[i] || xc.date[i] == null) continue;
                if (mn == null || xc.date[i].isBefore(mn)) mn = xc.date[i];
                if (mx == null || xc.date[i].isAfter(mx)) mx = xc.date[i];
            }
            if (mn == null) throw new AnalysisException("None of the matching rows has a usable " + xc.name + " date.");

            String asked = bucket;
            TreeMap<String, Acc> byP = groupByPeriod(t, keep, xc, m, agg, bucket);
            while (byP.size() < MIN_PERIODS && !bucket.equals("day")) {
                for (int i = 0; i < BUCKET_ORDER.length - 1; i++)
                    if (BUCKET_ORDER[i].equals(bucket)) {
                        bucket = BUCKET_ORDER[i + 1];
                        break;
                    }
                byP = groupByPeriod(t, keep, xc, m, agg, bucket);
            }
            if (byP.size() < MIN_PERIODS)
                throw new AnalysisException("There are only " + byP.size() + " distinct dates in the matching rows - I need at least " + MIN_PERIODS + " periods to judge which are unusual.");

            List<String> labels = new ArrayList<>(byP.keySet());
            List<String> notes = new ArrayList<>();
            // a partly-covered first or last period would look like a fake dip
            if (additive(agg) && !bucket.equals("day") && labels.size() > MIN_PERIODS + 1) {
                boolean dropFirst = periodStart(mn, bucket).isBefore(mn), dropLast = periodEnd(mx, bucket).isAfter(mx);
                if (dropLast) {
                    notes.add(labels.get(labels.size() - 1));
                    labels.remove(labels.size() - 1);
                }
                if (dropFirst) {
                    notes.add(labels.get(0));
                    labels.remove(0);
                }
            }
            int n = labels.size();
            double[] v = new double[n];
            for (int i = 0; i < n; i++) v[i] = nz0(byP.get(labels.get(i)).get(agg));

            // what "normal" means for each period: the median of nearby periods (same weekday for daily data)
            double[] base = new double[n], res = new double[n];
            Map<LocalDate, Integer> dayIdx = new HashMap<>();
            if (bucket.equals("day")) for (int i = 0; i < n; i++) dayIdx.put(LocalDate.parse(labels.get(i)), i);
            for (int i = 0; i < n; i++) {
                List<Double> nb = new ArrayList<>();
                if (bucket.equals("day")) {
                    LocalDate d = LocalDate.parse(labels.get(i));
                    for (int k = -4; k <= 4; k++) {
                        Integer j = k == 0 ? null : dayIdx.get(d.plusDays(7L * k));
                        if (j != null) nb.add(v[j]);
                    }
                }
                if (nb.size() < 3) {
                    nb.clear();
                    for (int j = Math.max(0, i - 3); j <= Math.min(n - 1, i + 3); j++) if (j != i) nb.add(v[j]);
                }
                base[i] = medianOf(nb.stream().mapToDouble(Double::doubleValue).toArray());
                res[i] = v[i] - base[i];
            }
            double medRes = medianOf(res);
            double[] dev = new double[n];
            for (int i = 0; i < n; i++) dev[i] = Math.abs(res[i] - medRes);
            double mad = medianOf(dev), scale;
            if (mad > 0) scale = mad / 0.6745;
            else {
                double[] s = res.clone();
                Arrays.sort(s);
                double iqr = quantile(s, 0.75) - quantile(s, 0.25);
                if (!(iqr > 0)) throw new AnalysisException(measure + " barely changes from one " + bucket + " to the next, so no period stands out.");
                scale = iqr / 1.349;
            }
            double[] score = new double[n];
            List<Integer> flagged = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                score[i] = (res[i] - medRes) / scale;
                if (Math.abs(score[i]) > ANOMALY_Z) flagged.add(i);
            }
            flagged.sort((a, b) -> Double.compare(Math.abs(score[b]), Math.abs(score[a])));
            int total = flagged.size(), lim = Math.max(1, Math.min(p.limit != null ? p.limit : 15, 200));

            r.headers.addAll(List.of(xc.name + " (" + bucket + ")", measure, "Typical (nearby periods)", "Difference", "Score"));
            for (int i : flagged.subList(0, Math.min(lim, total)))
                r.rows.add(new String[]{labels.get(i), Fmt.full(v[i]), Fmt.full(base[i]), base[i] != 0 ? Fmt.pct((v[i] - base[i]) / Math.abs(base[i])) : Fmt.signed(v[i] - base[i]),
                        String.format(Locale.US, "%+.1f", score[i])});
            r.title = p.title != null ? p.title : "Unusual " + bucket + "s: " + measure;

            ChartData cd = new ChartData();
            cd.type = ChartType.LINE;
            cd.title = r.title;
            cd.xLabel = xc.name + " (" + bucket + ")";
            cd.yLabel = measure;
            cd.labels = labels;
            cd.series = new ArrayList<>(List.of(measure, "Typical (nearby periods)"));
            cd.values.add(v);
            cd.values.add(base);
            cd.highlight.addAll(flagged);
            r.charts.add(cd);

            String filt = matchedNote(r, p);
            if (!bucket.equals(asked))
                r.facts.add("The data only spans a few " + asked + "s, so I looked at " + bucket + "s instead.");
            if (total == 0)
                r.facts.add("No " + bucket + " stands out: " + measure + " stays within its normal range across " + n + " " + bucket + "s" + filt + ".");
            else {
                r.facts.add("Looked at " + measure + " per " + bucket + " across " + n + " periods" + filt + ": " + total + (total == 1 ? " looks" : " look") + " unusual.");
                int top = flagged.get(0);
                r.facts.add("The most unusual is " + labels.get(top) + " at " + Fmt.full(v[top]) + " against a typical " + Fmt.full(base[top])
                        + (base[top] != 0 ? " (" + Fmt.pct((v[top] - base[top]) / Math.abs(base[top])) + ")" : "") + ".");
                if (total > 1) {
                    List<String> others = new ArrayList<>();
                    for (int k = 1; k < Math.min(total, 4); k++) others.add(labels.get(flagged.get(k)) + " (" + (score[flagged.get(k)] > 0 ? "high" : "low") + ")");
                    r.facts.add("Next: " + String.join(", ", others) + (total > 4 ? ", and " + (total - 4) + " more" : "") + ".");
                }
                if (bucket.equals("month") || bucket.equals("quarter"))
                    r.facts.add("Ask \"what's driving the " + (score[top] < 0 ? "drop" : "rise") + " in " + labels.get(top) + "?\" to see why.");
            }
            if (!notes.isEmpty())
                r.facts.add("Left out the partly-covered " + String.join(" and ", notes) + " so an incomplete period isn't mistaken for a dip.");
            r.facts.add("Method: each " + bucket + " is compared with nearby ones" + (bucket.equals("day") ? " (same weekday where possible)" : "")
                    + " and flagged when its robust z-score is beyond +/-" + ANOMALY_Z + ".");
        }

        static TreeMap<String, Acc> groupByPeriod(DataTable t, boolean[] keep, Column xc, Column m, String agg, String bucket) {
            TreeMap<String, Acc> by = new TreeMap<>();
            for (int i = 0; i < t.rows; i++) {
                if (!keep[i] || xc.date[i] == null) continue;
                double v = 1;
                if (m != null) {
                    v = m.num[i];
                    if (Double.isNaN(v)) continue;
                }
                by.computeIfAbsent(bucketLabel(xc.date[i], bucket), k -> new Acc()).add(v, agg.equals("median"));
            }
            return by;
        }

        // ------------------------------------------------------------ period comparison
        /** A period label ("2026-03", "2026-Q1", "2026", "2026-W09", "2026-03-15") or a range ("2026-01-01..2026-03-31") as {start, end}. */
        static LocalDate[] spanOf(String label) throws AnalysisException {
            String s = label == null ? "" : label.trim();
            if (s.contains("..")) {
                String[] ab = s.split("\\.\\.", 2);
                return new LocalDate[]{spanOf(ab[0])[0], spanOf(ab[1])[1]};
            }
            Matcher m;
            try {
                if ((m = Pattern.compile("(\\d{4})-[Ww](\\d{1,2})").matcher(s)).matches()) {
                    LocalDate d = LocalDate.of(Integer.parseInt(m.group(1)), 1, 4)
                            .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, Integer.parseInt(m.group(2))).with(java.time.DayOfWeek.MONDAY);
                    return new LocalDate[]{d, d.plusDays(6)};
                }
                if ((m = Pattern.compile("(\\d{4})-[Qq]([1-4])").matcher(s)).matches()) {
                    LocalDate d = LocalDate.of(Integer.parseInt(m.group(1)), (Integer.parseInt(m.group(2)) - 1) * 3 + 1, 1);
                    return new LocalDate[]{d, d.plusMonths(3).minusDays(1)};
                }
                if ((m = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})").matcher(s)).matches()) {
                    LocalDate d = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                    return new LocalDate[]{d, d};
                }
                if ((m = Pattern.compile("(\\d{4})-(\\d{1,2})").matcher(s)).matches()) {
                    LocalDate d = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), 1);
                    return new LocalDate[]{d, d.withDayOfMonth(d.lengthOfMonth())};
                }
                if ((m = Pattern.compile("(\\d{4})").matcher(s)).matches()) {
                    int y = Integer.parseInt(m.group(1));
                    return new LocalDate[]{LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31)};
                }
            } catch (java.time.DateTimeException | NumberFormatException e) {
                // fall through to the message below
            }
            throw new AnalysisException("I couldn't read \"" + s + "\" as a period. Use a month (2026-03), quarter (2026-Q1), year (2026), week (2026-W09), day (2026-03-15) "
                    + "or a range (2026-01-01..2026-03-31).");
        }

        static long daysIn(LocalDate[] span) { return ChronoUnit.DAYS.between(span[0], span[1]) + 1; }

        /** label -> {valueA, valueB, rowsA, rowsB} for each value of a text column. */
        static Map<String, double[]> splitBy(DataTable t, Column dim, boolean[] inA, boolean[] inB, Column m, String agg) {
            Map<String, Acc[]> acc = new LinkedHashMap<>();
            for (int i = 0; i < t.rows; i++) {
                if (!inA[i] && !inB[i]) continue;
                String k = dim.text[i] == null || dim.text[i].isBlank() ? null : dim.text[i].trim();
                if (k == null) continue;
                double v = m == null ? 1 : m.num[i];
                if (Double.isNaN(v)) continue;
                Acc[] pair = acc.computeIfAbsent(k, x -> new Acc[]{new Acc(), new Acc()});
                if (inA[i]) pair[0].add(v, agg.equals("median"));
                if (inB[i]) pair[1].add(v, agg.equals("median"));
            }
            Map<String, double[]> out = new LinkedHashMap<>();
            for (var e : acc.entrySet())
                out.put(e.getKey(), new double[]{nz0(e.getValue()[0].get(agg)), nz0(e.getValue()[1].get(agg)), e.getValue()[0].n, e.getValue()[1].n});
            return out;
        }

        static double total(DataTable t, boolean[] in, Column m, String agg) {
            Acc a = new Acc();
            for (int i = 0; i < t.rows; i++) {
                if (!in[i]) continue;
                double v = m == null ? 1 : m.num[i];
                if (!Double.isNaN(v)) a.add(v, agg.equals("median"));
            }
            return nz0(a.get(agg));
        }

        static void compare(DataTable t, Plan p, boolean[] keep, Result r) throws AnalysisException {
            Column tc = p.time != null ? need(t, p.time, "date column") : t.firstOfType(ColType.DATE);
            if (tc == null || tc.type != ColType.DATE) throw new AnalysisException("Comparing periods needs a date column, and this table doesn't have one.");
            if (p.from == null || p.to == null)
                throw new AnalysisException("Tell me which two periods to compare, for example \"compare February and March\", \"Q1 vs Q2\" or \"this month vs last month\".");
            LocalDate[] spA = spanOf(p.from), spB = spanOf(p.to);
            Column m = metricOf(t, p);
            String agg = aggOf(p, m), measure = measureLabel(agg, m);
            boolean[] inA = new boolean[t.rows], inB = new boolean[t.rows];
            int nA = 0, nB = 0;
            for (int i = 0; i < t.rows; i++) {
                LocalDate d = tc.date[i];
                if (!keep[i] || d == null) continue;
                if (!d.isBefore(spA[0]) && !d.isAfter(spA[1])) { inA[i] = true; nA++; }
                if (!d.isBefore(spB[0]) && !d.isAfter(spB[1])) { inB[i] = true; nB++; }
            }
            if (nA == 0) throw new AnalysisException("No rows fall in " + p.from + (tc.dmin != null ? " (your " + tc.name + " dates run from " + tc.dmin + " to " + tc.dmax + ")" : "") + ".");
            if (nB == 0) throw new AnalysisException("No rows fall in " + p.to + (tc.dmin != null ? " (your " + tc.name + " dates run from " + tc.dmin + " to " + tc.dmax + ")" : "") + ".");
            double totA = total(t, inA, m, agg), totB = total(t, inB, m, agg), totD = totB - totA;
            boolean add = additive(agg);

            // which column to break the change down by
            Column dim = optional(t, p.x, "breakdown");
            if (dim != null && dim.type != ColType.TEXT)
                throw new AnalysisException("I can only break a comparison down by a text column, and \"" + dim.name + "\" isn't one.");
            boolean auto = false;
            Map<String, double[]> parts = null;
            if (dim == null) {
                double best = -1;
                for (Column c : t.cols) {
                    if (c.type != ColType.TEXT) continue;
                    int dc = c.distinctCount();
                    if (dc < 2 || dc > 12) continue;
                    Map<String, double[]> cand = splitBy(t, c, inA, inB, m, agg);
                    double score = 0;
                    for (double[] v : cand.values()) score += Math.abs(v[1] - v[0]);
                    if (score > best) {
                        best = score;
                        dim = c;
                        parts = cand;
                        auto = true;
                    }
                }
            } else parts = splitBy(t, dim, inA, inB, m, agg);

            // ---- table
            r.headers.add(dim == null ? "Total" : dim.name);
            r.headers.addAll(List.of(p.from, p.to, "Change", "Change %"));
            if (add) r.headers.add("Share of change");
            r.rows.add(compareRow("Total", totA, totB, add ? 1.0 : Double.NaN, add));
            List<Map.Entry<String, double[]>> ranked = new ArrayList<>();
            if (parts != null) {
                ranked.addAll(parts.entrySet());
                ranked.sort((x, y) -> Double.compare(Math.abs(y.getValue()[1] - y.getValue()[0]), Math.abs(x.getValue()[1] - x.getValue()[0])));
            }
            int lim = Math.max(1, Math.min(p.limit != null ? p.limit : 12, 100));
            List<Map.Entry<String, double[]>> shown = ranked.subList(0, Math.min(lim, ranked.size()));
            for (var e : shown)
                r.rows.add(compareRow(e.getKey(), e.getValue()[0], e.getValue()[1], add && totD != 0 ? (e.getValue()[1] - e.getValue()[0]) / totD : Double.NaN, add));
            r.title = p.title != null ? p.title : measure + ": " + p.from + " vs " + p.to + (dim != null ? " by " + dim.name : "");

            // ---- charts: side by side, then the change itself
            ChartData side = new ChartData();
            side.title = r.title;
            side.yLabel = measure;
            side.series = new ArrayList<>(List.of(p.from, p.to));
            double[] va = new double[0], vb = new double[0];
            if (dim == null) {
                side.labels = new ArrayList<>(List.of("Total"));
                va = new double[]{totA};
                vb = new double[]{totB};
            } else {
                List<Map.Entry<String, double[]>> top = new ArrayList<>(ranked);
                top.sort((x, y) -> Double.compare(Math.max(Math.abs(y.getValue()[0]), Math.abs(y.getValue()[1])), Math.max(Math.abs(x.getValue()[0]), Math.abs(x.getValue()[1]))));
                top = top.subList(0, Math.min(10, top.size()));
                va = new double[top.size()];
                vb = new double[top.size()];
                for (int i = 0; i < va.length; i++) {
                    side.labels.add(top.get(i).getKey());
                    va[i] = top.get(i).getValue()[0];
                    vb[i] = top.get(i).getValue()[1];
                }
            }
            side.values.add(va);
            side.values.add(vb);
            boolean neg = false;
            int maxLab = 0;
            for (double v : va) if (v < 0) neg = true;
            for (double v : vb) if (v < 0) neg = true;
            for (String s : side.labels) maxLab = Math.max(maxLab, s.length());
            side.type = side.labels.size() > 6 || maxLab > 12 ? ChartType.HBAR : ChartType.BAR;
            r.charts.add(side);
            if (dim != null && shown.size() >= 2) {
                ChartData chg = new ChartData();
                chg.title = "Change in " + measure.toLowerCase(Locale.ROOT) + ", " + p.from + " to " + p.to + ", by " + dim.name;
                chg.type = ChartType.HBAR;
                chg.signColors = true;
                chg.yLabel = "Change";
                chg.series = new ArrayList<>(List.of("Change"));
                double[] dv = new double[Math.min(10, shown.size())];
                for (int i = 0; i < dv.length; i++) {
                    chg.labels.add(shown.get(i).getKey());
                    dv[i] = shown.get(i).getValue()[1] - shown.get(i).getValue()[0];
                }
                chg.values.add(dv);
                r.charts.add(chg);
            }

            // ---- plain-English facts
            String filt = matchedNote(r, p);
            r.facts.add(measure + " was " + Fmt.full(totA) + " in " + p.from + " and " + Fmt.full(totB) + " in " + p.to + ": "
                    + (totD >= 0 ? "up " : "down ") + Fmt.full(Math.abs(totD)) + (totA != 0 ? " (" + Fmt.pct(totD / Math.abs(totA)) + ")" : "") + filt + ".");
            r.facts.add("That is based on " + Fmt.full(nA) + " rows in " + p.from + " and " + Fmt.full(nB) + " rows in " + p.to + ".");
            if (dim != null && !ranked.isEmpty()) {
                Map.Entry<String, double[]> lead = null, counter = null;
                for (var e : ranked) {
                    double d = e.getValue()[1] - e.getValue()[0];
                    if (lead == null && (totD == 0 || d * totD > 0)) lead = e;
                    if (counter == null && totD != 0 && d * totD < 0) counter = e;
                }
                if (lead == null) lead = ranked.get(0);
                double ld = lead.getValue()[1] - lead.getValue()[0];
                StringBuilder sb = new StringBuilder("By " + dim.name + ", the biggest contributor was " + lead.getKey() + " (" + Fmt.signed(ld)
                        + (lead.getValue()[0] != 0 ? ", " + Fmt.pct(ld / Math.abs(lead.getValue()[0])) : "")
                        + (add && totD != 0 ? ", " + Fmt.share(ld / totD) + " of the net change" : "") + ")");
                if (counter != null) {
                    double cd = counter.getValue()[1] - counter.getValue()[0];
                    sb.append("; the biggest move the other way was ").append(counter.getKey()).append(" (").append(Fmt.signed(cd))
                            .append(counter.getValue()[0] != 0 ? ", " + Fmt.pct(cd / Math.abs(counter.getValue()[0])) : "").append(")");
                }
                r.facts.add(sb.append(".").toString());
                if (auto) r.facts.add("I broke it down by " + dim.name + " because that column moved the most; add \"by region\" (or another column) to choose a different one.");
            }
            long la = daysIn(spA), lb = daysIn(spB);
            if (add && Math.abs(la - lb) > 0.1 * Math.max(la, lb))
                r.facts.add("The periods differ in length (" + la + " vs " + lb + " days), so the totals aren't like for like. Ask for the average instead for a fairer comparison.");
            for (String[] pe : new String[][]{{p.from, spA[0].toString(), spA[1].toString()}, {p.to, spB[0].toString(), spB[1].toString()}}) {
                LocalDate s0 = LocalDate.parse(pe[1]), e0 = LocalDate.parse(pe[2]);
                if (tc.dmax != null && e0.isAfter(tc.dmax) && !s0.isAfter(tc.dmax))
                    r.facts.add("Your data ends on " + tc.dmax + ", so " + pe[0] + " is only partly covered and its total will look lower than a full period.");
                else if (tc.dmin != null && s0.isBefore(tc.dmin) && !e0.isBefore(tc.dmin))
                    r.facts.add("Your data starts on " + tc.dmin + ", so " + pe[0] + " is only partly covered.");
            }
            if (p.to.matches("\\d{4}-\\d{2}") && add)
                r.facts.add("Ask \"what's driving the " + (totD < 0 ? "drop" : "rise") + " in " + p.to + "?\" for a full driver analysis across every column.");
        }

        private static String[] compareRow(String label, double a, double b, double share, boolean add) {
            double d = b - a;
            List<String> row = new ArrayList<>(List.of(label, Fmt.full(a), Fmt.full(b), Fmt.signed(d), a != 0 ? Fmt.pct(d / Math.abs(a)) : "n/a"));
            if (add) row.add(Double.isNaN(share) ? "" : Fmt.share(share));
            return row.toArray(new String[0]);
        }

        // ------------------------------------------------------------ forecast
        static final int MIN_FORECAST_PERIODS = 6;

        /** Trend (least squares) plus an optional repeating seasonal pattern; sigma is the residual spread. */
        record Model(double a, double b, double[] season, double sigma, int n, double sxx, boolean flat) {
            double at(int t) { return a + b * t + season[t % season.length]; }

            double spread(int t) {
                double tbar = (n - 1) / 2.0;
                return sigma * Math.sqrt(1.0 + 1.0 / n + (t - tbar) * (t - tbar) / sxx);
            }
        }

        static int seasonLength(String bucket, int n) {
            switch (bucket) {
                case "day": return n >= 14 ? 7 : 1;
                case "week": return n >= 104 ? 52 : 1;
                case "month": return n >= 24 ? 12 : 1;
                case "quarter": return n >= 8 ? 4 : 1;
                default: return 1;
            }
        }

        static Model fitModel(double[] y, int m) {
            int n = y.length;
            double[] s = new double[Math.max(1, m)];
            double a = 0, b = 0, tbar = (n - 1) / 2.0, sxx = 0;
            for (int i = 0; i < n; i++) sxx += (i - tbar) * (i - tbar);
            for (int iter = 0; iter < (m > 1 ? 4 : 1); iter++) {
                double ybar = 0;
                double[] z = new double[n];
                for (int i = 0; i < n; i++) {
                    z[i] = y[i] - s[i % s.length];
                    ybar += z[i];
                }
                ybar /= n;
                double sxy = 0;
                for (int i = 0; i < n; i++) sxy += (i - tbar) * (z[i] - ybar);
                b = sxx > 0 ? sxy / sxx : 0;
                a = ybar - b * tbar;
                if (m > 1) {
                    double[] sum = new double[m];
                    int[] cnt = new int[m];
                    for (int i = 0; i < n; i++) {
                        sum[i % m] += y[i] - (a + b * i);
                        cnt[i % m]++;
                    }
                    double mean = 0;
                    for (int j = 0; j < m; j++) {
                        s[j] = cnt[j] > 0 ? sum[j] / cnt[j] : 0;
                        mean += s[j];
                    }
                    mean /= m;
                    for (int j = 0; j < m; j++) s[j] -= mean;
                }
            }
            double sse = 0;
            for (int i = 0; i < n; i++) {
                double e = y[i] - (a + b * i + s[i % s.length]);
                sse += e * e;
            }
            int dof = n - 2 - (m > 1 ? m - 1 : 0);
            if (dof < 1) dof = Math.max(1, n - 2);
            return new Model(a, b, s, Math.sqrt(sse / dof), n, Math.max(sxx, 1e-9), false);
        }

        /** The trend model, unless its slope is within the noise: then the projection holds the recent level (plus any seasonal pattern). */
        static Model chooseModel(double[] y, int m, int recentN) {
            Model fit = fitModel(y, m);
            double slopeT = fit.b() / (fit.sigma() / Math.sqrt(fit.sxx()) + 1e-12);
            if (Math.abs(slopeT) >= 2) return fit;
            int n = y.length;
            double[] z = new double[n];
            double mean = 0, lvl = 0;
            for (int i = 0; i < n; i++) {
                z[i] = y[i] - fit.season()[i % fit.season().length];
                mean += z[i];
            }
            mean /= n;
            for (int i = n - recentN; i < n; i++) lvl += z[i];
            lvl /= recentN;
            double ss = 0;
            for (double v : z) ss += (v - mean) * (v - mean);
            return new Model(lvl, 0, fit.season(), Math.sqrt(ss / Math.max(1, n - 1)), recentN, 1e12, true);
        }

        static LocalDate nextStart(LocalDate d, String bucket) {
            switch (bucket) {
                case "week": return d.plusWeeks(1);
                case "month": return d.plusMonths(1);
                case "quarter": return d.plusMonths(3);
                case "year": return d.plusYears(1);
                default: return d.plusDays(1);
            }
        }

        static int defaultHorizon(String bucket) {
            switch (bucket) {
                case "day": return 14;
                case "week": return 8;
                case "month": return 3;
                case "quarter": return 2;
                default: return 2;
            }
        }

        /** How many of the next-finer periods make up one of this size (used when the history is too short for the asked size). */
        static int stepRatio(String from, String to) {
            if (from.equals("year") && to.equals("quarter")) return 4;
            if (from.equals("quarter") && to.equals("month")) return 3;
            if (from.equals("month") && to.equals("week")) return 4;
            if (from.equals("week") && to.equals("day")) return 7;
            return 1;
        }

        static void forecast(DataTable t, Plan p, boolean[] keep, Result r) throws AnalysisException {
            Column xc = p.x != null ? need(t, p.x, "date column") : t.firstOfType(ColType.DATE);
            if (xc == null || xc.type != ColType.DATE) throw new AnalysisException("A forecast needs a date column, and this table doesn't have one.");
            Column m = metricOf(t, p);
            String agg = aggOf(p, m), measure = measureLabel(agg, m);
            boolean add = additive(agg);
            LocalDate mn = null, mx = null;
            for (int i = 0; i < t.rows; i++) {
                if (!keep[i] || xc.date[i] == null) continue;
                if (mn == null || xc.date[i].isBefore(mn)) mn = xc.date[i];
                if (mx == null || xc.date[i].isAfter(mx)) mx = xc.date[i];
            }
            if (mn == null) throw new AnalysisException("None of the matching rows has a usable " + xc.name + " date.");

            String bucket = bucketOf(p.bucket, xc, keep);
            if (bucket.equals("weekday")) bucket = "day";
            String asked = bucket;
            List<String> notes = new ArrayList<>();

            // history on a gap-free calendar grid (a missing period is a real zero for sums and counts)
            List<String> labels;
            double[] y;
            int missing;
            while (true) {
                TreeMap<String, Acc> byP = groupByPeriod(t, keep, xc, m, agg, bucket);
                labels = new ArrayList<>();
                List<Double> vals = new ArrayList<>();
                missing = 0;
                double carry = Double.NaN;
                for (LocalDate d = periodStart(mn, bucket); !d.isAfter(periodStart(mx, bucket)); d = nextStart(d, bucket)) {
                    String lab = bucketLabel(d, bucket);
                    Acc a = byP.get(lab);
                    double v;
                    if (a != null) v = nz0(a.get(agg));
                    else {
                        missing++;
                        v = add ? 0 : carry;
                    }
                    if (!Double.isNaN(v)) carry = v;
                    labels.add(lab);
                    vals.add(v);
                }
                for (int i = 0; i < vals.size(); i++) if (Double.isNaN(vals.get(i))) vals.set(i, carry);
                y = vals.stream().mapToDouble(Double::doubleValue).toArray();
                // a part-covered first or last period would drag the trend, so it is left out when enough periods remain
                boolean partialLast = add && !bucket.equals("day") && periodEnd(mx, bucket).isAfter(mx);
                boolean partialFirst = add && !bucket.equals("day") && periodStart(mn, bucket).isBefore(mn);
                int drop = (partialLast ? 1 : 0) + (partialFirst ? 1 : 0);
                if (labels.size() - drop >= MIN_FORECAST_PERIODS || bucket.equals("day")) {
                    if (partialLast && labels.size() - drop >= MIN_FORECAST_PERIODS) {
                        notes.add(labels.get(labels.size() - 1));
                        labels.remove(labels.size() - 1);
                        y = Arrays.copyOf(y, y.length - 1);
                    }
                    if (partialFirst && labels.size() >= MIN_FORECAST_PERIODS + 1) {
                        notes.add(labels.get(0));
                        labels.remove(0);
                        y = Arrays.copyOfRange(y, 1, y.length);
                    }
                    break;
                }
                bucket = BUCKET_ORDER[Math.min(BUCKET_ORDER.length - 1, Arrays.asList(BUCKET_ORDER).indexOf(bucket) + 1)];
            }
            int n = y.length;
            if (n < MIN_FORECAST_PERIODS)
                throw new AnalysisException("There are only " + n + " " + bucket + "s of history, and I need at least " + MIN_FORECAST_PERIODS + " to project a trend.");

            // horizon
            int ratio = 1;
            if (!bucket.equals(asked)) {
                String cur = asked;
                while (!cur.equals(bucket)) {
                    String nxt = BUCKET_ORDER[Arrays.asList(BUCKET_ORDER).indexOf(cur) + 1];
                    ratio *= stepRatio(cur, nxt);
                    cur = nxt;
                }
            }
            int h = p.limit != null ? p.limit * ratio : defaultHorizon(bucket);
            int hmax = Math.max(1, Math.min(60, n / 2));
            if (h > hmax) {
                notes.add("__cap__" + hmax);
                h = hmax;
            }
            int season = seasonLength(bucket, n);
            int recentN = Math.min(n, season > 1 ? season : 4);
            Model model = chooseModel(y, season, recentN);
            double lastActual = 0;
            for (int i = n - recentN; i < n; i++) lastActual += y[i];
            lastActual /= recentN;
            boolean nonNeg = true;
            for (double v : y) if (v < 0) nonNeg = false;
            nonNeg = nonNeg && add;
            double[] fc = new double[h], lo = new double[h], hi = new double[h];
            LocalDate d = periodStart(mx, bucket);
            if (!labels.isEmpty()) {
                try {
                    d = spanOf(labels.get(labels.size() - 1))[0];
                } catch (AnalysisException ignored) {
                    // labels are produced by bucketLabel, which spanOf understands
                }
            }
            List<String> future = new ArrayList<>();
            for (int k = 0; k < h; k++) {
                d = nextStart(d, bucket);
                future.add(bucketLabel(d, bucket));
                int tt = n + k;
                double f = model.at(tt), se = 1.96 * model.spread(tt);
                if (nonNeg) f = Math.max(0, f);
                fc[k] = f;
                lo[k] = nonNeg ? Math.max(0, f - se) : f - se;
                hi[k] = f + se;
            }

            // ---- backtest: hide the last few periods, forecast them, and see how wrong that was
            Double modelErr = null, naiveErr = null;
            int kb = Math.max(1, Math.min(h, n / 3));
            if (n - kb >= 5) {
                double[] train = Arrays.copyOf(y, n - kb);
                int mt = seasonLength(bucket, train.length);
                Model bm = chooseModel(train, mt, Math.min(train.length, mt > 1 ? mt : 4));
                double sa = 0, sn = 0, sy = 0;
                for (int k = 0; k < kb; k++) {
                    double actual = y[n - kb + k], pred = bm.at(train.length + k);
                    if (nonNeg) pred = Math.max(0, pred);
                    double naive = mt > 1 ? train[train.length - mt + (k % mt)] : train[train.length - 1];
                    sa += Math.abs(actual - pred);
                    sn += Math.abs(actual - naive);
                    sy += Math.abs(actual);
                }
                if (sy > 0) {
                    modelErr = sa / sy;
                    naiveErr = sn / sy;
                }
            }

            // ---- table
            r.headers.addAll(List.of(xc.name + " (" + bucket + ")", "Forecast", "Low (95%)", "High (95%)", "vs recent average"));
            for (int k = 0; k < h; k++)
                r.rows.add(new String[]{future.get(k), Fmt.full(fc[k]), Fmt.full(lo[k]), Fmt.full(hi[k]),
                        lastActual != 0 ? Fmt.pct((fc[k] - lastActual) / Math.abs(lastActual)) : "n/a"});
            r.title = p.title != null ? p.title : "Forecast: " + measure + ", next " + h + " " + bucket + (h == 1 ? "" : "s");

            // ---- chart: history, then the projection with its range
            ChartData cd = new ChartData();
            cd.type = ChartType.LINE;
            cd.fixedType = true;
            cd.title = r.title;
            cd.xLabel = xc.name + " (" + bucket + ")";
            cd.yLabel = measure;
            cd.labels = new ArrayList<>(labels);
            cd.labels.addAll(future);
            int total = n + h;
            double[] act = new double[total], fline = new double[total], blo = new double[total], bhi = new double[total];
            Arrays.fill(act, Double.NaN);
            Arrays.fill(fline, Double.NaN);
            Arrays.fill(blo, Double.NaN);
            Arrays.fill(bhi, Double.NaN);
            for (int i = 0; i < n; i++) act[i] = y[i];
            fline[n - 1] = blo[n - 1] = bhi[n - 1] = y[n - 1];   // joins the projection to the last real point
            for (int k = 0; k < h; k++) {
                fline[n + k] = fc[k];
                blo[n + k] = lo[k];
                bhi[n + k] = hi[k];
            }
            cd.series = new ArrayList<>(List.of("Actual", "Forecast"));
            cd.values.add(act);
            cd.values.add(fline);
            cd.dashed.add(1);
            cd.bandLo = blo;
            cd.bandHi = bhi;
            cd.forecastFrom = n;
            cd.note = "Shaded band: about 95% range";
            r.charts.add(cd);

            // ---- plain-English facts
            String filt = matchedNote(r, p);
            int li = h - 1;
            r.facts.add("Based on " + n + " " + bucket + "s of history (" + labels.get(0) + " to " + labels.get(n - 1) + ")" + filt + ", " + measure + " is projected at "
                    + Fmt.full(fc[li]) + " in " + future.get(li) + (lastActual != 0 ? " (" + Fmt.pct((fc[li] - lastActual) / Math.abs(lastActual)) + " vs the recent average of " + Fmt.full(lastActual) + ")" : "")
                    + ", most likely between " + Fmt.full(lo[li]) + " and " + Fmt.full(hi[li]) + ".");
            if (add && h > 1) {
                double sum = 0;
                for (double f : fc) sum += f;
                r.facts.add("Over the next " + h + " " + bucket + "s that adds up to about " + Fmt.full(sum) + ".");
            }
            double mean = 0;
            for (double v : y) mean += v;
            mean /= n;
            if (model.flat())
                r.facts.add("There is no clear trend: the " + bucket + "-to-" + bucket + " movement is within the normal noise, so the projection holds the recent level" + (season > 1 ? " plus the repeating pattern" : "") + ".");
            else
                r.facts.add("The underlying trend is " + (model.b() > 0 ? "up" : "down") + " about " + Fmt.full(Math.abs(model.b())) + " per " + bucket
                        + (mean != 0 ? " (" + String.format(Locale.US, "%.1f%%", Math.abs(model.b() / mean) * 100) + " of the average)" : "") + ".");
            if (season > 1) r.facts.add("A repeating pattern every " + season + " " + bucket + "s was included.");
            else if (!bucket.equals("year") && seasonLength(bucket, 1000) > 1)
                r.facts.add("There isn't enough history to model a repeating " + (bucket.equals("day") ? "weekly" : bucket.equals("month") ? "yearly" : bucket.equals("quarter") ? "yearly" : "yearly")
                        + " pattern (that takes at least two full cycles), so none was included.");
            if (modelErr != null) {
                r.facts.add("Reliability check: when I hid the last " + kb + " " + bucket + (kb == 1 ? "" : "s") + " and forecast them from the earlier history, the average error was "
                        + String.format(Locale.US, "%.1f%%", modelErr * 100) + " (simply repeating the previous " + (season > 1 ? "cycle" : "period") + " would have been off by "
                        + String.format(Locale.US, "%.1f%%", naiveErr * 100) + ")"
                        + (modelErr > naiveErr * 1.05 ? ", so treat this projection with extra caution." : "."));
            }
            if (n < 12) r.facts.add("With only " + n + " " + bucket + "s of history this is a rough guide at best" + (modelErr == null ? ", and there isn't enough to test it against the past" : "") + ".");
            if (!bucket.equals(asked)) r.facts.add("There isn't enough history at " + asked + " level, so I worked in " + bucket + "s instead.");
            if (missing > 0) r.facts.add(missing + " " + bucket + (missing == 1 ? "" : "s") + " had no rows" + (add ? " and counted as zero." : " and carried the previous value."));
            for (String nt : notes) {
                if (nt.startsWith("__cap__")) r.facts.add("I limited the projection to " + nt.substring(7) + " " + bucket + "s, because looking much further ahead than half your history is guesswork.");
            }
            List<String> partial = new ArrayList<>();
            for (String nt : notes) if (!nt.startsWith("__cap__")) partial.add(nt);
            if (!partial.isEmpty()) r.facts.add("Left out the partly-covered " + String.join(" and ", partial) + " so an incomplete period isn't read as a dip.");
            r.facts.add("This carries past patterns forward; it can't know about promotions, closures or one-off events, and the further ahead it looks the less reliable it is.");
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
                if (!weekday) {   // drill-down context
                    cd.basePlan = p;
                    if (timeX) {
                        cd.periodX = true;
                        cd.timeCol = xc.name;
                        cd.bucket = xb;
                    } else {
                        cd.labelFilters = new ArrayList<>();
                        for (String lab : xs) cd.labelFilters.add(List.of(new Filter(xc.name, "=", List.of(lab))));
                    }
                    if (sc != null && sc.type == ColType.TEXT) {
                        cd.seriesFilters = new ArrayList<>();
                        for (String sname : ss) cd.seriesFilters.add(List.of(new Filter(sc.name, "=", List.of(sname))));
                    }
                }
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

        // ------------------------------------------------------------ correlate
        static final class PairStats {
            double[] xs, ys;
            int n;
            double r, rho, slope, intercept, r2, mx, my;
        }

        static PairStats pair(Column a, Column b, boolean[] keep) {
            int cnt = 0;
            for (int i = 0; i < keep.length; i++) if (keep[i] && !Double.isNaN(a.num[i]) && !Double.isNaN(b.num[i])) cnt++;
            if (cnt < 3) return null;
            double[] xs = new double[cnt], ys = new double[cnt];
            int k = 0;
            for (int i = 0; i < keep.length; i++)
                if (keep[i] && !Double.isNaN(a.num[i]) && !Double.isNaN(b.num[i])) {
                    xs[k] = a.num[i];
                    ys[k] = b.num[i];
                    k++;
                }
            double mx = 0, my = 0;
            for (int i = 0; i < cnt; i++) {
                mx += xs[i];
                my += ys[i];
            }
            mx /= cnt;
            my /= cnt;
            double sxx = 0, syy = 0, sxy = 0;
            for (int i = 0; i < cnt; i++) {
                sxx += (xs[i] - mx) * (xs[i] - mx);
                syy += (ys[i] - my) * (ys[i] - my);
                sxy += (xs[i] - mx) * (ys[i] - my);
            }
            if (sxx == 0 || syy == 0) return null;
            PairStats ps = new PairStats();
            ps.xs = xs;
            ps.ys = ys;
            ps.n = cnt;
            ps.mx = mx;
            ps.my = my;
            ps.r = sxy / Math.sqrt(sxx * syy);
            ps.slope = sxy / sxx;
            ps.intercept = my - ps.slope * mx;
            ps.r2 = ps.r * ps.r;
            return ps;
        }

        static double[] ranks(double[] v) {
            Integer[] idx = new Integer[v.length];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            Arrays.sort(idx, Comparator.comparingDouble(i -> v[i]));
            double[] rk = new double[v.length];
            for (int i = 0; i < idx.length; ) {
                int j = i;
                while (j + 1 < idx.length && v[idx[j + 1]] == v[idx[i]]) j++;
                double avg = (i + j) / 2.0 + 1;
                for (int k = i; k <= j; k++) rk[idx[k]] = avg;
                i = j + 1;
            }
            return rk;
        }

        static double pearson(double[] a, double[] b) {
            int n = a.length;
            double ma = 0, mb = 0;
            for (int i = 0; i < n; i++) {
                ma += a[i];
                mb += b[i];
            }
            ma /= n;
            mb /= n;
            double sa = 0, sb = 0, sab = 0;
            for (int i = 0; i < n; i++) {
                sa += (a[i] - ma) * (a[i] - ma);
                sb += (b[i] - mb) * (b[i] - mb);
                sab += (a[i] - ma) * (b[i] - mb);
            }
            return sa == 0 || sb == 0 ? Double.NaN : sab / Math.sqrt(sa * sb);
        }

        static String strength(double ar) {
            return ar < 0.1 ? "negligible" : ar < 0.3 ? "weak" : ar < 0.5 ? "moderate" : ar < 0.7 ? "strong" : "very strong";
        }

        static String sig(double v) {
            double a = Math.abs(v);
            return a >= 100 ? String.format(Locale.US, "%,.0f", v) : a >= 1 ? String.format(Locale.US, "%,.2f", v) : String.format(Locale.US, "%.4g", v);
        }

        static void correlate(DataTable t, Plan p, boolean[] keep, Result r) throws AnalysisException {
            List<Column> nums = t.colsOfType(ColType.NUMBER);
            if (nums.size() < 2)
                throw new AnalysisException("Correlation needs at least two numeric columns, and this table has " + nums.size() + ".");
            Column xc = optional(t, p.x, "x-axis"), yc = optional(t, p.metric, "y-axis");
            for (Column c : new Column[]{xc, yc})
                if (c != null && c.type != ColType.NUMBER)
                    throw new AnalysisException("Column \"" + c.name + "\" isn't numeric, so I can't correlate it. Numeric columns: " + nums.stream().map(n -> n.name).toList() + ".");
            if (xc != null && xc == yc) throw new AnalysisException("Pick two different columns to compare.");
            Column sc = optional(t, p.series, "colour grouping");
            if (sc != null && sc.type != ColType.TEXT) sc = null;

            if (xc == null || yc == null) {
                // correlation matrix; if one column was named, find its strongest partner
                List<Column> cs = new ArrayList<>(nums.size() > 12 ? nums.subList(0, 12) : nums);
                Column fixed = xc != null ? xc : yc;
                if (fixed != null && !cs.contains(fixed)) cs.add(fixed);
                int nc = cs.size();
                double[][] rm = new double[nc][nc];
                List<Object[]> pairs = new ArrayList<>();
                for (int i = 0; i < nc; i++) {
                    rm[i][i] = 1;
                    for (int j = i + 1; j < nc; j++) {
                        PairStats q = pair(cs.get(i), cs.get(j), keep);
                        rm[i][j] = rm[j][i] = q == null ? Double.NaN : q.r;
                        if (q != null) pairs.add(new Object[]{cs.get(i), cs.get(j), q.r, q.n});
                    }
                }
                pairs.sort((a, b) -> Double.compare(Math.abs((double) b[2]), Math.abs((double) a[2])));
                Object[] best = null;
                for (Object[] pr : pairs)
                    if (fixed == null || pr[0] == fixed || pr[1] == fixed) {
                        best = pr;
                        break;
                    }
                if (best == null) throw new AnalysisException("I couldn't find two numeric columns with enough overlapping values to correlate.");
                Column a = (Column) best[0], b = (Column) best[1];
                if (fixed != null && b == fixed) {
                    Column tmp = a;
                    a = b;
                    b = tmp;
                }
                xc = a;
                yc = b;
                r.headers.add("");
                for (Column c : cs) r.headers.add(c.name);
                for (int i = 0; i < nc; i++) {
                    String[] row = new String[nc + 1];
                    row[0] = cs.get(i).name;
                    for (int j = 0; j < nc; j++) row[j + 1] = Double.isNaN(rm[i][j]) ? "" : String.format(Locale.US, "%.2f", rm[i][j]);
                    r.rows.add(row);
                }
                StringBuilder sb = new StringBuilder();
                int shown = 0;
                for (Object[] pr : pairs) {
                    double rr = (double) pr[2];
                    if (Math.abs(rr) < 0.3 || shown >= 3) break;
                    sb.append(shown++ > 0 ? "; " : "").append(((Column) pr[0]).name).append(" and ").append(((Column) pr[1]).name)
                            .append(" (r = ").append(String.format(Locale.US, "%.2f", rr)).append(", ").append(strength(Math.abs(rr))).append(rr > 0 ? " positive" : " negative").append(")");
                }
                r.facts.add(shown == 0
                        ? "None of the numeric columns has a notable linear relationship with another (every |r| is below 0.3)."
                        : "Strongest relationships between numeric columns: " + sb + ".");
            }

            PairStats ps = pair(xc, yc, keep);
            if (ps == null)
                throw new AnalysisException("There aren't enough rows with values in both \"" + xc.name + "\" and \"" + yc.name + "\" (or one of them never changes).");
            ps.rho = pearson(ranks(ps.xs), ranks(ps.ys));
            if (r.rows.isEmpty()) {   // pair mode: show the statistics
                r.headers.addAll(List.of("Statistic", "Value"));
                r.rows.add(new String[]{"Rows with both values", String.format(Locale.US, "%,d", ps.n)});
                r.rows.add(new String[]{"Pearson r", String.format(Locale.US, "%.3f", ps.r)});
                r.rows.add(new String[]{"Spearman rho (rank)", String.format(Locale.US, "%.3f", ps.rho)});
                r.rows.add(new String[]{"R-squared", String.format(Locale.US, "%.1f%%", ps.r2 * 100)});
                r.rows.add(new String[]{"Slope (" + yc.name + " per 1 " + xc.name + ")", sig(ps.slope)});
                r.rows.add(new String[]{"Intercept", sig(ps.intercept)});
                r.rows.add(new String[]{"Mean of " + xc.name, Fmt.full(ps.mx)});
                r.rows.add(new String[]{"Mean of " + yc.name, Fmt.full(ps.my)});
            }
            r.title = p.title != null ? p.title : yc.name + " vs " + xc.name;

            // facts
            double ar = Math.abs(ps.r);
            List<String> f = new ArrayList<>();
            f.add(xc.name + " and " + yc.name + (ar < 0.1 ? " show no meaningful linear relationship" : " show a " + strength(ar) + (ps.r > 0 ? " positive" : " negative") + " correlation")
                    + " (r = " + String.format(Locale.US, "%.2f", ps.r) + ", n = " + String.format(Locale.US, "%,d", ps.n) + ", R-squared " + String.format(Locale.US, "%.0f%%", ps.r2 * 100) + ").");
            if (ar >= 0.1)
                f.add("On average, each +1 in " + xc.name + " goes with " + (ps.slope > 0 ? "a rise" : "a fall") + " of " + sig(Math.abs(ps.slope)) + " in " + yc.name + " (straight-line fit).");
            if (ar < 1.96 / Math.sqrt(ps.n))
                f.add("That is within what random noise alone would produce at this sample size, so don't read much into it.");
            if (Math.abs(ps.rho - ps.r) > 0.15)
                f.add("The rank correlation (Spearman " + String.format(Locale.US, "%.2f", ps.rho) + ") differs from r, which suggests a curved relationship or influential outliers.");
            f.add("Correlation does not prove that one causes the other.");
            r.facts.addAll(f);

            // scatter chart (sampled if there are very many points)
            Map<String, Integer> gcount = new HashMap<>();
            if (sc != null)
                for (int i = 0; i < t.rows; i++)
                    if (keep[i] && !Double.isNaN(xc.num[i]) && !Double.isNaN(yc.num[i]) && sc.text[i] != null && !sc.text[i].isBlank()) gcount.merge(sc.text[i].trim(), 1, Integer::sum);
            List<String> groups = new ArrayList<>(gcount.keySet());
            groups.sort((a, b) -> gcount.get(b) - gcount.get(a));
            if (groups.size() > 6) groups = new ArrayList<>(groups.subList(0, 6));
            if (sc == null || groups.isEmpty()) groups = List.of(yc.name + " vs " + xc.name);
            boolean grouped = sc != null && gcount.size() > 0;
            int stride = Math.max(1, (int) Math.ceil(ps.n / 4000.0));
            List<List<double[]>> pts = new ArrayList<>();
            for (int g = 0; g < groups.size(); g++) pts.add(new ArrayList<>());
            int seen = 0;
            for (int i = 0; i < t.rows; i++) {
                if (!keep[i] || Double.isNaN(xc.num[i]) || Double.isNaN(yc.num[i])) continue;
                if (seen++ % stride != 0) continue;
                int g = 0;
                if (grouped) {
                    g = sc.text[i] == null ? -1 : groups.indexOf(sc.text[i].trim());
                    if (g < 0) continue;
                }
                pts.get(g).add(new double[]{xc.num[i], yc.num[i]});
            }
            ChartData cd = new ChartData();
            cd.type = ChartType.SCATTER;
            cd.title = r.title;
            cd.xLabel = xc.name;
            cd.yLabel = yc.name;
            for (int g = 0; g < groups.size(); g++) {
                cd.series.add(groups.get(g));
                double[] gx = new double[pts.get(g).size()], gy = new double[gx.length];
                for (int k = 0; k < gx.length; k++) {
                    gx[k] = pts.get(g).get(k)[0];
                    gy[k] = pts.get(g).get(k)[1];
                }
                cd.sx.add(gx);
                cd.sy.add(gy);
            }
            cd.fit = new double[]{ps.slope, ps.intercept, ps.r, ps.n};
            r.charts.add(cd);
            if (stride > 1) r.facts.add("The scatter plot shows a sample of about " + String.format(Locale.US, "%,d", ps.n / stride) + " of the " + String.format(Locale.US, "%,d", ps.n) + " points; the statistics use all of them.");
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
                    DriverRow dr = driverRow(1, dims.get(d).name + " = " + e.getKey(), e.getValue(), agg, add, totalDelta, ratio,
                            List.of(new Filter(dims.get(d).name, "=", List.of(e.getKey()))));
                    if (dr != null) singles.add(dr);
                }
            Map<String, DriverRow> singleMap = new HashMap<>();
            for (DriverRow d : singles) singleMap.put(d.label(), d);
            for (var e : combo.entrySet()) {
                String[] parts = e.getKey().split("\u0001");
                String la = dims.get(Integer.parseInt(parts[0])).name + " = " + parts[1];
                String lb = dims.get(Integer.parseInt(parts[2])).name + " = " + parts[3];
                DriverRow dr = driverRow(2, la + " & " + lb, e.getValue(), agg, add, totalDelta, ratio,
                        List.of(new Filter(dims.get(Integer.parseInt(parts[0])).name, "=", List.of(parts[1])),
                                new Filter(dims.get(Integer.parseInt(parts[2])).name, "=", List.of(parts[3]))));
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
            trend.basePlan = p;
            trend.periodX = true;
            trend.timeCol = tc.name;
            trend.bucket = bucket;
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
            bars.basePlan = p;
            bars.labelFilters = new ArrayList<>();
            for (DriverRow d : plot) bars.labelFilters.add(d.filters());
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
        static DriverRow driverRow(int level, String label, Acc[] a, String agg, boolean add, double totalDelta, double ratio, List<Filter> fl) {
            double f = a[0] == null ? (add ? 0 : Double.NaN) : a[0].get(agg);
            double t = a[1] == null ? (add ? 0 : Double.NaN) : a[1].get(agg);
            if (Double.isNaN(f) || Double.isNaN(t)) return null;
            double d = t - f;
            if (d == 0) return null;
            double excess = add && !Double.isNaN(ratio) && !Double.isInfinite(ratio) ? t - f * ratio : d;
            return new DriverRow(level, label, f, t, d, excess, add && totalDelta != 0 ? d / totalDelta : Double.NaN, fl);
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

        static final Pattern STRONG = Pattern.compile("^ (now|and|also|then|but|instead|same|only|just|what about|how about|what if|ok|okay|"
                + "colou?r|split|group|segment|stack|break (it|that|this|them) down|drill|zoom|filter|exclude|excluding|without|remove|clear|reset|"
                + "show (it|that|them|this)|make (it|that|this)|change (it|that|this)|switch|as a|compare (it|that|this)) ");
        static final Pattern WEAK = Pattern.compile("^ (top|bottom|best|worst|per|by|for|in|with) ");

        static Plan plan(String question, DataTable t) { return plan(question, t, null); }

        /** With a previous plan, short follow-ups ("now split that by channel", "only North") modify it instead of starting over. */
        static Plan plan(String question, DataTable t, Plan prev) {
            Plan f = fresh(question, t);
            if (prev == null || f.intent.equals("describe")) return f;
            String qn = norm(question);
            if (has(qn, " (start over|start again|new question|from scratch) ")) return f;
            int words = qn.trim().split(" ").length;
            boolean follow = (STRONG.matcher(qn).find() && words <= 14)
                    || (WEAK.matcher(qn).find() && words <= 3)
                    || (has(qn, " (that|it|those|them|these|same|previous|earlier|above) ") && words <= 12)
                    || (has(qn, " (only|just) ") && words <= 6)
                    || (has(qn, " (instead|rather|again) ") && words <= 8);
            if (!follow) return f;
            Plan m = followUp(qn, t, prev, f);
            return m != null ? m : f;
        }

        /** Filters in {@code add} replace filters on the same column in {@code base} (unless keepSameColumn). */
        static List<Filter> mergeFilters(List<Filter> base, List<Filter> add, boolean keepSameColumn) {
            Set<String> cols = new HashSet<>();
            for (Filter f : add) cols.add(f.column().toLowerCase(Locale.ROOT));
            List<Filter> out = new ArrayList<>();
            for (Filter f : base) if (keepSameColumn || !cols.contains(f.column().toLowerCase(Locale.ROOT))) out.add(f);
            out.addAll(add);
            return out;
        }

        /** Applies only what the follow-up explicitly says to a copy of the previous plan. Returns null if nothing was recognised. */
        static Plan followUp(String qn, DataTable t, Plan prev, Plan fresh) {
            List<Column> nums = t.colsOfType(ColType.NUMBER);
            boolean metricNamed = false;
            for (Column c : nums) if (mention(qn, c.name) >= 0) metricNamed = true;
            boolean aggWord = has(qn, " (average|avg|mean|typical|median|maximum|max|peak|minimum|min|how many|count|number of|total|sum) ");
            boolean groupNamed = fresh.x != null && (fresh.message == null || !fresh.message.contains("didn't say how to group"));
            boolean splitCue = has(qn, " (split|break|broken|breakdown|segment|segmented|colou?r|coloured|group|grouped|stack|stacked|further|also|and|then) ")
                    && has(qn, " (by|per|into|across) ");
            boolean chartWord = List.of("pie", "line", "bar").contains(fresh.chart);
            boolean dateFilter = fresh.filters.stream().anyMatch(f -> {
                Column c = t.find(f.column());
                return c != null && c.type == ColType.DATE;
            });

            Plan p = prev.copy();
            p.title = null;
            p.message = null;
            List<String> notes = new ArrayList<>();
            boolean filtersChanged = false;

            if ((has(qn, " (clear|remove|reset|drop|forget) (the |all |any |those |that |my )?(filters?|restrictions?) ")
                    || has(qn, " (all|every) (data|rows|records) ")) && !p.filters.isEmpty()) {
                p.filters = new ArrayList<>();
                notes.add("cleared the earlier filters");
                filtersChanged = true;
            }
            if (!fresh.filters.isEmpty()) {
                p.filters = mergeFilters(p.filters, fresh.filters, false);
                notes.add("filtered to " + Engine.describeFilters(fresh));
                filtersChanged = true;
            }

            if (fresh.intent.equals("drivers")) {
                p.intent = "drivers";
                p.x = fresh.x;
                p.bucket = fresh.bucket;
                p.from = fresh.from;
                p.to = fresh.to;
                p.direction = fresh.direction;
                p.series = null;
                p.chart = "auto";
                p.sort = "none";
                p.limit = null;
                if (metricNamed) p.metric = fresh.metric;
                if (aggWord) p.agg = fresh.agg;
                p.filters.removeIf(f -> {
                    Column c = t.find(f.column());
                    return c != null && c.type == ColType.DATE;
                });
                notes.add("analysing what changed");
            } else if (fresh.intent.equals("anomalies")) {
                p.intent = "anomalies";
                p.x = fresh.x;
                p.bucket = fresh.bucket;
                p.metric = fresh.metric;
                p.series = fresh.series;
                p.agg = fresh.agg;
                p.chart = fresh.chart;
                p.sort = "none";
                p.from = null;
                p.to = null;
                p.limit = fresh.limit;
                notes.add("looking for unusual values");
            } else if (fresh.intent.equals("compare") || fresh.intent.equals("forecast")) {
                p.intent = fresh.intent;
                p.x = fresh.x;
                p.time = fresh.time;
                p.from = fresh.from;
                p.to = fresh.to;
                p.bucket = fresh.bucket;
                p.metric = fresh.metric;
                p.agg = fresh.agg;
                p.series = null;
                p.chart = fresh.chart;
                p.sort = "none";
                p.limit = fresh.limit;
                notes.add(fresh.intent.equals("compare") ? "comparing the two periods" : "projecting forward");
            } else if (fresh.intent.equals("correlate")) {
                p.intent = "correlate";
                p.x = fresh.x;
                p.metric = fresh.metric;
                p.series = fresh.series;
                p.chart = "scatter";
                p.bucket = null;
                p.from = null;
                p.to = null;
                p.sort = "none";
                p.limit = null;
                notes.add("looking at the relationship between numeric columns");
            } else if (fresh.intent.equals("rows")) {
                p.intent = "rows";
                p.x = null;
                p.series = null;
                p.bucket = null;
                p.chart = "none";
                p.from = null;
                p.to = null;
                if (metricNamed && fresh.metric != null) p.metric = fresh.metric;
                else if (p.metric == null) p.metric = fresh.metric;
                p.sort = fresh.sort.equals("none") ? "desc" : fresh.sort;
                p.limit = fresh.limit;
                notes.add("listing individual rows");
            } else if (prev.intent.equals("correlate")) {
                if (groupNamed) {
                    Column gc = t.find(fresh.x);
                    if (gc != null && gc.type == ColType.TEXT) {
                        p.series = gc.name;
                        notes.add("colouring points by " + gc.name);
                    }
                }
            } else if (prev.intent.equals("anomalies") && !aggWord && !chartWord) {
                if (metricNamed && fresh.metric != null) {
                    p.metric = fresh.metric;
                    notes.add("scanning " + fresh.metric);
                }
                if (fresh.limit != null) {
                    p.limit = fresh.limit;
                    notes.add("showing " + fresh.limit);
                }
                if (groupNamed) {
                    Column gc = t.find(fresh.x);
                    if (gc != null && gc.type == ColType.TEXT) {
                        p.series = gc.name;
                        notes.add("comparing within each " + gc.name);
                    }
                }
            } else if (prev.intent.equals("compare")) {
                if (metricNamed && fresh.metric != null) {
                    p.metric = fresh.metric;
                    notes.add("measuring " + fresh.metric);
                }
                if (aggWord) {
                    p.agg = fresh.agg;
                    if (fresh.metric == null && fresh.agg.equals("count") && !metricNamed) p.metric = null;
                    notes.add("using " + p.agg);
                }
                if (groupNamed) {
                    Column gc = t.find(fresh.x);
                    if (gc != null && gc.type == ColType.TEXT) {
                        p.x = gc.name;
                        notes.add("breaking the change down by " + gc.name);
                    }
                }
                if (fresh.limit != null) {
                    p.limit = fresh.limit;
                    notes.add("showing the top " + fresh.limit);
                }
            } else if (prev.intent.equals("forecast")) {
                if (metricNamed && fresh.metric != null) {
                    p.metric = fresh.metric;
                    notes.add("forecasting " + fresh.metric);
                }
                if (aggWord) {
                    p.agg = fresh.agg;
                    notes.add("using " + p.agg);
                }
                Matcher hm = Pattern.compile(" (?:(\\d+) )(day|week|month|quarter|year)s? ").matcher(qn);
                if (hm.find()) {
                    p.limit = Integer.parseInt(hm.group(1));
                    p.bucket = hm.group(2);
                    notes.add("looking " + p.limit + " " + p.bucket + (p.limit == 1 ? "" : "s") + " ahead");
                }
            } else if (prev.intent.equals("rows") && !groupNamed && !aggWord) {
                if (metricNamed && fresh.metric != null) {
                    p.metric = fresh.metric;
                    notes.add("sorting by " + fresh.metric);
                }
                if (fresh.limit != null) {
                    p.limit = fresh.limit;
                    notes.add("showing " + fresh.limit + " rows");
                }
                if (!"none".equals(fresh.sort)) {
                    p.sort = fresh.sort;
                    notes.add(fresh.sort.equals("asc") ? "lowest first" : "highest first");
                }
            } else if (prev.intent.equals("drivers") && !groupNamed && !metricNamed && !aggWord && !chartWord && !dateFilter) {
                // only the filters changed: keep the driver analysis
            } else {
                if (!p.intent.equals("aggregate")) {   // coming from a drivers / rows answer
                    if (!prev.intent.equals("drivers")) {
                        p.x = null;
                        p.series = null;
                        p.bucket = null;
                    }
                    p.intent = "aggregate";
                    p.from = null;
                    p.to = null;
                    p.sort = "none";
                    p.chart = "auto";
                    p.limit = null;
                }
                if (metricNamed) {
                    p.metric = fresh.metric;
                    if (!aggWord && "count".equals(p.agg)) p.agg = "sum";
                    notes.add("measuring " + fresh.metric);
                }
                if (aggWord) {
                    p.agg = fresh.agg;
                    if (fresh.metric == null && fresh.agg.equals("count") && !metricNamed) p.metric = null;
                    notes.add("using " + p.agg);
                }
                if (p.metric == null) p.agg = "count";
                if (groupNamed) {
                    if (splitCue && p.x != null && !p.x.equalsIgnoreCase(fresh.x)) {
                        p.series = fresh.x;
                        if (fresh.bucket != null) p.bucket = fresh.bucket;
                        notes.add("split by " + fresh.x);
                    } else if (p.x == null || !p.x.equalsIgnoreCase(fresh.x) || fresh.series != null) {
                        p.x = fresh.x;
                        p.series = fresh.series;
                        p.bucket = fresh.bucket;
                        p.chart = "auto";
                        if (fresh.limit == null) {
                            p.limit = null;
                            p.sort = "none";
                        }
                        notes.add("grouping by " + fresh.x + (fresh.series != null ? " and " + fresh.series : ""));
                    } else if (fresh.bucket != null && !fresh.bucket.equals(p.bucket)) {
                        p.bucket = fresh.bucket;
                        notes.add("by " + fresh.bucket);
                    }
                }
                if (fresh.limit != null) {
                    p.limit = fresh.limit;
                    p.sort = fresh.sort;
                    notes.add("top " + fresh.limit);
                } else if (!"none".equals(fresh.sort)) {
                    p.sort = fresh.sort;
                    notes.add(fresh.sort.equals("asc") ? "lowest first" : "highest first");
                }
                if (chartWord) {
                    p.chart = fresh.chart;
                    notes.add("as a " + fresh.chart + " chart");
                }
            }
            if (notes.isEmpty()) return null;

            Column xc = p.x == null ? null : t.find(p.x), sc = p.series == null ? null : t.find(p.series);
            if (!p.intent.equals("drivers") && (xc == null || xc.type != ColType.DATE) && (sc == null || sc.type != ColType.DATE)) p.bucket = null;
            StringBuilder msg = new StringBuilder("Follow-up to your previous question: " + String.join("; ", notes) + ".");
            if (!filtersChanged && !p.filters.isEmpty())
                msg.append(" Earlier filters still apply (").append(Engine.describeFilters(p)).append("); say \"clear filters\" to remove them.");
            p.message = msg.toString();
            return p;
        }

        static Plan fresh(String question, DataTable t) {
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
            boolean anomWord = has(qn, " (anomal\\w*|outliers?|unusual\\w*|abnormal\\w*|irregular\\w*|suspicious\\w*|odd|strange|weird|unexpected\\w*|atypical|aberrant|fishy|out of the ordinary|out of line|stand out|stands out|standing out|extreme) ");
            boolean change = time != null && !anomWord && has(qn, " (drop|dropped|drops|decline|declined|declining|fall|fell|fallen|falling|decrease|decreased|dip|dipped|slump|slumped|"
                    + "increase|increased|spike|spiked|jump|jumped|surge|surged|rise|rose|grew|why|driving|driver|drivers|drove|cause|caused|reason|reasons|behind|explain|what changed|went down|went up"
                    + ") ");
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
            if (has(qn, " (pie|donut|doughnut|share|shares|proportion|proportions|percentage|percentages|composition) ")) p.chart = "pie";
            else if (has(qn, " (line|lines|trend|trends|over time) ")) p.chart = "line";
            else if (has(qn, " (bar|bars|column|columns|histogram) ")) p.chart = "bar";

            // ---- forecast
            if (time != null && (has(qn, " (forecast\\w*|predict\\w*|projection|projections|projected|projecting|extrapolat\\w*|going forward|outlook) ")
                    || has(qn, " (next|coming|following|upcoming) (\\d+ )?(days?|weeks?|months?|quarters?|years?) "))) {
                Matcher hm = Pattern.compile(" (?:next|coming|following|upcoming) (?:(\\d+) )?(day|week|month|quarter|year)s? ").matcher(qn);
                String hb = bucket;
                Integer horizon = null;
                if (hm.find()) {
                    horizon = hm.group(1) != null ? Integer.parseInt(hm.group(1)) : 1;
                    hb = hm.group(2);
                }
                p.intent = "forecast";
                p.x = time.name;
                p.bucket = hb;
                p.limit = horizon;
                p.sort = "none";
                p.chart = "line";
                p.series = null;
                return p;
            }

            // ---- compare two periods
            if (time != null && !has(qn, " (why|driving|driver|drivers|cause|caused|reason|reasons|behind|explain|what changed) ")
                    && has(qn, " (compare|compared|comparing|comparison|versus|vs|against|difference between|differences between|year over year|yoy|year on year|"
                    + "month over month|mom|month on month|quarter over quarter|qoq|quarter on quarter|week over week|wow|week on week) ")) {
                String[] pair = comparePeriods(qn, time);
                if (pair != null) {
                    p.intent = "compare";
                    p.time = time.name;
                    p.from = pair[0];
                    p.to = pair[1];
                    p.x = null;
                    for (Column c : cats) if (mention(qn, c.name) >= 0 && !filteredCols.contains(c.name)) { p.x = c.name; break; }
                    p.bucket = null;
                    p.series = null;
                    p.sort = "none";
                    p.chart = "auto";
                    if (!has(qn, " (top|bottom|best|worst) \\d+ ")) p.limit = null;
                    return p;
                }
            }

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

            // ---- anomalies / outliers
            if (anomWord && !nums.isEmpty()) {
                String ab = bucket;
                if (ab == null) {
                    if (has(qn, " (days|dates|which day|what day|any day) ")) ab = "day";
                    else if (has(qn, " (weeks|which week|what week|any week) ")) ab = "week";
                    else if (has(qn, " (months|which month|what month|any month) ")) ab = "month";
                    else if (has(qn, " (quarters|which quarter|what quarter|any quarter) ")) ab = "quarter";
                    else if (has(qn, " (years|which year|what year|any year) ")) ab = "year";
                }
                boolean periodAsk = time != null && (ab != null || timeWord || has(qn, " periods "));
                p.intent = "anomalies";
                p.sort = "none";
                p.series = null;
                if (periodAsk) {
                    p.x = time.name;
                    p.bucket = ab;
                    p.chart = "line";
                } else {
                    p.x = null;
                    p.bucket = null;
                    p.chart = "auto";
                    p.agg = "sum";
                    p.metric = mentioned && metric != null ? metric.name : null;
                    p.message = null;
                    for (Column c : cats) if (mention(qn, c.name) >= 0 && !filteredCols.contains(c.name)) { p.series = c.name; break; }
                }
                return p;
            }

            // ---- correlation / scatter plots
            List<Column> numHits = new ArrayList<>();
            {
                List<int[]> ph = new ArrayList<>();
                for (int i = 0; i < nums.size(); i++) {
                    int pos = mention(qn, nums.get(i).name);
                    if (pos >= 0) ph.add(new int[]{pos, i});
                }
                ph.sort(Comparator.comparingInt(h -> h[0]));
                for (int[] h : ph) numHits.add(nums.get(h[1]));
            }
            boolean corrWord = has(qn, " (correlat\\w*|relationship|relationships|related|relate|relates|scatter\\w*|association|associated|linked|depends? on|dependence|influence\\w*|covary|co vary) ");
            boolean vsWord = has(qn, " (vs|versus|against) ");
            if (nums.size() >= 2 && (corrWord || (vsWord && numHits.size() >= 2))) {
                Column xc = null, yc = null;
                if (numHits.size() >= 2) {
                    if (vsWord) {   // "price vs quantity": price on the y-axis
                        yc = numHits.get(0);
                        xc = numHits.get(1);
                    } else {
                        xc = numHits.get(0);
                        yc = numHits.get(1);
                    }
                } else if (numHits.size() == 1) xc = numHits.get(0);
                Column colour = null;
                for (Column c : cats) if (mention(qn, c.name) >= 0 && !filteredCols.contains(c.name)) { colour = c; break; }
                p.intent = "correlate";
                p.x = xc == null ? null : xc.name;
                p.metric = yc == null ? null : yc.name;
                p.series = colour == null ? null : colour.name;
                p.agg = "sum";
                p.bucket = null;
                p.sort = "none";
                p.limit = null;
                p.chart = "scatter";
                p.message = null;
                return p;
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
        /** The two periods a comparison question names, oldest first: explicit ("Q1 vs Q2", "feb and march 2026") or relative to the end of the data. */
        static String[] comparePeriods(String qn, Column time) {
            // 1. periods named outright, in the order they appear
            List<String> labels = new ArrayList<>();
            Matcher tk = Pattern.compile(" (?:(" + MONTH_RE + ")(?: ((?:19|20)\\d\\d))?|q([1-4])(?: ((?:19|20)\\d\\d))?|(?:quarter|qtr) ([1-4])(?: ((?:19|20)\\d\\d))?|((?:19|20)\\d\\d))(?= )").matcher(qn);
            while (tk.find()) {
                String label;
                if (tk.group(1) != null) {
                    int mo = monthOf(tk.group(1));
                    int y = pickYear(time, tk.group(2) != null ? Integer.valueOf(tk.group(2)) : null, mo);
                    label = String.format("%04d-%02d", y, mo);
                } else if (tk.group(3) != null || tk.group(5) != null) {
                    int q = Integer.parseInt(tk.group(3) != null ? tk.group(3) : tk.group(5));
                    String ys = tk.group(3) != null ? tk.group(4) : tk.group(6);
                    int y = pickYear(time, ys != null ? Integer.valueOf(ys) : null, (q - 1) * 3 + 1);
                    label = y + "-Q" + q;
                } else label = tk.group(7);
                if (!labels.contains(label)) labels.add(label);
            }
            if (labels.size() >= 2) {
                List<String> two = new ArrayList<>(labels.subList(0, 2));
                Collections.sort(two);
                return new String[]{two.get(0), two.get(1)};
            }
            if (time.dmax == null) return null;
            LocalDate end = time.dmax;
            // 2. "last 30 days vs the previous 30 days"
            Matcher rn = Pattern.compile(" last (\\d+) (days|weeks|months) ").matcher(qn);
            if (rn.find()) {
                int n = Integer.parseInt(rn.group(1));
                String u = rn.group(2);
                LocalDate bStart = u.equals("days") ? end.minusDays(n - 1L) : u.equals("weeks") ? end.minusWeeks(n).plusDays(1) : end.minusMonths(n).plusDays(1);
                LocalDate aEnd = bStart.minusDays(1);
                LocalDate aStart = u.equals("days") ? aEnd.minusDays(n - 1L) : u.equals("weeks") ? aEnd.minusWeeks(n).plusDays(1) : aEnd.minusMonths(n).plusDays(1);
                return new String[]{aStart + ".." + aEnd, bStart + ".." + end};
            }
            // 3. one period against the one before it ("this month vs last month", "year over year", "month over month")
            String unit = null;
            if (has(qn, " (year over year|yoy|year on year|vs same period last year|same period last year|a year ago) ")) unit = "yoy";
            else if (has(qn, " (month over month|mom|month on month) ")) unit = "month";
            else if (has(qn, " (quarter over quarter|qoq|quarter on quarter) ")) unit = "quarter";
            else if (has(qn, " (week over week|wow|week on week) ")) unit = "week";
            else {
                Matcher um = Pattern.compile(" (?:this|latest|current|last|previous|prior|recent) (week|month|quarter|year) ").matcher(qn);
                if (um.find()) unit = um.group(1);
            }
            if (unit == null) return null;
            switch (unit) {
                case "week": {
                    LocalDate s = end.with(java.time.DayOfWeek.MONDAY);
                    return new String[]{Engine.bucketLabel(s.minusWeeks(1), "week"), Engine.bucketLabel(end, "week")};
                }
                case "month":
                    return new String[]{Engine.bucketLabel(end.minusMonths(1), "month"), Engine.bucketLabel(end, "month")};
                case "quarter":
                    return new String[]{Engine.bucketLabel(end.minusMonths(3), "quarter"), Engine.bucketLabel(end, "quarter")};
                default: {   // year / yoy: year to date against the same dates a year earlier
                    if (unit.equals("yoy") && has(qn, " month ")) {
                        LocalDate ly = end.minusYears(1);
                        return new String[]{Engine.bucketLabel(ly, "month"), Engine.bucketLabel(end, "month")};
                    }
                    LocalDate ys = end.withDayOfYear(1);
                    return new String[]{ys.minusYears(1) + ".." + end.minusYears(1), ys + ".." + end};
                }
            }
        }

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
                 "intent": "aggregate" | "drivers" | "rows" | "describe" | "correlate" | "anomalies" | "compare" | "forecast",
                 "title": short chart title,
                 "metric": exact numeric column name, or null to count rows,
                 "agg": "sum" | "avg" | "count" | "min" | "max" | "median",
                 "x": exact column name for the x-axis / grouping, or null for one overall number,
                 "bucket": "day" | "weekday" | "week" | "month" | "quarter" | "year" | null  (only when x or series is a date column; "weekday" = Mon..Sun),
                 "series": exact text column to split into several series, or null,
                 "filters": [ {"column": "...", "op": "=" | "!=" | ">" | "<" | ">=" | "<=" | "contains" | "in" | "not in", "value": "..." or ["a","b"] for in / not in} ],
                 "sort": "asc" | "desc" | "none",
                 "limit": integer or null,
                 "chart": "bar" | "line" | "pie" | "scatter" | "none",
                 "from": period label or null, "to": period label or null, "direction": "drop" | "rise",
                 "time": date column name (compare only; defaults to the first date column),
                 "message": optional one-sentence note about an assumption you made
                }
                Guidance:
                - "aggregate": group + aggregate, drawn as a chart. Time on x => line; categories => bar; shares/proportions => pie (single series, at most 10 categories).
                - "drivers": use for "why did X drop/rise/change", "what is driving ...". Set x to the date column, bucket to the period size, and from/to to period labels \
                (month "2026-03", quarter "2026-Q1", week "2026-W09", year "2026", day "2026-03-15"). If the user names one period, set only "to" (the app compares it with the previous period). \
                If no period is named, omit both and set "direction". The app ranks which segments of every text column explain the change, so do not add filters for the segments.
                - "rows": list individual rows ("the ten biggest transactions"): metric = column to sort by, limit = how many, sort desc/asc, chart "none".
                - "describe": overview of all columns.
                - "correlate": the relationship between numeric columns ("is discount related to units?", "scatter plot of price vs quantity", "which columns are correlated?"). \
                Set x to the numeric column for the x-axis and metric to the numeric column for the y-axis, chart "scatter", and optionally series to a text column to colour the points. \
                Leave both null for a correlation matrix of all numeric columns, or set just one to find its strongest partner. Filters work as usual.
                - "anomalies": unusual values or outliers ("any unusual transactions?", "which weeks look odd?", "outliers in amount by category"). \
                Set metric to the numeric column to scan (null = scan every numeric column). To look for unusual rows, leave x null; set series to a text column to judge each value against its own group. \
                To look for unusual time periods, set x to the date column and bucket to the period size (and agg for how to combine the metric). limit = how many to list. The app does the statistics.
                - "compare": two periods side by side ("compare Q1 and Q2", "this month vs last month", "2025 vs 2026", "last 30 days vs the previous 30 days"). \
                Set from = the earlier period and to = the later one, as labels: month "2026-03", quarter "2026-Q1", year "2026", week "2026-W09", day "2026-03-15", or a range "2026-01-01..2026-03-31". \
                Resolve relative wording ("this month", "last quarter", "year to date vs the same period last year") against the LATEST date in the data, not today. \
                Set x to a text column to break the change down by (null lets the app choose the one that moved most), metric/agg for the measure, time to the date column. \
                Use "drivers" instead when the user asks WHY something changed.
                - "forecast": project a measure forward ("forecast revenue for the next 3 months", "where is this heading?"). Set x to the date column, bucket to the period size, \
                limit to how many periods ahead (null = sensible default), metric and agg for the measure. The app fits the trend and any seasonality and draws the range.
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
        record Hit(Shape shape, String tip, int li, int si) {}

        private Color INK = new Color(0x222222), GRID = new Color(0xE3E3E3), AXIS = new Color(0x888888);
        static final Color POS = new Color(0x2CA58D), NEG = new Color(0xE15759);
        static final Color[] PALETTE = {new Color(0x2E6FDB), new Color(0xF28E2B), new Color(0x2CA58D), new Color(0xE15759),
                new Color(0x8E6BBF), new Color(0xEDC948), new Color(0x76B7B2), new Color(0xFF9DA7), new Color(0x9C755F), new Color(0xBAB0AC)};

        private final ChartData d;
        private final List<Hit> hits = new ArrayList<>();
        private final java.util.function.BiConsumer<Integer, Integer> onPick;   // (label index, series index); null = not clickable

        ChartPanel(ChartData d) { this(d, null); }

        /** Dark colours for on-screen use. Exports (PNG, PDF) always draw a fresh, light panel. */
        void setDark(boolean on) {
            INK = new Color(on ? 0xE6E6E6 : 0x222222);
            GRID = new Color(on ? 0x3D3D3D : 0xE3E3E3);
            AXIS = new Color(on ? 0x9A9A9A : 0x888888);
            setBackground(on ? new Color(0x1E1E1E) : Color.WHITE);
            setBorder(BorderFactory.createLineBorder(new Color(on ? 0x444444 : 0xDDDDDD)));
            repaint();
        }

        ChartPanel(ChartData d, java.util.function.BiConsumer<Integer, Integer> onPick) {
            this.d = d;
            this.onPick = onPick;
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(520, 300));
            setBorder(BorderFactory.createLineBorder(new Color(0xDDDDDD)));
            setToolTipText("");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) { popup(e); }

                @Override
                public void mouseReleased(MouseEvent e) { popup(e); }
            });
            if (onPick != null) {
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        Hit h = hitAt(e.getPoint());
                        if (h != null && h.li() >= 0) onPick.accept(h.li(), h.si());
                    }
                });
                addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                    @Override
                    public void mouseMoved(MouseEvent e) {
                        Hit h = hitAt(e.getPoint());
                        setCursor(h != null && h.li() >= 0 ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                    }
                });
            }
        }

        private void popup(MouseEvent e) {
            if (!e.isPopupTrigger()) return;
            JPopupMenu menu = new JPopupMenu();
            JMenuItem save = new JMenuItem("Save chart as PNG...");
            save.addActionListener(a -> ChartExport.saveDialog(this, d));
            JMenuItem copy = new JMenuItem("Copy chart to clipboard");
            copy.addActionListener(a -> ChartExport.copy(this, d));
            menu.add(save);
            menu.add(copy);
            menu.show(e.getComponent(), e.getX(), e.getY());
        }

        private Hit hitAt(Point pt) {
            for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).shape().contains(pt)) return hits.get(i);
            return null;
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            Hit h = hitAt(e.getPoint());
            if (h == null) return null;
            return h.li() >= 0 && onPick != null ? h.tip() + "   (click to drill down)" : h.tip();
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
            if (d.bandLo != null) for (double v : d.bandLo) if (!Double.isNaN(v)) lo = Math.min(lo, v);
            if (d.bandHi != null) for (double v : d.bandHi) if (!Double.isNaN(v)) hi = Math.max(hi, v);
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
            if (d.type == ChartType.SCATTER ? d.sx.isEmpty() : (d.values.isEmpty() || d.labels.isEmpty())) {
                g.dispose();
                return;
            }
            switch (d.type) {
                case SCATTER -> scatter(g, w, h, 34);
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
            if (d.note != null) {
                g.setFont(g.getFont().deriveFont(11f));
                g.setColor(AXIS);
                g.drawString(d.note, w - 12 - g.getFontMetrics().stringWidth(d.note), top0 + g.getFontMetrics().getAscent());
                g.setFont(g.getFont().deriveFont(12f));
            }
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

            // projection region (forecasts) and highlight bands
            if (d.forecastFrom >= 0 && d.forecastFrom < n) {
                g.setColor(new Color(128, 128, 128, 34));
                g.fill(new Rectangle2D.Double(left + d.forecastFrom * slot, plotY, (n - d.forecastFrom) * slot, plotH));
            }
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
                if (d.bandLo != null && d.bandHi != null) {
                    Path2D.Double band = new Path2D.Double();
                    boolean started = false;
                    for (int i = 0; i < n; i++) {
                        if (Double.isNaN(d.bandHi[i])) continue;
                        double x = left + (i + 0.5) * slot, y = yOf.applyAsDouble(d.bandHi[i]);
                        if (!started) { band.moveTo(x, y); started = true; } else band.lineTo(x, y);
                    }
                    for (int i = n - 1; i >= 0; i--) {
                        if (Double.isNaN(d.bandLo[i])) continue;
                        band.lineTo(left + (i + 0.5) * slot, yOf.applyAsDouble(d.bandLo[i]));
                    }
                    if (started) {
                        band.closePath();
                        Color bc = color(Math.min(1, ns - 1));
                        g.setColor(new Color(bc.getRed(), bc.getGreen(), bc.getBlue(), 52));
                        g.fill(band);
                    }
                }
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
                    g.setStroke(d.dashed.contains(s)
                            ? new BasicStroke(2.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{7f, 5f}, 0f)
                            : new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(path);
                    g.setStroke(new BasicStroke(1f));
                    for (int i = 0; i < n; i++) {
                        if (Double.isNaN(vs[i])) continue;
                        double x = left + (i + 0.5) * slot, y = yOf.applyAsDouble(vs[i]);
                        if (n <= 60) g.fill(new Ellipse2D.Double(x - 3, y - 3, 6, 6));
                        hits.add(new Hit(new Ellipse2D.Double(x - 7, y - 7, 14, 14), tip(i, s, vs[i]), i, s));
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
                        hits.add(new Hit(rr, tip(i, s, v), i, s));
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
                    hits.add(new Hit(rr, tip(i, s, v), i, s));
                    g.setColor(INK);
                    String vl = Fmt.compact(v);
                    int tw = fm.stringWidth(vl);
                    int tx = v >= 0 ? (int) (xe + 4) : (int) (xe - 4 - tw);
                    g.drawString(vl, tx, (int) (y + barH / 2 + fm.getAscent() / 2.0 - 1));
                }
            }
        }

        // ---------------------------------------------------------------- scatter
        private void scatter(Graphics2D g, int w, int h, int top0) {
            FontMetrics fm = g.getFontMetrics();
            int top = legend(g, w, top0);
            double xmin = Double.POSITIVE_INFINITY, xmax = Double.NEGATIVE_INFINITY, ymin = xmin, ymax = xmax;
            for (int s = 0; s < d.sx.size(); s++)
                for (int k = 0; k < d.sx.get(s).length; k++) {
                    xmin = Math.min(xmin, d.sx.get(s)[k]);
                    xmax = Math.max(xmax, d.sx.get(s)[k]);
                    ymin = Math.min(ymin, d.sy.get(s)[k]);
                    ymax = Math.max(ymax, d.sy.get(s)[k]);
                }
            if (!(xmin <= xmax)) return;
            if (xmax == xmin) xmax = xmin + 1;
            if (ymax == ymin) ymax = ymin + 1;
            double xstep = niceStep((xmax - xmin) / 6), ystep = niceStep((ymax - ymin) / 5);
            final double xlo = Math.floor(xmin / xstep + 1e-9) * xstep, xhi = Math.max(Math.ceil(xmax / xstep - 1e-9) * xstep, xlo + xstep);
            final double ylo = Math.floor(ymin / ystep + 1e-9) * ystep, yhi = Math.max(Math.ceil(ymax / ystep - 1e-9) * ystep, ylo + ystep);
            int yt = (int) Math.round((yhi - ylo) / ystep), xt = (int) Math.round((xhi - xlo) / xstep);
            int tickW = 0;
            for (int k = 0; k <= yt; k++) tickW = Math.max(tickW, fm.stringWidth(Fmt.compact(ylo + k * ystep)));
            int left = tickW + 36, right = 18, bottom = fm.getHeight() * 2 + 14;
            final int plotX = left, plotY = top, plotW = w - left - right, plotH = h - top - bottom;
            if (plotW < 60 || plotH < 50) return;
            java.util.function.DoubleUnaryOperator xOf = v -> plotX + (v - xlo) / (xhi - xlo) * plotW;
            java.util.function.DoubleUnaryOperator yOf = v -> plotY + (yhi - v) / (yhi - ylo) * plotH;

            g.setStroke(new BasicStroke(1f));
            for (int k = 0; k <= yt; k++) {
                double v = ylo + k * ystep;
                int y = (int) Math.round(yOf.applyAsDouble(v));
                g.setColor(GRID);
                g.drawLine(plotX, y, plotX + plotW, y);
                g.setColor(AXIS);
                String lab = Fmt.compact(v);
                g.drawString(lab, plotX - 6 - fm.stringWidth(lab), y + fm.getAscent() / 2 - 1);
            }
            for (int k = 0; k <= xt; k++) {
                double v = xlo + k * xstep;
                int x = (int) Math.round(xOf.applyAsDouble(v));
                g.setColor(GRID);
                g.drawLine(x, plotY, x, plotY + plotH);
                g.setColor(AXIS);
                String lab = Fmt.compact(v);
                g.drawString(lab, x - fm.stringWidth(lab) / 2, plotY + plotH + 4 + fm.getAscent());
            }
            g.setColor(AXIS);
            g.drawRect(plotX, plotY, plotW, plotH);
            // axis titles
            g.setColor(INK);
            String xl = clip(g, d.xLabel, plotW);
            g.drawString(xl, plotX + (plotW - fm.stringWidth(xl)) / 2, plotY + plotH + 8 + fm.getHeight() + fm.getAscent());
            Graphics2D gr = (Graphics2D) g.create();
            String yl = clip(g, d.yLabel, plotH);
            gr.rotate(-Math.PI / 2, 14, plotY + plotH / 2.0);
            gr.drawString(yl, (float) (14 - fm.stringWidth(yl) / 2.0), (float) (plotY + plotH / 2.0 + 4));
            gr.dispose();

            // points
            int total = 0;
            for (double[] a : d.sx) total += a.length;
            boolean tips = total <= 1500;
            for (int s = 0; s < d.sx.size(); s++) {
                Color c = color(s);
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 150));
                for (int k = 0; k < d.sx.get(s).length; k++) {
                    double px = xOf.applyAsDouble(d.sx.get(s)[k]), py = yOf.applyAsDouble(d.sy.get(s)[k]);
                    g.fill(new Ellipse2D.Double(px - 3.2, py - 3.2, 6.4, 6.4));
                    if (tips)
                        hits.add(new Hit(new Ellipse2D.Double(px - 5, py - 5, 10, 10),
                                d.xLabel + ": " + Fmt.full(d.sx.get(s)[k]) + ",  " + d.yLabel + ": " + Fmt.full(d.sy.get(s)[k]), -1, -1));
                }
            }

            // fitted line (clipped to the plot) and a small caption
            if (d.fit != null) {
                double a = d.fit[1], b = d.fit[0];
                double x0 = xlo, x1 = xhi, y0 = a + b * x0, y1 = a + b * x1;
                if (b != 0) {   // clip to the y range
                    double xa = (ylo - a) / b, xb = (yhi - a) / b;
                    double lo = Math.min(xa, xb), hi = Math.max(xa, xb);
                    x0 = Math.max(x0, lo);
                    x1 = Math.min(x1, hi);
                    y0 = a + b * x0;
                    y1 = a + b * x1;
                }
                if (x0 < x1) {
                    g.setColor(INK);
                    g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{6f, 4f}, 0f));
                    g.draw(new Line2D.Double(xOf.applyAsDouble(x0), yOf.applyAsDouble(y0), xOf.applyAsDouble(x1), yOf.applyAsDouble(y1)));
                    g.setStroke(new BasicStroke(1f));
                }
                String cap = String.format(Locale.US, "r = %.2f    n = %,d", d.fit[2], (long) d.fit[3]);
                int cw = fm.stringWidth(cap) + 12;
                g.setColor(new Color(getBackground().getRed(), getBackground().getGreen(), getBackground().getBlue(), 220));
                g.fillRect(plotX + plotW - cw - 6, plotY + 6, cw, fm.getHeight() + 6);
                g.setColor(INK);
                g.drawString(cap, plotX + plotW - cw, plotY + 6 + fm.getAscent() + 3);
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
                g.setColor(getBackground());
                g.setStroke(new BasicStroke(1.5f));
                g.draw(arc);
                hits.add(new Hit(arc, d.labels.get(i) + ": " + Fmt.full(vs[i]) + " (" + Fmt.share(vs[i] / sum) + ")", i, 0));
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
            Random r2 = new Random(7);   // separate stream: the original columns stay exactly as before
            double[] unitPrice = {60, 45, 130, 15};
            String[] regions = {"North", "South", "East", "West", "Central"};
            double[] regionW = {1.0, 1.25, 0.9, 1.1, 0.75};
            String[] cats = {"Hardware", "Software", "Services", "Subscriptions"};
            double[] catW = {0.30, 0.25, 0.15, 0.30};
            double[] catMean = {420, 260, 650, 90};
            String[] channels = {"Online", "Retail", "Partner"};
            double[] chanW = {0.50, 0.30, 0.20};
            List<String[]> recs = new ArrayList<>();
            recs.add(new String[]{"TransactionID", "Date", "Region", "Category", "Channel", "Status", "Amount", "Units", "Discount (%)"});
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
                    int units = (int) Math.max(1, Math.round(amount / unitPrice[ci] * Math.exp(0.12 * r2.nextGaussian())));
                    double disc = Math.max(0, Math.min(30, 2 + 0.5 * units + 3.5 * r2.nextGaussian()));
                    recs.add(new String[]{"T" + (id++), d.toString(), regions[ri], cats[ci], channels[chi], status,
                            String.format(Locale.US, "%.2f", amount), String.valueOf(units), String.format(Locale.US, "%.1f", disc)});
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
                        "Scatter plot of amount vs units",
                        "Any unusual transactions?",
                        "Compare Q1 with Q2 by region",
                        "Forecast revenue for the next 3 months");
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
            if (t.colsOfType(ColType.NUMBER).size() >= 2) out.add("Which numeric columns are related?");
            if (metric != null) out.add("Are there any unusual " + metric.name + " values?");
            if (metric != null && time != null) out.add("Compare " + metric.name + " this month with last month");
            if (metric != null && time != null) out.add("Forecast " + metric.name + " for the next 3 months");
            return out;
        }
    }


    // =====================================================================================
    //  Drill-down: what a click on a chart element means
    // =====================================================================================
    static final class Drill {
        record Drilled(String question, Plan plan) {}

        /**
         * Clicking a period (bar or point on a time axis) compares it with the previous period; clicking the
         * first period breaks it down by a category. Clicking a category or segment filters to it and shows how
         * it behaves over time (or, with no date column, a further breakdown).
         */
        static Drilled plan(DataTable t, ChartData cd, int li, int si) {
            if (cd.basePlan == null || li < 0 || li >= cd.labels.size()) return null;
            Plan p = cd.basePlan.copy();
            p.title = null;
            p.message = null;
            p.sort = "none";
            p.limit = null;
            p.chart = "auto";
            p.intent = "aggregate";
            p.from = null;
            p.to = null;
            p.x = null;
            p.series = null;
            p.bucket = null;
            List<Filter> filters = new ArrayList<>(cd.basePlan.filters);
            List<String> what = new ArrayList<>();
            if (si >= 0 && cd.series.size() > 1 && cd.seriesFilters != null && si < cd.seriesFilters.size() && cd.seriesFilters.get(si) != null) {
                filters = LocalPlanner.mergeFilters(filters, cd.seriesFilters.get(si), false);
                what.add(cd.series.get(si));
            }
            String label = cd.labels.get(li);
            String ctx = what.isEmpty() ? "" : " for " + String.join(", ", what);
            Column time = t.firstOfType(ColType.DATE);

            if (cd.periodX) {
                if (cd.timeCol == null || "weekday".equals(cd.bucket)) return null;
                p.filters = filters;
                if (li > 0) {
                    p.intent = "drivers";
                    p.x = cd.timeCol;
                    p.bucket = cd.bucket;
                    p.from = cd.labels.get(li - 1);
                    p.to = label;
                    return new Drilled("What changed in " + label + " compared with " + cd.labels.get(li - 1) + ctx + "?", p);
                }
                String[] range = periodRange(label);   // first period: nothing earlier to compare with, so break it down
                if (range == null) return null;
                filters = LocalPlanner.mergeFilters(filters, List.of(new Filter(cd.timeCol, ">=", List.of(range[0])),
                        new Filter(cd.timeCol, "<=", List.of(range[1]))), false);
                p.filters = filters;
                p.x = bestBreakdown(t, filters);
                return new Drilled("Break down " + label + ctx, p);
            }

            if (cd.labelFilters == null || li >= cd.labelFilters.size()) return null;
            filters = LocalPlanner.mergeFilters(filters, cd.labelFilters.get(li), false);
            p.filters = filters;
            if (time != null) {
                p.x = time.name;     // bucket left empty: chosen from the date span
            } else {
                p.x = bestBreakdown(t, filters);
            }
            String subject = cd.xLabel != null && !cd.xLabel.isEmpty() && !label.contains(" = ") ? cd.xLabel + " = " + label : label;
            return new Drilled("Drill into " + subject + ctx + (time != null ? ": how has it changed over time?" : ""), p);
        }

        /** A categorical column not already pinned to a single value, preferring 3 to 12 distinct values. */
        static String bestBreakdown(DataTable t, List<Filter> filters) {
            Set<String> pinned = new HashSet<>();
            for (Filter f : filters) if (f.op().equals("=") || f.op().equals("in")) pinned.add(f.column());
            Column best = null;
            for (Column c : t.cols) {
                int dc = c.type == ColType.TEXT ? c.distinctCount() : 0;
                if (dc < 2 || dc > 30 || dc > 0.5 * t.rows || pinned.contains(c.name)) continue;
                if (best == null || (best.distinctCount() < 3 || best.distinctCount() > 12) && dc >= 3 && dc <= 12) best = c;
            }
            return best == null ? null : best.name;
        }

        static String[] periodRange(String l) {
            if (l.matches("\\d{4}-\\d{2}-\\d{2}") || l.matches("\\d{4}-\\d{2}") || l.matches("\\d{4}")) return new String[]{l, l};
            Matcher q = Pattern.compile("(\\d{4})-Q([1-4])").matcher(l);
            if (q.matches()) {
                int y = Integer.parseInt(q.group(1)), n = Integer.parseInt(q.group(2));
                return new String[]{String.format("%04d-%02d", y, (n - 1) * 3 + 1), String.format("%04d-%02d", y, n * 3)};
            }
            Matcher w = Pattern.compile("(\\d{4})-W(\\d{2})").matcher(l);
            if (w.matches()) {
                LocalDate start = LocalDate.of(Integer.parseInt(w.group(1)), 1, 4)
                        .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, Integer.parseInt(w.group(2))).with(ChronoField.DAY_OF_WEEK, 1);
                return new String[]{start.toString(), start.plusDays(6).toString()};
            }
            return null;
        }
    }

    // =====================================================================================
    //  Auto-insights: a quick scan of a freshly loaded table for things worth a closer look
    // =====================================================================================
    record Insight(String headline, Plan plan) {}

    static final class Insights {
        static Column defaultMetric(DataTable t) {
            List<Column> nums = t.colsOfType(ColType.NUMBER);
            if (nums.isEmpty()) return null;
            for (Column c : nums)
                if (c.name.toLowerCase(Locale.ROOT).matches(".*(amount|revenue|sales|total|price|value|cost|profit|income|spend|payment).*")) return c;
            return nums.get(0);
        }

        static String pct(double frac) { return String.format(Locale.US, "%.1f%%", Math.abs(frac) * 100); }

        static List<Insight> generate(DataTable t) {
            List<Insight> drops = new ArrayList<>(), rises = new ArrayList<>(), conc = new ArrayList<>(),
                    outl = new ArrayList<>(), trend = new ArrayList<>(), missing = new ArrayList<>();
            Column m = defaultMetric(t);
            String agg = m == null ? "count" : "sum";
            String measure = Engine.measureLabel(agg, m);
            boolean[] all = new boolean[t.rows];
            Arrays.fill(all, true);
            try {
                timeInsights(t, m, agg, measure, all, drops, rises, trend);
                concentration(t, m, agg, measure, conc);
                outliers(t, m, outl);
                missingData(t, missing);
            } catch (RuntimeException ignored) {
                // insights are a bonus: never let them break loading
            }
            List<Insight> out = new ArrayList<>();
            for (List<Insight> group : List.of(drops, rises, conc, outl, trend, missing)) out.addAll(group);
            return out.size() > 5 ? new ArrayList<>(out.subList(0, 5)) : out;
        }

        private static void timeInsights(DataTable t, Column m, String agg, String measure, boolean[] all,
                                         List<Insight> drops, List<Insight> rises, List<Insight> trend) {
            Column time = t.firstOfType(ColType.DATE);
            if (time == null) return;
            String bucket = Engine.bucketOf(null, time, all);
            TreeMap<String, Acc> byB = new TreeMap<>();
            for (int i = 0; i < t.rows; i++) {
                if (time.date[i] == null) continue;
                double v = 1;
                if (m != null) {
                    v = m.num[i];
                    if (Double.isNaN(v)) continue;
                }
                byB.computeIfAbsent(Engine.bucketLabel(time.date[i], bucket), k -> new Acc()).add(v, false);
            }
            List<String> labels = new ArrayList<>(byB.keySet());
            // a month that stops part-way would look like a fake drop
            if (bucket.equals("month") && time.dmax != null && time.dmax.getDayOfMonth() < time.dmax.lengthOfMonth() && !labels.isEmpty()
                    && labels.get(labels.size() - 1).equals(Engine.bucketLabel(time.dmax, "month")))
                labels.remove(labels.size() - 1);
            if (labels.size() < 3) return;
            double[] v = new double[labels.size()];
            for (int i = 0; i < v.length; i++) v[i] = byB.get(labels.get(i)).get(agg);
            int worst = -1, best = -1;
            double wp = 0, bp = 0;
            for (int i = 1; i < v.length; i++) {
                if (v[i - 1] == 0) continue;
                double p = (v[i] - v[i - 1]) / Math.abs(v[i - 1]);
                if (p < wp) { wp = p; worst = i; }
                if (p > bp) { bp = p; best = i; }
            }
            if (worst > 0 && wp <= -0.10)
                drops.add(new Insight(measure + " fell " + pct(wp) + " from " + labels.get(worst - 1) + " to " + labels.get(worst),
                        driverPlan(time, bucket, m, agg, labels.get(worst - 1), labels.get(worst), "drop")));
            if (best > 0 && bp >= 0.10)
                rises.add(new Insight(measure + " rose " + pct(bp) + " from " + labels.get(best - 1) + " to " + labels.get(best),
                        driverPlan(time, bucket, m, agg, labels.get(best - 1), labels.get(best), "rise")));
            if (v[0] != 0) {
                double p = (v[v.length - 1] - v[0]) / Math.abs(v[0]);
                if (Math.abs(p) >= 0.05) {
                    Plan pl = new Plan();
                    pl.metric = m == null ? null : m.name;
                    pl.agg = agg;
                    pl.x = time.name;
                    pl.bucket = bucket;
                    pl.chart = "line";
                    trend.add(new Insight("Overall, " + measure + " is " + (p > 0 ? "up " : "down ") + pct(p) + " from " + labels.get(0) + " to " + labels.get(labels.size() - 1), pl));
                }
            }
        }

        private static Plan driverPlan(Column time, String bucket, Column m, String agg, String from, String to, String dir) {
            Plan p = new Plan();
            p.intent = "drivers";
            p.metric = m == null ? null : m.name;
            p.agg = agg;
            p.x = time.name;
            p.bucket = bucket;
            p.from = from;
            p.to = to;
            p.direction = dir;
            return p;
        }

        /** A segment whose share of the measure differs a lot from its share of rows (or, for counts, a dominant segment). */
        private static void concentration(DataTable t, Column m, String agg, String measure, List<Insight> out) {
            Insight best = null;
            double bestGap = 0;
            for (Column c : t.cols) {
                int dc = c.type == ColType.TEXT ? c.distinctCount() : 0;
                if (dc < 3 || dc > 30 || dc > 0.5 * t.rows) continue;
                Map<String, Double> tot = new HashMap<>();
                Map<String, Integer> cnt = new HashMap<>();
                double sum = 0;
                int rows = 0;
                boolean negative = false;
                for (int i = 0; i < t.rows; i++) {
                    String k = c.text[i];
                    if (k == null || k.isBlank()) continue;
                    double v = m == null ? 1 : m.num[i];
                    if (Double.isNaN(v)) continue;
                    if (v < 0) negative = true;
                    tot.merge(k.trim(), v, Double::sum);
                    cnt.merge(k.trim(), 1, Integer::sum);
                    sum += v;
                    rows++;
                }
                if (negative || sum <= 0 || rows == 0) continue;
                for (var e : tot.entrySet()) {
                    double vs = e.getValue() / sum, rs = cnt.get(e.getKey()) / (double) rows;
                    double gap = m == null ? (vs >= 0.5 ? vs : 0) : vs - rs;
                    if (gap < (m == null ? 0.5 : 0.10) || vs < 0.10 || gap <= bestGap) continue;
                    bestGap = gap;
                    Plan p = new Plan();
                    p.metric = m == null ? null : m.name;
                    p.agg = agg;
                    p.x = c.name;
                    p.sort = "desc";
                    String pc = String.format(Locale.US, "%.0f%%", vs * 100);
                    best = new Insight(m == null
                            ? e.getKey() + " makes up " + pc + " of all rows (by " + c.name + ")"
                            : e.getKey() + " is " + String.format(Locale.US, "%.0f%%", rs * 100) + " of rows but " + pc + " of " + measure + " (by " + c.name + ")", p);
                }
            }
            if (best != null) out.add(best);
        }

        private static void outliers(DataTable t, Column m, List<Insight> out) {
            if (m == null) return;
            double[] v = Arrays.stream(m.num).filter(d -> !Double.isNaN(d)).toArray();
            int n = v.length;
            if (n < 20) return;
            Engine.Scored s = Engine.robustScores(v);
            if (s == null) return;
            int hi = 0, lo = 0;
            for (double z : s.score()) {
                if (z > Engine.ANOMALY_Z) hi++;
                else if (z < -Engine.ANOMALY_Z) lo++;
            }
            int count = hi + lo;
            if (count == 0 || count > 0.05 * n) return;
            Plan p = new Plan();
            p.intent = "anomalies";
            p.metric = m.name;
            p.limit = Math.min(count, 20);
            p.chart = "auto";
            out.add(new Insight(count + " unusual " + m.name + " value" + (count == 1 ? "" : "s") + " (" + hi + " high, " + lo + " low; typical " + Fmt.full(s.typical()) + ")", p));
        }

        private static void missingData(DataTable t, List<Insight> out) {
            Column worst = null;
            double share = 0;
            for (Column c : t.cols) {
                int blank = 0;
                for (String s : c.text) if (s == null || s.isBlank()) blank++;
                double f = blank / (double) t.rows;
                if (f >= 0.20 && f > share) {
                    share = f;
                    worst = c;
                }
            }
            if (worst == null) return;
            Plan p = new Plan();
            p.intent = "describe";
            out.add(new Insight(worst.name + " is " + String.format(Locale.US, "%.0f%%", share * 100) + " empty, so results involving it may be incomplete", p));
        }
    }

    // =====================================================================================
    //  PDF report: a tiny dependency-free PDF writer (Helvetica text, vector rules, JPEG charts)
    // =====================================================================================
    static final class Pdf {
        static final double W = 595.28, H = 841.89, M = 48;   // A4 in points, margin
        // Helvetica glyph widths (1/1000 em) for ASCII 32..126
        static final int[] REG = {278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
                556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
                1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
                667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
                333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
                556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584};
        static final int[] BOLD = {278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278,
                556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611,
                975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778,
                667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556,
                333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611,
                611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584};

        final List<StringBuilder> pages = new ArrayList<>();
        final List<List<Integer>> pageImgs = new ArrayList<>();
        final List<byte[]> jpegs = new ArrayList<>();
        final List<int[]> dims = new ArrayList<>();
        StringBuilder cur;
        double y;   // distance of the next line from the top of the page

        Pdf() { newPage(); }

        static String n(double v) { return String.format(Locale.US, "%.2f", v); }

        static double width(String s, boolean bold, double size) {
            int[] w = bold ? BOLD : REG;
            double sum = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                sum += c >= 32 && c <= 126 ? w[c - 32] : 556;
            }
            return sum * size / 1000.0;
        }

        static String esc(String s) {
            byte[] b = s.replaceAll("\\p{Cntrl}", " ").getBytes(Charset.forName("windows-1252"));   // unmappable characters become '?'
            StringBuilder sb = new StringBuilder();
            for (byte x : b) {
                int c = x & 0xff;
                if (c == '(' || c == ')' || c == '\\') sb.append('\\').append((char) c);
                else if (c >= 32 && c < 127) sb.append((char) c);
                else sb.append(String.format("\\%03o", c));
            }
            return sb.toString();
        }

        void color(Color c, String op) {
            cur.append(n(c.getRed() / 255.0)).append(' ').append(n(c.getGreen() / 255.0)).append(' ').append(n(c.getBlue() / 255.0)).append(' ').append(op).append(' ');
        }

        void newPage() {
            cur = new StringBuilder();
            pages.add(cur);
            pageImgs.add(new ArrayList<>());
            y = M;
            String foot = "DataChat report  -  page " + pages.size();
            text((W - width(foot, false, 8)) / 2, H - 26, false, 8, foot, new Color(0x888888));
        }

        void ensure(double h) { if (y + h > H - M) newPage(); }

        void gap(double h) { y += h; }

        void text(double x, double baseline, boolean bold, double size, String s, Color c) {
            cur.append("BT /F").append(bold ? 2 : 1).append(' ').append(n(size)).append(" Tf ");
            color(c, "rg");
            cur.append(n(x)).append(' ').append(n(H - baseline)).append(" Td (").append(esc(s)).append(") Tj ET\n");
        }

        void rect(double x, double top, double w, double h, Color c) {
            color(c, "rg");
            cur.append(n(x)).append(' ').append(n(H - top - h)).append(' ').append(n(w)).append(' ').append(n(h)).append(" re f\n");
        }

        void rule(double x1, double x2, double top, Color c, double lw) {
            color(c, "RG");
            cur.append(n(lw)).append(" w ").append(n(x1)).append(' ').append(n(H - top)).append(" m ").append(n(x2)).append(' ').append(n(H - top)).append(" l S\n");
        }

        static List<String> wrap(String s, boolean bold, double size, double maxW) {
            List<String> lines = new ArrayList<>();
            for (String para : s.split("\n")) {
                StringBuilder line = new StringBuilder();
                for (String word : para.trim().split("\\s+")) {
                    while (width(word, bold, size) > maxW && word.length() > 1) {   // very long token: hard-break
                        int k = word.length();
                        while (k > 1 && width(word.substring(0, k), bold, size) > maxW) k--;
                        if (line.length() > 0) { lines.add(line.toString()); line.setLength(0); }
                        lines.add(word.substring(0, k));
                        word = word.substring(k);
                    }
                    String test = line.length() == 0 ? word : line + " " + word;
                    if (width(test, bold, size) <= maxW || line.length() == 0) {
                        line.setLength(0);
                        line.append(test);
                    } else {
                        lines.add(line.toString());
                        line.setLength(0);
                        line.append(word);
                    }
                }
                lines.add(line.toString());
            }
            return lines;
        }

        void para(String s, boolean bold, double size, Color c, double indent) {
            double lh = size * 1.38;
            for (String line : wrap(s, bold, size, W - 2 * M - indent)) {
                ensure(lh);
                text(M + indent, y + size, bold, size, line, c);
                y += lh;
            }
        }

        static byte[] jpeg(BufferedImage img) throws IOException {
            ImageWriter wr = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam p = wr.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.92f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(bo)) {
                wr.setOutput(ios);
                wr.write(null, new IIOImage(img, null, null), p);
            } finally {
                wr.dispose();
            }
            return bo.toByteArray();
        }

        /** Draws an image scaled to the page width (or less) at the current position. */
        void image(BufferedImage img) throws IOException {
            double w = W - 2 * M, h = w * img.getHeight() / img.getWidth();
            ensure(h + 6);
            jpegs.add(jpeg(img));
            dims.add(new int[]{img.getWidth(), img.getHeight()});
            int idx = jpegs.size() - 1;
            pageImgs.get(pageImgs.size() - 1).add(idx);
            cur.append("q ").append(n(w)).append(" 0 0 ").append(n(h)).append(' ').append(n(M)).append(' ').append(n(H - y - h)).append(" cm /Im").append(idx).append(" Do Q\n");
            y += h + 6;
        }

        static String fit(String s, boolean bold, double size, double maxW) {
            if (width(s, bold, size) <= maxW) return s;
            while (s.length() > 1 && width(s + "...", bold, size) > maxW) s = s.substring(0, s.length() - 1);
            return s + "...";
        }

        void table(List<String> headers, List<String[]> rows, int maxRows) {
            int nc = headers.size();
            if (nc == 0) return;
            double size = nc > 8 ? 6.5 : 8, rowH = size + 6, avail = W - 2 * M;
            int shown = Math.min(rows.size(), maxRows);
            double[] cw = new double[nc];
            double total = 0;
            for (int c = 0; c < nc; c++) {
                double w = width(headers.get(c), true, size);
                for (int r = 0; r < Math.min(shown, 40); r++) w = Math.max(w, width(rows.get(r)[c], false, size));
                cw[c] = w + 10;
                total += cw[c];
            }
            double f = total > avail ? avail / total : 1;
            for (int c = 0; c < nc; c++) cw[c] *= f;
            ensure(rowH * 2);
            tableHeader(headers, cw, size, rowH);
            for (int r = 0; r < shown; r++) {
                if (y + rowH > H - M) {
                    newPage();
                    tableHeader(headers, cw, size, rowH);
                }
                double x = M;
                for (int c = 0; c < nc; c++) {
                    String cell = fit(rows.get(r)[c] == null ? "" : rows.get(r)[c], false, size, cw[c] - 6);
                    boolean num = cell.matches("[-+]?[\\d,]*\\.?\\d+%?");
                    text(num ? x + cw[c] - 3 - width(cell, false, size) : x + 3, y + size + 2.5, false, size, cell, new Color(0x222222));
                    x += cw[c];
                }
                y += rowH;
                rule(M, M + avail, y, new Color(0xE3E3E3), 0.4);
            }
            if (rows.size() > shown) {
                gap(3);
                para("Showing the first " + shown + " of " + rows.size() + " rows. Use \"Save result as CSV\" in the app for the full table.", false, 8, new Color(0x777777), 0);
            }
        }

        private void tableHeader(List<String> headers, double[] cw, double size, double rowH) {
            rect(M, y, W - 2 * M, rowH, new Color(0xEEEEEE));
            double x = M;
            for (int c = 0; c < headers.size(); c++) {
                String h = fit(headers.get(c), true, size, cw[c] - 6);
                boolean right = false;
                text(right ? x + cw[c] - 3 - width(h, true, size) : x + 3, y + size + 2.5, true, size, h, new Color(0x222222));
                x += cw[c];
            }
            y += rowH;
        }

        byte[] build() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int nJ = jpegs.size(), np = pages.size(), base = 5 + nJ, nObj = 4 + nJ + 2 * np;
            long[] off = new long[nObj + 1];
            out.write("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n".getBytes(StandardCharsets.ISO_8859_1));
            StringBuilder kids = new StringBuilder();
            for (int k = 0; k < np; k++) kids.append(base + 2 * k + 1).append(" 0 R ");
            plain(out, off, 1, "<< /Type /Catalog /Pages 2 0 R >>");
            plain(out, off, 2, "<< /Type /Pages /Count " + np + " /Kids [" + kids + "] >>");
            plain(out, off, 3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
            plain(out, off, 4, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
            for (int i = 0; i < nJ; i++)
                stream(out, off, 5 + i, "/Type /XObject /Subtype /Image /Width " + dims.get(i)[0] + " /Height " + dims.get(i)[1]
                        + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode", jpegs.get(i));
            for (int k = 0; k < np; k++) {
                ByteArrayOutputStream z = new ByteArrayOutputStream();
                try (DeflaterOutputStream d = new DeflaterOutputStream(z)) {
                    d.write(pages.get(k).toString().getBytes(StandardCharsets.ISO_8859_1));
                }
                stream(out, off, base + 2 * k, "/Filter /FlateDecode", z.toByteArray());
                StringBuilder xo = new StringBuilder();
                for (int idx : pageImgs.get(k)) xo.append("/Im").append(idx).append(' ').append(5 + idx).append(" 0 R ");
                plain(out, off, base + 2 * k + 1, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + n(W) + " " + n(H)
                        + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> /XObject << " + xo + ">> >> /Contents " + (base + 2 * k) + " 0 R >>");
            }
            long xref = out.size();
            out.write(("xref\n0 " + (nObj + 1) + "\n0000000000 65535 f \n").getBytes(StandardCharsets.ISO_8859_1));
            for (int id = 1; id <= nObj; id++) out.write(String.format("%010d 00000 n \n", off[id]).getBytes(StandardCharsets.ISO_8859_1));
            out.write(("trailer\n<< /Size " + (nObj + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1));
            return out.toByteArray();
        }

        private static void plain(ByteArrayOutputStream out, long[] off, int id, String body) throws IOException {
            off[id] = out.size();
            out.write((id + " 0 obj\n" + body + "\nendobj\n").getBytes(StandardCharsets.ISO_8859_1));
        }

        private static void stream(ByteArrayOutputStream out, long[] off, int id, String dict, byte[] data) throws IOException {
            off[id] = out.size();
            out.write((id + " 0 obj\n<< " + dict + " /Length " + data.length + " >>\nstream\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(data);
            out.write("\nendstream\nendobj\n".getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    static final class ChartExport {
        static File dir;

        static String fileName(ChartData cd) {
            String base = cd.title == null || cd.title.isBlank() ? "chart" : cd.title;
            base = base.replaceAll("[^A-Za-z0-9._ -]+", "").trim().replaceAll("\\s+", "_");
            if (base.isEmpty()) base = "chart";
            return base.length() > 60 ? base.substring(0, 60) : base;
        }

        /** The chart drawn at twice its on-screen size, so it stays sharp in slides and documents. */
        static BufferedImage image(ChartData cd) { return ReportBuilder.render(cd, 2); }

        static void writePng(ChartData cd, File f) throws IOException {
            if (!ImageIO.write(image(cd), "png", f)) throw new IOException("This Java installation can't write PNG files.");
        }

        /** Asks where to save; returns the chosen .png file, or null if cancelled. */
        static File choose(Component parent, String suggestion, String title) {
            JFileChooser fc = new JFileChooser(dir);
            fc.setDialogTitle(title);
            fc.setFileFilter(new FileNameExtensionFilter("PNG image", "png"));
            fc.setSelectedFile(new File(suggestion + ".png"));
            if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return null;
            File f = fc.getSelectedFile();
            if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".png")) f = new File(f.getPath() + ".png");
            if (f.exists() && JOptionPane.showConfirmDialog(parent, f.getName() + " already exists. Overwrite it?", "Save chart",
                    JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return null;
            return f;
        }

        static File saveDialog(Component parent, ChartData cd) {
            File f = choose(parent, fileName(cd), "Save chart as PNG");
            if (f == null) return null;
            try {
                writePng(cd, f);
                dir = f.getParentFile();
                return f;
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(parent, "Could not save the chart: " + ex.getMessage());
                return null;
            }
        }

        /** Several charts stacked into one picture (what "Copy chart" puts on the clipboard when an answer has two). */
        static BufferedImage combined(List<ChartData> charts) {
            if (charts.size() == 1) return image(charts.get(0));
            List<BufferedImage> imgs = new ArrayList<>();
            int w = 0, h = 0;
            for (ChartData cd : charts) {
                BufferedImage im = image(cd);
                imgs.add(im);
                w = Math.max(w, im.getWidth());
                h += im.getHeight() + 16;
            }
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            int y = 0;
            for (BufferedImage im : imgs) {
                g.drawImage(im, 0, y, null);
                y += im.getHeight() + 16;
            }
            g.dispose();
            return out;
        }

        static void copy(Component parent, ChartData cd) { copy(parent, image(cd)); }

        static void copy(Component parent, BufferedImage img) {
            try {
                java.awt.datatransfer.Transferable tr = new java.awt.datatransfer.Transferable() {
                    public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }

                    public boolean isDataFlavorSupported(DataFlavor fl) { return DataFlavor.imageFlavor.equals(fl); }

                    public Object getTransferData(DataFlavor fl) throws java.awt.datatransfer.UnsupportedFlavorException {
                        if (!isDataFlavorSupported(fl)) throw new java.awt.datatransfer.UnsupportedFlavorException(fl);
                        return img;
                    }
                };
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(tr, null);
            } catch (HeadlessException | IllegalStateException ex) {
                JOptionPane.showMessageDialog(parent, "Could not copy the chart: " + ex.getMessage());
            }
        }
    }

    static final class ReportBuilder {
        static byte[] build(DataTable t, boolean demo, List<String> insightLines, List<ReportEntry> log) throws IOException {
            Color ink = new Color(0x222222), grey = new Color(0x777777), blue = new Color(0x1a5fb4);
            Pdf p = new Pdf();
            p.para("DataChat report", true, 24, ink, 0);
            p.gap(4);
            p.para("Dataset: " + t.name + (demo ? " (demo data)" : "") + "  -  " + String.format(Locale.US, "%,d", t.rows) + " rows x " + t.cols.size() + " columns", false, 10, grey, 0);
            p.para("Generated: " + LocalDate.now(), false, 10, grey, 0);
            StringBuilder cols = new StringBuilder("Columns: ");
            for (int i = 0; i < t.cols.size(); i++)
                cols.append(i > 0 ? ", " : "").append(t.cols.get(i).name).append(" (").append(t.cols.get(i).type.name().toLowerCase()).append(")");
            p.para(cols.toString(), false, 9, grey, 0);
            if (!insightLines.isEmpty()) {
                p.gap(12);
                p.para("Key insights", true, 14, ink, 0);
                p.gap(2);
                for (String line : insightLines) p.para("-  " + line, false, 10, ink, 8);
            }
            if (log.isEmpty()) {
                p.gap(14);
                p.para("No questions were asked in this session.", false, 10, ink, 0);
            }
            int qn = 0;
            for (ReportEntry e : log) {
                qn++;
                p.gap(16);
                double need = 130;   // heading + answer text, plus the first chart so they are not split across pages
                if (!e.result().charts.isEmpty()) {
                    ChartData c0 = e.result().charts.get(0);
                    need += (Pdf.W - 2 * Pdf.M) * chartHeight(c0) / 1000.0;
                }
                p.ensure(Math.min(need, Pdf.H - 2 * Pdf.M - 10));
                p.rule(Pdf.M, Pdf.W - Pdf.M, p.y, new Color(0xCCCCCC), 0.6);
                p.gap(10);
                p.para("Q" + qn + ".  " + e.question(), true, 12, blue, 0);
                p.gap(4);
                p.para(e.answer(), false, 10, ink, 0);
                Result r = e.result();
                p.gap(2);
                p.para("Based on " + String.format(Locale.US, "%,d", r.matched) + " of " + String.format(Locale.US, "%,d", r.total) + " rows"
                        + (e.filters().isEmpty() ? "" : "  |  filters: " + e.filters()), false, 8, grey, 0);
                p.gap(6);
                for (ChartData cd : r.charts) p.image(render(cd));
                if (!r.rows.isEmpty() && !r.headers.isEmpty()) {
                    p.gap(2);
                    p.table(r.headers, r.rows, 25);
                }
            }
            return p.build();
        }

        static int chartHeight(ChartData cd) {
            return cd.type == ChartType.HBAR ? Math.max(340, Math.min(760, 120 + 30 * cd.labels.size())) : 420;
        }

        static BufferedImage render(ChartData cd) { return render(cd, 1); }

        static BufferedImage render(ChartData cd, int scale) {
            int w = 1000, h = chartHeight(cd);
            ChartPanel cp = new ChartPanel(cd);
            cp.setSize(w, h);
            BufferedImage img = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w * scale, h * scale);
            g.scale(scale, scale);
            cp.print(g);
            g.dispose();
            return img;
        }
    }
}
