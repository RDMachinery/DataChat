# DataChat

**Ask your CSV questions in plain English and get an answer plus a chart.**

No pivot tables, no remembering which formula does what. Load a CSV and type things like:

- *"What's driving the drop in March?"*
- *"Chart the financial transactions by region"*
- *"Monthly revenue by category, excluding refunded"*
- *"Share of revenue by channel as a pie chart"*

DataChat is a single-file Java Swing desktop app with **no external dependencies**. It works with Claude (Anthropic API) for natural-language understanding, and it also ships with a built-in **offline engine and demo data** so you can try it without an API key.

---

## Contents

- [Quick start](#quick-start)
- [Using the app](#using-the-app)
- [Example questions](#example-questions)
- [How it works](#how-it-works)
- [The analysis plan](#the-analysis-plan)
- [Driver analysis ("why did it drop?")](#driver-analysis-why-did-it-drop)
- [Privacy](#privacy)
- [Offline mode](#offline-mode)
- [CSV handling](#csv-handling)
- [Limitations](#limitations)
- [Troubleshooting](#troubleshooting)
- [Code tour](#code-tour)

---

## Quick start

**Requirements:** Java 17 or newer (JDK for the commands below).

```bash
# Run directly from source (Java 11+ source launcher)
java DataChat.java

# or compile first
javac DataChat.java
java DataChat
```

The app opens with **demo data already loaded**, so you can click an example question straight away.

### Enabling Claude (optional)

Either paste your API key into the **Claude API key** field in the toolbar, or set environment variables before launching:

```bash
export ANTHROPIC_API_KEY="sk-ant-..."
export ANTHROPIC_MODEL="claude-sonnet-5-5"   # optional; this is the default
java DataChat.java
```

The key is held in memory only and is never written to disk. Without a key (or with **Offline mode** ticked) the built-in rule-based engine answers instead.

---

## Using the app

### Window layout

| Area | What it does |
|---|---|
| **Toolbar** | Load CSV, Demo data, API key, model name, Offline mode, and the sample-rows privacy toggle |
| **Chat (left)** | Your questions and the analyst's plain-English answers, plus clickable example questions |
| **Chart tab** | The chart(s) for the latest answer. Hover for exact values. **Save chart as PNG...** exports it |
| **Result table tab** | The exact numbers behind the answer. **Save result as CSV...** exports them (see [Exporting results](#exporting-results)) |
| **Your data tab** | A scrollable view of the loaded CSV |
| **How it was computed tab** | The JSON plan that was executed, how many rows were used, and the computed facts |

The status bar shows the loaded file, row and column counts, and which engine is active (Claude or offline).

### Loading data

- Click **Load CSV...**, or **drag and drop** a `.csv`, `.tsv` or `.txt` file onto the window.
- Click **Demo data** to load (or reload) the built-in sample.

### Demo data

The demo is 2,317 synthetic financial transactions from January to June 2026 with these columns: `TransactionID, Date, Region, Category, Channel, Status, Amount`.

It contains a deliberate story: **March dips** because the North region (store closures) and the Hardware category (supply shortage) sell much less. Ask *"What's driving the drop in March?"* and the analysis should surface exactly those two.

### Exporting results

Open the **Result table** tab and click **Save result as CSV...** to save the table behind the latest answer. The button is greyed out until you've asked a question.

- The suggested file name comes from the answer's title.
- Numbers are written as plain values (`12503.58`, not `+12,503.58`) so they open cleanly in Excel, Numbers or Google Sheets. Percentage columns such as `-59.4%` stay as text.
- The file is UTF-8 with a byte-order mark, which Excel needs to display non-ASCII characters correctly, and fields containing commas, quotes or line breaks are quoted.
- The chart isn't part of the CSV. Use **Save chart as PNG...** for that.

### Asking questions

Type in the box at the bottom of the chat and press Enter (or click **Ask**). With Claude enabled, follow-ups can build on the previous question, for example *"now split that by channel"* or *"only for North"*. The last three turns are sent as context.

---

## Example questions

| Ask | You get |
|---|---|
| `Chart the financial transactions by region` | Bar chart of total Amount per Region |
| `Monthly revenue by category` | Multi-line chart, one line per Category |
| `What's driving the drop in March?` | Trend chart plus a ranked breakdown of which segments caused the change |
| `Why did revenue rise in May?` | Same analysis for an increase |
| `Compare February and March` | Driver analysis between two named periods |
| `Share of revenue by channel as a pie chart` | Pie chart with percentages |
| `Average amount by category, excluding refunded` | Filtered average per Category |
| `How many transactions per month?` | Row counts over time |
| `Revenue by weekday` | Totals for Mon to Sun |
| `Show amount by region for Q2` | Date-filtered totals |
| `Top 5 largest transactions` | The five biggest rows, as a table |
| `Describe this data` | Column-by-column overview |

---

## How it works

DataChat splits the problem so that the language model never has to do arithmetic:

```
 your question
      |
      v
 1. PLAN      Claude reads the question + a compact schema of your table
              and replies with a small JSON "analysis plan".
      |
      v
 2. EXECUTE   The app runs that plan itself on the FULL dataset
              (filter, group, aggregate, compare), so numbers are exact.
      |
      v
 3. EXPLAIN   Claude writes a short plain-English answer from the
              computed result table; the app draws the chart.
```

Why this design:

- **Accuracy.** Every number comes from Java code running over all your rows, not from a model's estimate.
- **Privacy.** Claude sees the schema and the small aggregated result, not your raw rows (see [Privacy](#privacy)).
- **Transparency.** The *How it was computed* tab shows the exact plan that ran, so you can verify the answer.
- **Graceful fallback.** If the API call fails, the app falls back to the offline engine and tells you.

### What Claude receives

For the planning call:

- The table name, row count and, for each column, its **type**: `NUMBER`, `DATE` or `TEXT`.
- Per column: numeric min, max and mean; date range; or the distinct values of text columns (up to 25, otherwise a few examples).
- Optionally the first 3 rows (only if **Send sample rows to AI** is ticked).
- The last three question/plan pairs, and today's date.

For the explanation call: your question, the executed plan, the computed facts, and at most the first 30 rows of the result table.

Both calls go to `https://api.anthropic.com/v1/messages` using Java's built-in `HttpClient`.

---

## The analysis plan

Claude (or the offline planner) produces one JSON object. The engine executes it.

```json
{
  "intent": "aggregate",
  "title": "Total Amount by month and Category",
  "metric": "Amount",
  "agg": "sum",
  "x": "Date",
  "bucket": "month",
  "series": "Category",
  "filters": [{ "column": "Status", "op": "!=", "value": "Refunded" }],
  "sort": "none",
  "limit": null,
  "chart": "line"
}
```

| Field | Meaning |
|---|---|
| `intent` | `aggregate` (group + chart), `drivers` (explain a change), `rows` (list individual rows), `describe` (column overview) |
| `metric` | Numeric column to measure, or `null` to count rows |
| `agg` | `sum`, `avg`, `count`, `min`, `max`, `median` |
| `x` | Column for the x-axis or grouping (`null` = one overall number) |
| `bucket` | For date columns: `day`, `weekday`, `week`, `month`, `quarter`, `year` (auto-chosen from the date span if omitted) |
| `series` | Optional second grouping, drawn as separate colours (max 8 series) |
| `filters` | `column`, `op` (`=`, `!=`, `>`, `<`, `>=`, `<=`, `contains`, `in`, `not in`) and `value`. Date filters accept partial ISO dates like `2026-03` |
| `sort` / `limit` | Ordering and top-N. Categories default to descending by value; large category sets are capped at 20 |
| `chart` | `bar`, `line`, `pie`, `none` (omitted means auto) |
| `from` / `to` / `direction` | For `drivers`: the periods to compare (for example `2026-02`, `2026-03`) and whether to look for a `drop` or a `rise` |

Chart choice when `chart` is auto: time on the x-axis gives a line (or bars for very few points), categories give bars (horizontal when there are many or the labels are long), and pie is used only when requested with up to 10 positive slices.

If a column name in a plan doesn't match exactly, the engine tries case-insensitive and fuzzy matching, and otherwise reports the available columns.

---

## Driver analysis ("why did it drop?")

For questions like *"what's driving the drop in March?"* the engine:

1. Buckets the data by period (month by default) and totals the measure for each.
2. Picks the periods to compare. A single named period is compared with the one before it. If none is named, it finds the **largest period-on-period drop** (or rise).
3. For every text column with 2 to 40 distinct values, computes each segment's value in both periods.
4. Ranks segments by their **excess movement**: how far they moved beyond what they'd have done if they had followed the overall trend. This stops large, stable segments (such as `Status = Completed`, which is most of the data) from topping the list just because they are big.
5. Also checks **two-way combinations** (for example `Region = North & Category = Hardware`) and keeps only those that moved clearly more (at least 10 percentage points) than both of their parts.
6. Reports segments that lagged the trend or moved the opposite way, and splits the change into **row count vs average value per row**.

You get two charts: the trend with both periods highlighted, and a horizontal bar chart of each segment's change (green up, red down). The result table shows each segment's change, percentage change and **share of the total change**. Shares are computed within each dimension separately, so they can overlap across dimensions.

If the final period in the data stops mid-month, the answer warns that it may be incomplete.

---

## Privacy

- Your CSV is read and processed **locally**. Aggregation, filtering and charting all happen in Java on your machine.
- Claude only ever receives the schema summary, the aggregated result table (30 rows at most), and, **only if you tick "Send sample rows to AI"**, the first three data rows.
- Text-column summaries include distinct values (up to 25 per column), so avoid using Claude with columns whose *values* are sensitive, or use **Offline mode**.
- In **Offline mode** nothing leaves your machine.

---

## Offline mode

With no API key (or **Offline mode** ticked), a built-in rule-based planner turns your question into the same JSON plan. It understands:

- **Measures:** column names mentioned in the question, plus words like *average, median, max, min, how many, count*. If you don't name a measure, it picks the most money-like numeric column and says so.
- **Grouping:** *by, per, each, across*, column names (and their plurals), and time words such as *monthly, per week, over time, trend, weekday*.
- **Filters:** category values appearing in the question (`North`, `Hardware`), exclusions (*excluding refunded*, *without retail*), and dates (*in March*, *Q2*, *2026*).
- **Ranking and charts:** *top 5, bottom 3, lowest, best*, and *pie / line / bar*.
- **Change questions:** *drop, fell, decline, rise, why, driving, compare X and Y*.

It's good for exploring and for demos. It is keyword-based, so unusual phrasing and conversational follow-ups work best with Claude enabled.

---

## CSV handling

- **Delimiters** are auto-detected: comma, semicolon, tab or pipe. Quoted fields and escaped quotes are supported.
- **Encoding:** UTF-8 (BOM handled), with a fallback to Windows-1252 if invalid bytes are found.
- **Numbers:** currency symbols, thousands separators and percent signs are stripped (`$1,200.50`, `£99`, `12%`), and `(20)` is read as `-20`.
- **Dates:** auto-detected from `yyyy-MM-dd`, `yyyy-MM`, `yyyy/MM/dd`, `d/M/yyyy` (day first is tried before month first), `M/d/yyyy`, `d-M-yyyy`, `d.M.yyyy`, and `d MMM yyyy` / `MMM d, yyyy` styles. A time portion is ignored.
- **Type detection:** a column is `NUMBER` or `DATE` if at least 95% of its non-empty values parse. Columns named like IDs (`Customer ID`, `OrderID`, `zip`, `phone`) are kept as text so they aren't summed.
- Blank headers become `Column N`, and duplicate headers get a numeric suffix.

---

## Limitations

- The data is held in memory, so very large files (millions of rows) will need a bigger heap (`java -Xmx4g ...`).
- Only one table at a time. There are no joins across files.
- Day-first dates are assumed when a column is ambiguous (for example `03/04/2026` with no day above 12).
- Stacked charts and multiple y-axes aren't supported. Multi-series data is drawn as grouped bars or multiple lines.
- Driver analysis considers text columns (up to 40 distinct values each) and two-way combinations of columns with up to 12 values. Numeric columns aren't used as dimensions.
- The offline engine can't resolve follow-ups that depend on the previous question.
- A language model can occasionally misread a question. Check the **How it was computed** tab if an answer surprises you.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Status bar says *offline engine* | No API key entered, or **Offline mode** is ticked |
| Note says *Claude's plan could not be used* | The API returned an error (bad key, wrong model name, no network) or an invalid plan. The message includes the HTTP error. The answer came from the offline engine |
| `I couldn't find a column called ...` | The plan referenced a column that doesn't exist. Rephrase using the column names listed in the chat |
| `... isn't numeric` | You asked to sum or average a text or date column. Use a numeric column, or count instead |
| Dates not recognised | Check the **Your data** tab and the *Describe this data* answer to see the detected type. Reformat the column as `yyyy-MM-dd` |
| Garbled characters | Re-save the file as UTF-8 |
| Out of memory | Launch with a larger heap: `java -Xmx4g DataChat.java` |

---

## Code tour

Everything lives in `DataChat.java`, organised as nested classes:

| Class | Responsibility |
|---|---|
| `DataChat` | Swing UI: toolbar, chat transcript, tabs, drag and drop, background `SwingWorker` tasks |
| `DataTable`, `Column` | In-memory table, CSV parsing, type detection (`NUMBER` / `DATE` / `TEXT`), schema description for the model |
| `Plan`, `Filter` | The analysis plan, with JSON conversion |
| `Engine` | Executes a plan: filtering, grouping, aggregation, driver analysis, fact generation |
| `Result`, `ChartData` | Output of the engine: table rows, chart specs, plain-English facts |
| `LocalPlanner` | Offline natural-language to plan translation, and offline answer text |
| `Claude` | Anthropic Messages API client plus the planner and explainer system prompts |
| `Json` | Minimal JSON reader and writer (no libraries) |
| `ChartPanel` | Java2D chart rendering: bar, horizontal bar, line, pie, with tooltips |
| `DemoData` | Synthetic transactions generator and example questions |

### Extending it

- **New aggregation:** add it to `Acc.get`, `Engine.aggOf` and `measureLabel`, and mention it in `Claude.PLAN_SYSTEM`.
- **New time bucket:** add a case in `Engine.bucketLabel` and the allowed set in `Engine.bucketOf`.
- **New chart type:** add a `ChartType`, a paint method in `ChartPanel`, and a rule in `Engine.chartType`.
- **Different model:** change the **Model** field or set `ANTHROPIC_MODEL`.
- **Your own demo data:** edit `DemoData.create()` and `DemoData.examplesFor()`.
