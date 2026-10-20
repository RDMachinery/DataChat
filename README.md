# DataChat

**Ask your CSV or Excel data questions in plain English and get an answer plus a chart.**

No pivot tables, no remembering which formula does what. Load a CSV or Excel file and type things like:

- *"What's driving the drop in March?"*
- *"Chart the financial transactions by region"*
- *"Monthly revenue by category, excluding refunded"*
- *"Share of revenue by channel as a pie chart"*
- *"Are there any unusual transactions?"*

DataChat is a single-file Java Swing desktop app with **no external dependencies**. It works with Claude (Anthropic API) for natural-language understanding, and it also ships with a built-in **offline engine and demo data** so you can try it without an API key.

---

## Contents

- [Quick start](#quick-start)
- [Using the app](#using-the-app)
- [Auto-insights](#auto-insights)
- [Follow-up questions](#follow-up-questions)
- [Click a chart to drill down](#click-a-chart-to-drill-down)
- [Correlation and scatter plots](#correlation-and-scatter-plots)
- [Unusual values and outliers](#unusual-values-and-outliers)
- [Cleaning data](#cleaning-data)
- [Calculated columns](#calculated-columns)
- [Joining two files or sheets](#joining-two-files-or-sheets)
- [Comparing two periods](#comparing-two-periods)
- [Forecasting](#forecasting)
- [Switching chart type and stacked charts](#switching-chart-type-and-stacked-charts)
- [Saving charts as images](#saving-charts-as-images)
- [Dark mode](#dark-mode)
- [PDF report](#pdf-report)
- [Example questions](#example-questions)
- [How it works](#how-it-works)
- [The analysis plan](#the-analysis-plan)
- [Driver analysis ("why did it drop?")](#driver-analysis-why-did-it-drop)
- [Privacy](#privacy)
- [Offline mode](#offline-mode)
- [CSV handling](#csv-handling)
- [Excel files](#excel-files)
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
| **Toolbar** | Two rows. Top: **Load CSV / Excel...**, **Demo data**, **Export PDF report...** (greyed out until you've asked a question) and the **Dark mode** checkbox. Bottom: Claude API key, model name, Offline mode and the sample-rows privacy toggle |
| **Chat (left)** | Your questions, the analyst's plain-English answers, the auto-insights for the loaded file, and clickable example questions |
| **Chart tab** | The chart(s) for the latest answer. Hover for exact values, and **click a bar, point or slice to drill down** (see below). **Save chart as PNG...**, **Copy chart** and a right-click on the chart save or copy it as an image (see [Saving charts as images](#saving-charts-as-images)) |
| **Result table tab** | The exact numbers behind the answer. **Save result as CSV...** exports them (see [Exporting results](#exporting-results)) |
| **Your data tab** | A scrollable view of the loaded CSV |
| **How it was computed tab** | The JSON plan that was executed, how many rows were used, and the computed facts |

The status bar shows the loaded file, row and column counts, and which engine is active (Claude or offline).

### Loading data

- Click **Load CSV / Excel...**, or **drag and drop** a `.csv`, `.tsv`, `.txt`, `.xlsx` or `.xlsm` file onto the window. Excel files are covered in [Excel files](#excel-files).
- Click **Demo data** to load (or reload) the built-in sample.

### Demo data

The demo is 2,317 synthetic financial transactions from January to June 2026 with these columns: `TransactionID, Date, Region, Category, Channel, Status, Amount, Units, Discount (%)`. `Units` and `Discount (%)` are there so you can try the correlation and scatter-plot questions.

It contains a deliberate story: **March dips** because the North region (store closures) and the Hardware category (supply shortage) sell much less. Ask *"What's driving the drop in March?"* and the analysis should surface exactly those two.

### Auto-insights

As soon as a file loads, DataChat scans it and posts a short list of things worth a closer look in the chat. Each line ends with an **explore** link: click it and the full analysis (table, charts and explanation) runs immediately, with no typing.

| Insight | What triggers it |
|---|---|
| **Biggest drop** | The largest period-on-period fall in the main measure, if it is at least 10%. *Explore* runs the driver analysis for exactly those two periods |
| **Biggest rise** | The same for the largest increase |
| **Over-indexing segment** | A segment whose share of the measure differs from its share of rows by at least 10 points, for example *"Services is 16% of rows but 33% of Total Amount"*. When the table has no numeric column it flags a segment that makes up at least half of all rows |
| **Unusual values** | Values above Q3 + 3 x IQR in the main numeric column (shown only if they are under 5% of rows) |
| **Overall trend** | The change from the first to the last period, if it is at least 5% |
| **Missing data** | A column that is at least 20% empty |

At most five insights are shown, in that priority order. Details:

- The "main measure" is the first numeric column whose name looks like money (*amount, revenue, sales, price, cost, profit...*), otherwise the first numeric column. With no numeric columns, insights count rows instead.
- A last month that stops part-way through is ignored when looking for drops, so an incomplete month never shows up as a fake slump.
- Insights are computed locally, so they never call the API. Clicking *explore* does use Claude for the explanation if a key is set.
- The insights are also printed in the [PDF report](#pdf-report).

### Follow-up questions

You can refine an answer instead of retyping the whole question. This works with Claude (the last three question/plan pairs are sent as context) and, new, in **offline mode** too, where the app keeps the previous plan and changes only what you say.

| Say | What changes |
|---|---|
| `Now split that by channel` | Adds Channel as a second grouping (separate lines or bars) and keeps everything else |
| `Only North` / `What about South?` | Adds a filter. A new filter on the same column replaces the old one |
| `And in Q2` | Replaces the date window |
| `Average instead` | Changes the aggregation, keeps the grouping and filters |
| `As a pie chart` / `as a bar chart` | Changes the chart type only |
| `Now by region` | Replaces the grouping (without a "split" word, the new column replaces the old one) |
| `Top 3` | Limits and sorts the previous answer |
| `Colour by category` | After a scatter plot, colours the points by a text column |
| `Clear filters` | Removes all earlier filters |
| `Start over` / `new question` | Ignores the previous question |

Notes:

- The answer says what it changed ("Follow-up to your previous question: split by Channel") and reminds you when earlier filters are still applied.
- A follow-up is recognised by short sentences that start with words like *now, and, also, only, just, what about, split, colour* or refer back (*that, it, same*), or by very short questions such as `by region` or `top 3`. A full question such as `Monthly revenue by category` always starts fresh.
- After a driver analysis ("what's driving the drop in March?"), `only Hardware` re-runs the same analysis on Hardware, and `now by region` turns it into an ordinary grouped answer.
- If nothing in the follow-up is recognised, it is treated as a new question.

### Click a chart to drill down

Charts are clickable. Hover shows the exact value and a hint (*click to drill down*), and the cursor turns into a hand. A click runs a follow-up analysis straight away, with no typing:

| You click | What runs |
|---|---|
| A **period** on a time axis (month, week, quarter...) | *What changed in that period compared with the previous one?* (the driver analysis). On the first period, which has nothing before it, a breakdown of that period instead |
| A **category** bar or slice (a region, a channel...) | The data filtered to that category, shown over time (or broken down further if the file has no date column) |
| A **segment** in the driver chart (for example `Region = North`) | That segment over time |
| A bar or point in a **multi-series** chart | The same, restricted to that series too (for example March, Services only) |

Filters from the chart you clicked carry over, so drilling is cumulative. Weekday charts and scatter plots are not clickable. With a Claude key, the drill-down answer is explained by Claude like any other question.

### Correlation and scatter plots

Ask how numeric columns relate and you get a scatter plot with a fitted line, plus the statistics.

| Ask | You get |
|---|---|
| `Scatter plot of amount vs units` | Scatter of Amount (y) against Units (x). *"A vs B"* puts A on the y-axis |
| `Is discount related to amount?` | The same for the two columns you named |
| `Correlation between units and amount by category` | Points coloured by Category (up to 6 groups) |
| `Which numeric columns are related?` | A correlation matrix of all numeric columns, plus a scatter of the strongest pair |
| `What is units correlated with?` | The strongest partner of the one column you named |

What you see:

- **Chart:** points, a dashed least-squares line, and a caption with *r* and *n*. Tooltips show exact values (up to 1,500 points; larger sets are sampled to about 4,000 for drawing, while all statistics use every row).
- **Result table:** row count, Pearson *r*, Spearman rank correlation, R-squared, slope, intercept and the two means (or the full matrix).
- **Plain-English answer:** strength and direction (negligible, weak, moderate, strong, very strong), what each +1 in x goes with in y, and warnings. It tells you when a result is within random noise for the sample size, when Spearman and Pearson disagree (a curved relationship or outliers), and that correlation does not prove causation.

Filters work as usual (*"correlation of amount and units for North in Q2"*). Colouring a scatter by category is a quick way to spot a relationship that is really a difference between groups: in the demo data, each Category forms its own band.

### Unusual values and outliers

Ask for anything odd, unusual, abnormal, suspicious or out of the ordinary, or for *outliers* or *anomalies*, and DataChat runs a statistical check and shows what stands out.

| Ask | You get |
|---|---|
| `Any unusual transactions?` | Every numeric column is scanned. The table lists the most extreme values with their whole row, and the chart counts the unusual values per column |
| `Are there any outliers in amount?` | One column scanned. A bar chart of the most extreme rows, labelled with their row number and category columns |
| `Show me odd amounts by category` | Each value is judged against **its own category** (a 300 order may be normal for Hardware and strange for Subscriptions) |
| `Which weeks look unusual?` / `Which days had unusual revenue?` | **Time-period mode:** totals per day, week, month, quarter or year, drawn as a line with the unusual periods shaded and a second line showing what "normal" would have been |
| `Top 5 outliers in units` | Only the five most extreme |
| `Unusual transactions in March` | Filters work as usual, so the check runs on the matching rows only |

How it decides:

- **Rows:** each value gets a robust z-score based on the **median and the median absolute deviation (MAD)**, so the outliers themselves cannot hide the spread the way they can with a mean and standard deviation. A value is flagged when its score is beyond **+/-3.5**, the usual cut-off for this method. Scores in the table keep their sign: `+8.8` is unusually high, `-12.7` unusually low.
- **Skewed money columns:** if every value is positive and the column is clearly right-skewed (mean more than 1.5 times the median), values are judged on a **log scale**, otherwise ordinary long-tailed amounts would all be flagged. The answer tells you when it did this.
- **Time periods:** each period is compared with the median of nearby periods (the same weekday up to four weeks either side for daily data, otherwise the three periods either side), then the differences are scored the same way. This copes with a steady trend and with weekday patterns. If the data only spans a few months, month questions automatically drop to weeks, and a partly-covered first or last period is left out of totals and counts so it isn't mistaken for a dip.
- **Table:** `Data row` (the position among data rows, header not counted), `Column`, `Value`, `Typical` (the median), `Score`, then every column of that row. Up to 15 rows are listed by default; ask for a different number (*"top 30 outliers"*) up to 200. **Save result as CSV...** keeps the original text of each cell.
- **Not tested:** columns where most values are identical (there is no spread to measure) or where fewer than 8 values are available. The answer says which.

Follow-ups work: after *"any unusual transactions?"*, try *"only North"*, *"now by category"* or *"just units"*. The auto-insights on load also include a one-line outlier summary for the main measure, which opens this same analysis when clicked.

Things to keep in mind: unusual does not mean wrong, it means different from the rest, so check each one. The cut-off is fixed at 3.5. Outliers are judged one column at a time, so a combination that is odd only when two columns are read together (a huge discount on a tiny order, say) won't be found.

### Cleaning data

Real files are messy: dates read the wrong way round, numbers stored as text because one cell says `N/A`, duplicate rows, columns you don't need. **Clean data...** (top toolbar, or type *"clean the data"* or *"remove duplicates"*) fixes these in one place.

- **Columns:** untick **Keep** to remove a column, edit **Column name** to rename it, and use **Read as** when the detected type is wrong: *Number (1,234.56)*, *Number (1.234,56)*, *Date (day first)*, *Date (month first)* or *Text*. The *Detected as* and *Example values* columns show what DataChat made of each one.
- **Treat these values as empty:** a list (by default `N/A, NA, #N/A, null, NaN, -, --, ?`) of values that mean "nothing here". A column that was read as text only because of a few `N/A` cells becomes a proper number column. Edit the list to suit your file; matching ignores capitals.
- **Remove duplicate rows:** drops rows that are identical to an earlier row in every kept column, keeping the first copy.
- A live line under the options says how many cells would become empty and how many rows are duplicates, before you apply anything.

Click **Apply cleaning** and the result becomes the table you ask questions about, named after the original with *(cleaned)* added. The chat summarises exactly what changed: rows and columns before and after, cells treated as empty, duplicates removed, columns renamed or removed, and any column whose type changed. If a forced type can't read some values (for example `13/04/2026` read month-first), those values are left blank and the summary says how many.

The original file is never modified. Load it again to start over.

Date order matters: `03/04/2026` is 3 April day-first and 4 March month-first. DataChat assumes day-first when a column is ambiguous, so use **Date (month first)** for US-style files.

### Calculated columns

Add a column computed from other columns, then use it in any question like a normal column. Type it in the chat:

- `add column Margin = Revenue - Cost`
- `add column Price per unit = Amount / Units`
- `add column Margin % = (Revenue - Cost) / Revenue * 100`
- `add column Discount share = Discount as a percent of Amount`
- `create a column called Net as Revenue minus Cost`

Or use the **Add a calculated column** section of **Clean data...**, which also shows an error message right there if the formula isn't right.

What formulas can contain:

| | |
|---|---|
| Numeric columns | By name, as written. Use `[Column name]` for awkward names, such as `[Discount (%)]` |
| Arithmetic | `+ - * / ^` and brackets |
| In words | `minus`, `plus`, `times`, `divided by`, `per`, `over`, `as a percent of`, `10% of` |
| Percentages | `10%` means 0.1 |
| Comparisons | `> < >= <= = !=` (give 1 for true, 0 for false) |
| Functions | `abs`, `round(x, decimals)`, `floor`, `ceil`, `sqrt`, `ln`, `log`, `exp`, `min(a, b)`, `max(a, b)`, `pow(a, b)`, `if(condition, a, b)` |

For example, `if(Amount > 500, 1, 0)` flags big orders, and `round(Cost / Units, 2)` rounds a unit cost.

Then ask as usual: *"Average Margin by Product"*, *"Total Margin by region"*, *"Show rows where Margin is below 0"*.

Notes:

- Only **numeric** columns can be used. A text or date column gives a clear error. Fix its type first in **Clean data...**.
- A row is left blank when an input is blank or the formula divides by zero, and the message tells you how many rows.
- A formula must use at least one column, and the name must be new. To redo one, remove the column in **Clean data...** and add it again.
- With a Claude API key, a request the offline parser can't read (*"add a column for the profit after tax at 20%"*) is sent to Claude, which only sees the column names and types and returns a formula. DataChat checks that formula with the same parser; nothing the model returns is ever run as code.
- Calculated columns live in the current table and are included if you [join](#joining-two-files-or-sheets) it or clean it. They aren't written back to your file; export a result as CSV to keep the numbers.

### Joining two files or sheets

Real questions often need data from two places: orders in one file and customer details in another, sales in one Excel sheet and targets in a second. **Join tables...** (top toolbar) combines two tables on a shared key column, and the result becomes the table you ask questions about.

1. Click **Join tables...** (or type *"join two files"* in the chat). Every file or sheet you have loaded in this session is already in the lists.
2. Click **Add file or sheet...** to bring in another CSV or Excel file. For a workbook with several sheets, select the ones you want (Ctrl-click for more than one). Hidden sheets are left out.
3. Choose the **left** and **right** tables. DataChat suggests the key columns by comparing names and values; change them if it guessed wrong. You can match on up to three columns together (for example region **and** month).
4. Read the **Match check**, which updates as you choose: what share of each table finds a partner, how many keys are blank, and examples of values that didn't match.
5. Choose which rows to keep, then click **Join**.

| Choice | Result |
|---|---|
| Keep every row of the left table *(default)* | Nothing from your main table is lost. Rows with no partner get blanks for the right table's columns |
| Keep only rows that match in both | A row appears only if both tables have the key |
| Keep all rows from both tables | Everything is kept, matched or not |

What you get:

- **A new table**, named after its parts (for example `orders + customers`), shown on **Your data**. Ask questions as usual: *"Total amount by region"* now works with columns from both files. The chat starts fresh for the new table, and the join table is itself kept in the lists so you can join it with a third file.
- **The key column from the right table isn't repeated.** The left table's key column holds the values. Other columns with the same name in both tables get the right table's name added, such as `Amount (targets)`.
- **A plain-English summary** of what happened: rows in and out, how many matched, how many didn't, and any warning.
- **Unmatched rows stay visible.** In text columns they read `(no match)`, so a chart *by manager* shows them as their own bar instead of quietly leaving them out. Number and date columns are left blank.

Things worth knowing:

- **Repeated rows inflate totals.** If one row of the left table matches several rows of the right (for example a customer with two addresses), the left row is repeated once per match and a *sum* of the left table's numbers counts it more than once. DataChat warns you in the Match check and again after joining. Use a key that is unique in the right table, add a second key column, or summarise the right table first.
- **Matching is forgiving but not fuzzy.** Capitals and extra spaces are ignored, `1`, `1.0` and `001` count as the same number, and dates match across formats (`2026-01-05` and `05/01/2026`). Anything else must be identical, so `C-001` and `C001` do not match. If nothing matches, the message shows example values from each side.
- Blank keys never match anything, in either table.
- A join can produce at most 1,000,000 rows. This only matters when neither table's key is unique, in which case every row matches many others and the result explodes.
- Joined data is analysed exactly like a loaded file, including auto-insights, drill-down, forecasting and PDF reports. Loading a new file replaces what you are looking at but keeps earlier tables in the Join lists (the last 10).

### Comparing two periods

Ask how one period differs from another and DataChat puts the two side by side, with the change broken down by segment.

- Name the periods: *"Compare Q1 with Q2 by region"*, *"February vs March"*, *"2025 versus 2026"*.
- Or use relative ones: *"last 30 days vs the previous 30"*, *"this month vs last month"*, *"month over month"*, *"year over year"*. Relative periods are measured from the **latest date in your data**, not today's date, so an old export still gives sensible answers. *Year over year* compares the year so far with the same dates a year earlier.
- Add *"by region"* (or any text column) to choose the breakdown. If you don't, DataChat picks the column with 2 to 12 values where the two periods differ most.

You get a table with both periods, the change, the change in percent and each segment's share of the overall change, plus two charts: grouped bars for the two periods and a red and green chart of the change per segment. The text names the biggest contributor and the biggest counter-mover, and warns when the periods have different lengths or the data only partly covers one of them.

This describes **what** changed. For **why**, ask *"what's driving the drop in March?"*, which runs the full [driver analysis](#driver-analysis-why-did-it-drop). Words such as *why*, *driving* or *cause* send the question there instead.

Follow-ups work too: after a comparison, try *"by channel"*, *"just units"* or *"average instead"*.

### Forecasting

Ask for a projection (*"forecast revenue for the next 3 months"*, *"what will sales look like next quarter?"*, *"predict units for the next 8 weeks"*) and DataChat extends the trend, drawing the history in blue, the forecast as a dashed line and the likely range as a shaded band.

How it works, in plain terms:

- It builds a complete calendar of periods (days, weeks, months, quarters or years, chosen from your data), filling any gaps, and drops a half-covered first or last period so it doesn't read as a fake dip.
- It fits a straight-line trend plus a repeating pattern (day of week, week of year, month of year or quarter) **only when there is enough history**: at least 14 days, 104 weeks, 24 months or 8 quarters. If the trend isn't statistically distinguishable from noise, it projects the recent level instead and says so.
- The band is an approximate 95% range that widens the further ahead it looks.
- It checks itself by hiding the most recent periods, forecasting them, and comparing against a naive "same as last period" guess. The accuracy line in the answer reports this when there is enough history.
- The table shows, per period, the forecast, the low and high ends, and the difference from the recent average.

Defaults when you don't say how far: 14 days, 8 weeks, 3 months, 2 quarters or 2 years. The horizon is capped at half the history (and 60 periods).

Be realistic about it: a forecast carries past patterns forward. It cannot know about promotions, closures or one-off events, and with fewer than about 6 periods of history DataChat refuses rather than guess. With only a few months of data, treat the result as a rough guide.

### Switching chart type and stacked charts

Above the chart, **Show as:** has six buttons: **Bars**, **Horizontal bars**, **Line**, **Pie**, **Stacked** and **100% stacked**. Click one to redraw the current answer in that style without asking again. The button for the current style is highlighted.

Buttons that wouldn't make sense are greyed out:

- **Pie** needs a single series with 2 to 12 positive values.
- **Line** needs at least 2 points (it suits time or ordered data best).
- **Stacked** and **100% stacked** need two or more series that are *parts of a whole*: totals or counts, with no negative values. Averages, minimums, maximums and medians can't be stacked, because stacking them would add up numbers that don't add up.
- Charts that have a fixed meaning, such as scatter plots, forecasts and comparisons, can't be switched, and change charts (red and green) only switch between the two bar styles.

You can also ask for stacking directly: *"Revenue by month and category, stacked"*, *"Revenue by region and channel as 100% stacked bars"*, or, after any chart with two groupings, *"make it stacked"*. A stacked chart needs a second grouping; if you ask for one without it, you get an ordinary chart and a note saying why.

- **Stacked** draws the segments on top of each other with the total above each bar, so you see both the overall level and the mix.
- **100% stacked** draws every bar to the same height, so you compare the *mix* between bars, such as each region's share by channel.
- Hover over a segment to see its value, its share of the bar and the bar's total. Click a segment to drill down, as with other charts.

Saved PNGs, copied charts and the PDF report use whatever style is on screen. Asking a new question starts from the automatic choice again.

### Saving charts as images

On the **Chart** tab, above the chart:

- **Save chart as PNG...** writes the chart to an image file. When an answer has two charts (driver analyses do), each is saved as its own file, with `-1` and `-2` added to the name you choose, which suits slides.
- **Copy chart** puts the chart on the clipboard so you can paste it straight into a slide, email or document. If there are two charts they are stacked into one picture.
- **Right-click any chart** for **Save chart as PNG...** or **Copy chart to clipboard** for just that chart.

Images are drawn at twice the on-screen size (about 2000 pixels wide) so they stay sharp. They include the title, legend and axes, and any highlighted periods. The suggested file name comes from the chart title, and DataChat asks before overwriting an existing file. The clipboard needs a desktop that supports image clipboards; if it can't, you'll get a message and can save a PNG instead.

### Dark mode

Tick **Dark mode** on the top toolbar row for a dark colour scheme that is easier on the eyes in a dim room.

- The whole window changes at once: chat, tabs, tables, buttons, dialogs and charts. Chart colours are adjusted so labels, gridlines and highlighted periods stay readable, and the chat's coloured names and links switch to lighter shades.
- Your choice is **remembered** the next time you start DataChat. Untick the box to go back to light.
- **Exports stay light.** Saved PNGs, copied charts and the PDF report are always drawn on white, so they look right in slides, documents and print.
- Dark mode uses Java's built-in cross-platform ("Metal") look, so buttons and scroll bars look the same on Windows, macOS and Linux rather than following the system style. Unticking the box returns to your system's normal look. The window's title bar is drawn by your operating system and follows its own dark or light setting.

### PDF report

Click **Export PDF report...** (top row of the toolbar, next to Demo data) to save everything you've asked in the current session as a single A4 document. The button is greyed out until you've asked at least one question.

The report contains:

1. A title block with the dataset name, row and column counts, the date, and the column list.
2. The **Key insights** found on load.
3. For every question, in order: the question, the plain-English answer, how many rows were used and any filters applied, the chart(s), and the result table (first 25 rows).

Notes:

- Loading a new file starts a fresh session, so the report only covers the current dataset.
- Charts are embedded as images and tables are real selectable text. Long tables continue across pages with the header repeated.
- The writer is built into the program (no PDF library), using the standard Helvetica fonts. Characters outside Western European text, such as Chinese, Cyrillic or Arabic, appear as `?`.
- For the complete table behind an answer, use **Save result as CSV...**.

### Exporting results

Open the **Result table** tab and click **Save result as CSV...** to save the table behind the latest answer. The button is greyed out until you've asked a question.

- The suggested file name comes from the answer's title.
- Numbers are written as plain values (`12503.58`, not `+12,503.58`) so they open cleanly in Excel, Numbers or Google Sheets. Percentage columns such as `-59.4%` stay as text.
- The file is UTF-8 with a byte-order mark, which Excel needs to display non-ASCII characters correctly, and fields containing commas, quotes or line breaks are quoted.
- The chart isn't part of the CSV. Use **Save chart as PNG...** on the Chart tab for that (see [Saving charts as images](#saving-charts-as-images)).

### Asking questions

Type in the box at the bottom of the chat and press Enter (or click **Ask**). Short follow-ups such as *"now split that by channel"* or *"only for North"* build on your previous question (see [Follow-up questions](#follow-up-questions)).

---

## Example questions

| Ask | You get |
|---|---|
| `Chart the financial transactions by region` | Bar chart of total Amount per Region |
| `Monthly revenue by category` | Multi-line chart, one line per Category |
| `What's driving the drop in March?` | Trend chart plus a ranked breakdown of which segments caused the change |
| `Why did revenue rise in May?` | Same analysis for an increase |
| `Compare February and March` | Side-by-side comparison of the two periods, with the change by segment (ask *"why did it drop from February to March?"* for the full driver analysis) |
| `Share of revenue by channel as a pie chart` | Pie chart with percentages |
| `Average amount by category, excluding refunded` | Filtered average per Category |
| `How many transactions per month?` | Row counts over time |
| `Revenue by weekday` | Totals for Mon to Sun |
| `Show amount by region for Q2` | Date-filtered totals |
| `Total amount where units > 5` | Numeric filter. Also `units over 5`, `units at most 3`, `discount above 10`, `units between 3 and 6`, `units = 5` |
| `Show rows where units > 8` | The matching rows as a table (also `list the transactions with discount above 10`) |
| `Top 5 largest transactions` | The five biggest rows, as a table |
| `Scatter plot of amount vs units` | Scatter plot with fitted line, *r* and *n* |
| `Is discount related to amount?` | Correlation statistics and a scatter plot |
| `Which numeric columns are related?` | Correlation matrix of all numeric columns |
| `Now split that by channel` | A follow-up: refines the previous answer (see [Follow-up questions](#follow-up-questions)) |
| `Any unusual transactions?` | Outlier scan across the numeric columns (see [Unusual values and outliers](#unusual-values-and-outliers)) |
| `Which weeks look unusual?` | Line chart with the odd weeks shaded |
| `Revenue by month and category, stacked` | Stacked bars with a total above each (see [Switching chart type and stacked charts](#switching-chart-type-and-stacked-charts)) |
| `Add column Price per unit = Amount / Units` | Adds a calculated column (see [Calculated columns](#calculated-columns)) |
| `Clean the data` | Opens the cleaning dialog (see [Cleaning data](#cleaning-data)) |
| `Compare Q1 with Q2 by region` | Side-by-side totals, change and share of change per Region (see [Comparing two periods](#comparing-two-periods)) |
| `Last 30 days vs the previous 30` | Same comparison for relative periods |
| `Year over year` | Year so far against the same dates a year earlier |
| `Forecast revenue for the next 3 months` | History, dashed forecast and a 95% band (see [Forecasting](#forecasting)) |
| `Join two files` | Opens the join dialog (see [Joining two files or sheets](#joining-two-files-or-sheets)) |
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
| `intent` | `aggregate` (group + chart), `drivers` (explain a change), `rows` (list individual rows), `describe` (column overview), `correlate` (relationship between numeric columns), `anomalies` (unusual rows or periods: `metric` is the column to scan or `null` for all, `series` a group to compare within, `x` + `bucket` for time periods), `compare` (two periods side by side: `from`, `to`, optional `x` as the breakdown column), `forecast` (`x` is the date column, `bucket` the period size, `limit` how many periods ahead) |
| `metric` | Numeric column to measure, or `null` to count rows |
| `agg` | `sum`, `avg`, `count`, `min`, `max`, `median` |
| `x` | Column for the x-axis or grouping (`null` = one overall number) |
| `bucket` | For date columns: `day`, `weekday`, `week`, `month`, `quarter`, `year` (auto-chosen from the date span if omitted) |
| `series` | Optional second grouping, drawn as separate colours (max 8 series) |
| `filters` | `column`, `op` (`=`, `!=`, `>`, `<`, `>=`, `<=`, `contains`, `in`, `not in`) and `value`. Date filters accept partial ISO dates like `2026-03` |
| `sort` / `limit` | Ordering and top-N. Categories default to descending by value; large category sets are capped at 20 |
| `chart` | `bar`, `line`, `pie`, `stacked`, `stacked100`, `scatter`, `none` (omitted means auto). Stacked needs a `series` and a `sum` or `count` aggregation |
| *(correlate)* | `x` is the numeric column for the x-axis, `metric` the numeric column for the y-axis (leave both `null` for a matrix, or set one to find its strongest partner), and `series` an optional text column to colour by |
| `from` / `to` / `direction` | For `drivers`: the periods to compare (for example `2026-02`, `2026-03`) and whether to look for a `drop` or a `rise` |
| `time` | For `compare`: how to read the periods. Labels such as `2026-03` (month), `2026-Q1`, `2026` (year), `2026-W09` (week), a day, or a range `2026-01-01..2026-01-31`. Relative periods are resolved against the latest date in the data |

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
- **Relationships:** *correlation, related, relationship, scatter, depends on*, or *A vs B* / *A against B* with two numeric columns.
- **Follow-ups:** short refinements of the previous answer (see [Follow-up questions](#follow-up-questions)).

It's good for exploring and for demos. It is keyword-based, so unusual phrasing works best with Claude enabled.

---

## CSV handling

- **Delimiters** are auto-detected: comma, semicolon, tab or pipe. Quoted fields and escaped quotes are supported.
- **Encoding:** UTF-8 (BOM handled), with a fallback to Windows-1252 if invalid bytes are found.
- **Numbers:** currency symbols, thousands separators and percent signs are stripped (`$1,200.50`, `£99`, `12%`), and `(20)` is read as `-20`.
- **European number formats** are detected per column: `1.234,56`, `12,5`, `1 234,5 €` and `(300,40)` are all read correctly. A value like `1.234.567,89` or `12,5` counts as European evidence and one like `1,234.56` or `1,234,567` as US evidence. Values that could be either (`1,234` or `1.200` on their own) follow the rest of the column, then the rest of the file, and default to US style if there is no evidence anywhere. Dotted dates such as `01.03.2026` are still recognised as dates, not numbers. If an ambiguous column is read the wrong way, give it one unambiguous value (for example `1.200,00`) or reformat the file.
- **Dates:** auto-detected from `yyyy-MM-dd`, `yyyy-MM`, `yyyy/MM/dd`, `d/M/yyyy` (day first is tried before month first), `M/d/yyyy`, `d-M-yyyy`, `d.M.yyyy`, and `d MMM yyyy` / `MMM d, yyyy` styles. A time portion is ignored.
- **Type detection:** a column is `NUMBER` or `DATE` if at least 95% of its non-empty values parse. Columns named like IDs (`Customer ID`, `OrderID`, `zip`, `phone`) are kept as text so they aren't summed.
- Blank headers become `Column N`, and duplicate headers get a numeric suffix.

---

## Excel files

DataChat reads **`.xlsx`** and **`.xlsm`** workbooks directly, with no Excel installation and no extra libraries (the file is unzipped and its XML parsed by the program itself). Macros are never run and external links are never followed.

- **Sheets:** if the workbook has more than one visible sheet you are asked which to load, and the dataset is named `file.xlsx [Sheet]`. Hidden sheets are not offered. To analyse another sheet, load the file again and pick it.
- **Header:** the first row of the data is the header. Title lines above the table are skipped (the chat tells you how many), blank rows and completely empty columns are dropped, and an unlabelled column is called `Column C` after its Excel letter.
- **Dates:** cells with a date or time format become real dates (`2026-03-19`, or `2026-03-19 13:30:15` when there is a time). Both the 1900 and 1904 date systems work. A date cell holding `0` counts as empty.
- **Numbers:** read at the precision Excel shows (15 significant digits), so floating-point noise such as `0.30000000000000004` doesn't appear. Cells formatted as percentages read as `25%`, which DataChat treats as the number 25, the same as a CSV with a `%` sign.
- **Text, booleans and formulas:** text is kept as is, TRUE/FALSE become `TRUE` and `FALSE`, and formulas use the **value Excel last saved** for them. A file written by another program that never calculated its formulas will show them as empty.
- **Merged cells:** only the top-left cell holds a value, so unmerge before loading if a merged cell labels several rows.
- **Not supported:** old binary `.xls` files and password-protected workbooks (you get a message saying how to re-save as `.xlsx` or CSV), and sheets with more than 1,000,000 rows.

The loaded data behaves exactly like a CSV from then on, so everything else in this guide applies.

---

## Limitations

- The data is held in memory, so very large files (millions of rows) will need a bigger heap (`java -Xmx4g ...`).
- Questions are answered against one table at a time. To use two files or sheets together, [join them](#joining-two-files-or-sheets) first. Joins are by key columns and are inner, left or full; there is no fuzzy matching, and a join can't be undone in place (load the original or join again).
- Excel: `.xls` and password-protected files aren't supported, formulas use their last saved values, and merged cells are read as a single value.
- Comparisons need dates in the data and two periods that can be told apart. The breakdown is one text column at a time (plus the automatic pick), and numeric columns aren't used as dimensions.
- Forecasts are a trend plus a repeating pattern, nothing more. They need at least 6 periods of history, use seasonality only with enough cycles (see [Forecasting](#forecasting)), and know nothing about events outside the data. The band is approximate and assumes steady noise.
- Chart type switching applies to the chart on screen; scatter, forecast and change charts keep their own styles.
- Outlier detection judges one column at a time with a fixed cut-off, and flags unusual values rather than proving errors. It needs at least 8 varied values (or 8 periods).
- Copying a chart needs a desktop that supports image clipboards. Saving a PNG always works.
- Day-first dates are assumed when a column is ambiguous (for example `03/04/2026` with no day above 12).
- Number columns whose every value is ambiguous (only `1,234`-style or `1.200`-style values, in a file with no other evidence) are read as US numbers.
- Stacked charts are bars only (no stacked areas or horizontal stacks), need totals or counts without negatives, and multiple y-axes aren't supported. Other multi-series data is drawn as grouped bars or multiple lines.
- Numeric filters in offline mode read one condition per column (for example `units > 5` or `units between 3 and 6`) and match the column name as written (the part before any brackets works for names like `Discount (%)`). More complex conditions, such as OR across columns, work best with Claude.
- Calculated columns are numeric only, have no date arithmetic and no conditions across rows (no running totals or rank). Each is computed once when you add it.
- Cleaning works on the whole table at once and replaces it with a new one (and a fresh chat); it doesn't edit individual cells. Duplicates are exact matches only.
- Driver analysis considers text columns (up to 40 distinct values each) and two-way combinations of columns with up to 12 values. Numeric columns aren't used as dimensions.
- Offline follow-ups handle the common refinements (split, filter, change aggregation, chart type, top N, colour by); more open-ended follow-ups such as *"why is that?"* work best with Claude.
- Correlation is linear (Pearson), with Spearman as a cross-check. It needs at least two numeric columns and doesn't test significance beyond a rule-of-thumb noise warning.
- Drill-down on a chart starts a new analysis based on what you clicked. It doesn't remember which earlier questions led there beyond the filters it carries.
- The PDF report uses built-in fonts and only covers Western European characters.
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
| Numbers read 1000x too small or large | A `1.200` or `1,234` column was read in the wrong style. See *European number formats* under [CSV handling](#csv-handling) |
| No auto-insights appear | Nothing in the data crossed the thresholds (for example fewer than 3 time periods), or the file has no date or numeric columns. Ask questions as usual |
| `... is an old-style Excel (.xls) file` | Open it in Excel, **Save As** `.xlsx` (or CSV), and load that |
| Excel dates show as numbers | The column probably has no date format in the workbook. Apply a date format in Excel and save, or convert the column to text dates |
| Formulas load as empty cells | The workbook was never calculated and saved by Excel. Open it in Excel, save it, and load it again |
| Outlier check says a column was *not tested* | Most of its values are identical, or there are fewer than 8 of them, so there is no spread to measure |
| Compare says it *couldn't work out the periods* | Name them (*"Q1 vs Q2"*, *"2026-02 vs 2026-03"*) or use a relative form (*"last 30 days vs previous 30"*). The data needs a date column |
| Forecast says there isn't enough history | It needs at least 6 periods. Ask for a finer period (*"by week"*) or load more data |
| Forecast shows a flat line | The trend wasn't clear enough to separate from noise, so it holds the recent level. This is deliberate |
| Some **Show as:** buttons are greyed out | That style doesn't suit this chart (for example Pie with more than 12 slices, negatives or several series) |
| `add column ...` says it doesn't know a word | The formula uses a name that isn't a numeric column. The message lists the numeric columns. Use `[Column name]` for names with spaces or brackets |
| A calculated column is mostly blank | Its inputs are blank in those rows, or the formula divides by zero. Clean the inputs first (for example treat `N/A` as empty) |
| A number column is read as text | One or a few cells like `N/A` or `-` spoil it. Open **Clean data...** and treat them as empty, or set **Read as** to a number |
| Dates come out as the wrong month | The file is month-first. In **Clean data...** set **Read as** to *Date (month first)* |
| **Stacked** is greyed out | The chart has one series, uses averages or other non-additive measures, or has negative values. Ask for two groupings, e.g. *"revenue by month and category"* |
| Join says *No rows match* | The key columns hold values written differently (`C-001` vs `C001`) or you picked the wrong columns. The message shows examples from each side. Fix the source data or choose other keys |
| Join warns that rows are repeated | The right table's key has duplicates. Totals of the left table's numbers would count rows more than once. Use a unique key or add another key column |
| The Join lists are missing a file | Only tables loaded in this session (the last 10) appear. Use **Add file or sheet...** in the dialog |
| **Save chart as PNG...** and **Copy chart** are greyed out | The current answer has no chart (for example a table of rows). Ask a question that produces a chart |
| `?` characters in the PDF | The text contains characters outside Western European encodings, which the built-in PDF fonts can't show |

---

## Code tour

Everything lives in `DataChat.java`, organised as nested classes:

| Class | Responsibility |
|---|---|
| `DataChat` | Swing UI: toolbar, chat transcript, tabs, drag and drop, background `SwingWorker` tasks |
| `DataTable`, `Column` | In-memory table, CSV parsing, type detection (`NUMBER` / `DATE` / `TEXT`), schema description for the model |
| `Calc` | Calculated columns: tokenizer, a small recursive-descent parser and per-row evaluation (no code execution), plus `parseDefinition` for finding "add column ..." in a sentence |
| `Clean` | Cleaning: type overrides (`Column.forceNumber`, `forceDate`), "treat as empty" values, duplicates, renames, dropped columns, and the change summary |
| `Join` | Joins: key normalisation, `suggest` (picks the likely key pair), `check` (match quality without building anything), `join` (inner, left or full) and the plain-English `notes`. The dialog and file/sheet adder are `DataChat.joinDialog` and `addTablesFromFile` |
| `Xlsx` | Dependency-free Excel reader: unzips the workbook, streams the XML, resolves shared strings, date and percent styles, and tidies the grid into rows for `DataTable` |
| `Plan`, `Filter` | The analysis plan, with JSON conversion |
| `Engine` | Executes a plan: filtering, grouping, aggregation, driver analysis, correlation, anomaly detection (`anomalies`, `periodAnomalies`, `robustScores`), period comparison (`compare`, `spanOf`), forecasting (`forecast`, `fitModel`, `chooseModel`), fact generation |
| `Result`, `ChartData` | Output of the engine: table rows, chart specs, plain-English facts |
| `LocalPlanner` | Offline natural-language to plan translation (including follow-ups that modify the previous plan, correlation detection, and `comparePeriods` for resolving period names), and offline answer text |
| `Drill` | Turns a click on a chart element into a follow-up `Plan` |
| `Claude` | Anthropic Messages API client plus the planner and explainer system prompts |
| `Json` | Minimal JSON reader and writer (no libraries) |
| `DarkTheme` | The dark colour theme (a small Metal theme); `DataChat.setDark` switches look and feel, recolours the chat transcript and redraws the charts |
| `ChartExport` | Save a chart as PNG (rendered at 2x) or copy it to the clipboard; used by the toolbar button and the chart's right-click menu |
| `ChartPanel` | Java2D chart rendering: bar, horizontal bar, line, pie, stacked and 100% stacked, scatter, forecast bands and dashed series, with tooltips and click handling. `DataChat.canShowAs` decides which **Show as:** buttons apply. Also used to render charts into the PDF |
| `Insights`, `Insight` | The on-load scan: biggest drop and rise, over-indexing segments, outliers, trend and missing data. Each insight carries a ready-made `Plan` |
| `Pdf`, `ReportBuilder` | Minimal PDF 1.4 writer (text, rules, JPEG images, Flate-compressed pages) and the report layout |
| `DemoData` | Synthetic transactions generator and example questions |

### Extending it

- **New aggregation:** add it to `Acc.get`, `Engine.aggOf` and `measureLabel`, and mention it in `Claude.PLAN_SYSTEM`.
- **New time bucket:** add a case in `Engine.bucketLabel` and the allowed set in `Engine.bucketOf`.
- **New chart type:** add a `ChartType`, a paint method in `ChartPanel`, and a rule in `Engine.chartType`.
- **New follow-up phrase:** add the cue to `LocalPlanner.STRONG` (or `WEAK` for very short questions) and handle what it changes in `LocalPlanner.followUp`.
- **New drill-down behaviour:** edit `Drill.plan`. Charts carry what a click means in `ChartData` (`basePlan`, `labelFilters`, `seriesFilters`, `periodX`).
- **Dark palette:** edit the colours in `DarkTheme` (whole UI), `ChartPanel.setDark` (charts) and `DataChat.renderChat` (chat text colours).
- **New formula function:** add the name to `Calc.FUNCS` and a case in `Calc.Parser.func`.
- **Default empty values for cleaning:** edit `Clean.DEFAULT_BLANKS`.
- **Join limits and matching:** `Join.MAX_OUT` caps result rows, `Join.norm` defines what counts as the same key value, and `Join.suggest` holds the scoring for suggested keys.
- **Forecast seasons and defaults:** edit `Engine.seasonLength` (minimum history per season) and `Engine.defaultHorizon`. The minimum history is the `6` in `Engine.forecast`.
- **Another switchable chart type:** add it to `SWITCH_TYPES` and a rule in `DataChat.canShowAs`.
- **Anomaly sensitivity:** change `Engine.ANOMALY_Z` (default 3.5; lower flags more) or `Engine.MIN_GROUP` / `MIN_PERIODS`.
- **Another file format:** produce a `List<String[]>` (header first) and pass it to `DataTable.fromRecords`, as `Xlsx` does, then add the extension to `loadFile` and the file chooser filter.
- **New insight:** add a method to `Insights` that builds an `Insight(headline, plan)` and call it from `Insights.generate`. The plan runs through the normal engine when the user clicks *explore*.
- **Report layout:** edit `ReportBuilder.build`; the `Pdf` helper offers `para`, `table`, `image`, `rule` and automatic page breaks.
- **Different model:** change the **Model** field or set `ANTHROPIC_MODEL`.
- **Your own demo data:** edit `DemoData.create()` and `DemoData.examplesFor()`.
