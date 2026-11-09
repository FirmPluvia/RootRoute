package route.root;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

public final class MainActivity extends Activity {
    private static final String CONTROLLER = "127.0.0.1:45894";
    private static final int MAX_CONFIG = 4 * 1024 * 1024;
    private static final String ROOT = "/data/adb/rootroute";
    private static final Set<String> CONFIG_KEYS = new HashSet<>(Arrays.asList(
        "proxies", "proxy-groups", "rules", "rule-providers", "proxy-providers", "sub-rules",
        "dns", "sniffer", "geodata-mode", "geodata-loader", "geo-auto-update", "ipv6",
        "unified-delay", "tcp-concurrent", "global-client-fingerprint", "global-ua"
    ));
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService appListWorker = Executors.newSingleThreadExecutor();
    private static final String[] TABS = {"仪表盘", "代理", "配置", "设置"};
    private final View[] pages = new View[TABS.length];
    private final Button[] tabs = new Button[TABS.length];
    private final ExecutorService trafficWorker = Executors.newSingleThreadExecutor();
    private final Handler trafficHandler = new Handler(Looper.getMainLooper());
    private Future<?> trafficTask;
    private boolean foregroundActivity;
    private int tabIndex;
    private volatile int trafficGeneration;
    private long routeStartedAt = -1;
    private TextView runDuration;
    private final Runnable durationTicker = new Runnable() {
        @Override public void run() {
            updateDuration();
            if (foregroundActivity && tabIndex == 0 && routeStartedAt >= 0) {
                trafficHandler.postDelayed(this, 1000);
            }
        }
    };
    private static final String[] MODE_LABELS = {"规则", "全局", "自定义"};
    private LinearLayout trafficCard;
    private TextView trafficNote;
    private Button outboundModeButton;
    private TextView uploadTotal;
    private TextView downloadTotal;
    private final Set<String> packages = new HashSet<>();
    private final List<Button> buttons = new ArrayList<>();
    private SharedPreferences prefs;
    private EditText nodeSearch;
    private LinearLayout subscriptionCards;
    private Button proxyToggle;
    private boolean routeRunning;
    private TextView status;
    private TextView routeState;
    private Switch tcpCompatibility;
    private TextView profileFeedback;
    private TextView nodeFeedback;
    private int subscriptionSort;
    private int nodeSort;
    private View navIndicator;
    private FrameLayout navCapsule;
    private HorizontalScrollView groupScroll;
    private LinearLayout groupChips;
    private ListView nodeList;
    private BaseAdapter nodeAdapter;
    private final List<String> nodeRows = new ArrayList<>();
    private final Map<String, JSONObject> groupDelays = new HashMap<>();
    private JSONObject proxyGroups = new JSONObject();
    private String proxyGroup = "";
    private String proxySession = "";
    private String selectedNode = "";
    private static boolean checkedOnColdStart;
    private boolean dark;
    private int surface;
    private int cardColor;
    private int fieldColor;
    private int foreground;
    private int muted;
    private int accent;
    private int tint;
    private Button selection;
    private boolean busy;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        packages.addAll(prefs.getStringSet("packages", Collections.emptySet()));
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
        surface = dark ? 0xff111216 : 0xffeeedf1;
        cardColor = dark ? 0xff202127 : 0xfffaf9fc;
        fieldColor = dark ? 0xff2b2e36 : 0xfff0f2f7;
        foreground = dark ? 0xfff4f5f8 : 0xff161820;
        muted = dark ? 0xffadb2c0 : 0xff60677a;
        accent = dark ? 0xff91b7ff : 0xff087cf0;
        tint = dark ? 0xff273653 : 0xffe9f0ff;

        nodeSort = prefs.getInt("nodeSort", 0);
        subscriptionSort = prefs.getInt("subscriptionSort", 0);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(surface);
        FrameLayout body = new FrameLayout(this);
        root.addView(body, new FrameLayout.LayoutParams(-1, -1));
        navCapsule = new FrameLayout(this);
        navCapsule.setBackground(rounded(cardColor, 34));
        navCapsule.setElevation(dp(8));
        FrameLayout.LayoutParams navLayout = new FrameLayout.LayoutParams(-1, dp(64), Gravity.BOTTOM);
        navLayout.setMargins(dp(20), 0, dp(20), dp(12));
        root.addView(navCapsule, navLayout);
        navIndicator = new View(this);
        navIndicator.setBackground(rounded(dark ? 0xff303441 : 0xffededf2, 28));
        FrameLayout.LayoutParams indicatorLayout = new FrameLayout.LayoutParams(0, -1, Gravity.TOP | Gravity.LEFT);
        indicatorLayout.setMargins(0, dp(5), 0, dp(5));
        navCapsule.addView(navIndicator, indicatorLayout);
        LinearLayout navigation = new LinearLayout(this);
        navigation.setPadding(dp(5), dp(4), dp(5), dp(4));
        navCapsule.addView(navigation, new FrameLayout.LayoutParams(-1, -1));
        navCapsule.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) positionNavIndicator(false);
        });
        LinearLayout[] panels = new LinearLayout[TABS.length];
        for (int i = 0; i < TABS.length; i++) {
            LinearLayout panel = column();
            panel.setPadding(dp(20), dp(24), dp(20), dp(16));
            panel.setFocusableInTouchMode(true);
            panels[i] = panel;
            View page;
            if (i == 1) {
                nodeList = new ListView(this);
                nodeList.setDivider(null);
                nodeList.setClipToPadding(false);
                nodeList.setPadding(dp(20), 0, dp(20), dp(12));
                panel.setPadding(0, dp(24), 0, dp(12));
                nodeList.addHeaderView(panel, null, false);
                page = nodeList;
            } else {
                ScrollView scroll = new ScrollView(this);
                scroll.setFillViewport(true);
                scroll.setClipToPadding(false);
                scroll.setVerticalScrollBarEnabled(false);
                scroll.addView(panel);
                page = scroll;
            }
            page.setId(0x51510100 + i);
            pages[i] = page;
            page.setPadding(i == 1 ? dp(20) : 0, 0, i == 1 ? dp(20) : 0, dp(88));
            body.addView(page, new FrameLayout.LayoutParams(-1, -1));
            TextView title = text(TABS[i], 34, foreground);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            if (i == 1 || i == 2) {
                LinearLayout toolbar = new LinearLayout(this);
                toolbar.setGravity(Gravity.CENTER_VERTICAL);
                toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
                if (i == 1) {
                    toolbarButton(toolbar, "search", "搜索节点", view -> toggleNodeSearch());
                    toolbarButton(toolbar, "bolt", "测试当前组时延", view -> testGroupDelay());
                    toolbarButton(toolbar, "more", "节点显示选项", view -> nodeOptions());
                } else {
                    toolbarButton(toolbar, "refresh", "更新全部订阅", view -> refreshSubscriptions());
                    toolbarButton(toolbar, "sort", "配置排序", view -> subscriptionSortOptions());
                    toolbarButton(toolbar, "add", "添加订阅", view -> editSubscription(null));
                }
                panel.addView(toolbar);
            } else {
                panel.addView(title);
            }
            space(panel, 18);

            final int index = i;
            Button tab = styledButton(TABS[i], false, view -> showTab(index));
            tab.setTextSize(13);
            tab.setPadding(dp(2), dp(6), dp(2), dp(6));
            tab.setCompoundDrawablePadding(dp(2));
            tab.setMinHeight(dp(52));
            navigation.addView(tab, new LinearLayout.LayoutParams(0, -1, 1f));
            tabs[i] = tab;
        }
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
                body.setPadding(bars.left, bars.top, bars.right, 0);
                navLayout.setMargins(dp(20) + bars.left, dp(4), dp(20) + bars.right, dp(12) + bars.bottom);
                navCapsule.setLayoutParams(navLayout);
                boolean keyboard = insets.isVisible(WindowInsets.Type.ime());
                navCapsule.setVisibility(keyboard ? View.GONE : View.VISIBLE);
                for (int i = 0; i < pages.length; i++) {
                    int horizontal = i == 1 ? dp(20) : 0;
                    int bottom = keyboard ? insets.getInsets(WindowInsets.Type.ime()).bottom : dp(88) + bars.bottom;
                    pages[i].setPadding(horizontal, 0, horizontal, bottom);
                }
            }
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();

        trafficCard = card(panels[0], "本次会话 · 代理流量");
        LinearLayout totals = new LinearLayout(this);
        trafficCard.addView(totals);
        uploadTotal = trafficTile(totals, "↑ 累计上传");
        downloadTotal = trafficTile(totals, "↓ 累计下载");
        trafficNote = feedback(trafficCard, "开启或重启代理后开始统计");
        updateTrafficCard();

        LinearLayout controls = card(panels[0], "代理状态");
        routeState = text("尚未检查", 14, accent);
        routeState.setBackground(rounded(tint, 16));
        routeState.setPadding(dp(12), dp(6), dp(12), dp(6));
        controls.addView(routeState, new LinearLayout.LayoutParams(-2, -2));
        space(controls, 14);
        proxyToggle = button(controls, "开启 Root 代理", true, v ->
            submitRoute(routeRunning ? this::stop : this::start));
        runDuration = text("", 14, muted);
        runDuration.setPadding(dp(2), dp(4), dp(2), 0);
        runDuration.setVisibility(View.GONE);
        controls.addView(runDuration);

        profileFeedback = feedback(panels[2], "");
        profileFeedback.setId(0x51510003);
        subscriptionCards = column();
        panels[2].addView(subscriptionCards);
        renderSubscriptions();

        groupScroll = new HorizontalScrollView(this);
        groupScroll.setHorizontalScrollBarEnabled(false);
        groupChips = new LinearLayout(this);
        groupScroll.addView(groupChips, new HorizontalScrollView.LayoutParams(-2, -2));
        panels[1].addView(groupScroll, new LinearLayout.LayoutParams(-1, -2));
        nodeSearch = searchField("搜索节点");
        nodeSearch.setVisibility(View.GONE);
        panels[1].addView(nodeSearch, new LinearLayout.LayoutParams(-1, -2));
        nodeSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { updateNodeRows(); }
            @Override public void afterTextChanged(Editable text) { }
        });
        nodeFeedback = feedback(panels[1], "启动代理后可查看并切换节点");
        nodeFeedback.setId(0x51510004);
        setupNodeList();

        LinearLayout routing = card(panels[0], "应用分流");
        routing.addView(text("只接管已选应用的流量", 14, muted));
        space(routing, 8);
        outboundModeButton = button(routing, "出站模式 · " + MODE_LABELS[outboundMode()], false, v -> chooseOutboundMode());
        selection = button(routing, "", false, v -> selectApps());
        updateSelection();
        tcpCompatibility = new Switch(this);
        tcpCompatibility.setText("IPv4 TCP 兼容模式");
        tcpCompatibility.setTextColor(foreground);
        tcpCompatibility.setMinHeight(dp(52));
        tcpCompatibility.setSwitchMinWidth(dp(48));
        tcpCompatibility.setSwitchPadding(dp(12));
        tcpCompatibility.setThumbTintList(toggleColors(0xffffffff, dark ? 0xffc5c8d0 : 0xffffffff));
        tcpCompatibility.setTrackTintList(toggleColors(accent, dark ? 0xff5d6270 : 0xffa5adbd));
        tcpCompatibility.setChecked(prefs.getBoolean("tcpCompatibility", true));
        tcpCompatibility.setOnCheckedChangeListener((view, enabled) ->
            prefs.edit().putBoolean("tcpCompatibility", enabled).apply());
        routing.addView(tcpCompatibility, new LinearLayout.LayoutParams(-1, -2));
        routing.addView(text("启用后重启代理生效。UDP 和 IPv6 继续使用 TPROXY。", 13, muted));

        LinearLayout statistics = card(panels[3], "流量统计");
        Switch showTraffic = settingsSwitch("显示会话流量统计", prefs.getBoolean("showTraffic", false));
        statistics.addView(showTraffic);
        statistics.addView(text("默认关闭。开启后仅统计经代理节点的流量，重启代理生效。", 13, muted));
        showTraffic.setOnCheckedChangeListener((view, enabled) -> {
            prefs.edit().putBoolean("showTraffic", enabled).apply();
            updateTrafficCard();
            updateTrafficMonitor();
        });

        LinearLayout logs = card(panels[3], "运行日志");
        Switch logging = settingsSwitch("显示运行日志", prefs.getBoolean("recordLogs", false));
        logs.addView(logging);
        LinearLayout logDetails = column();
        logs.addView(logDetails);
        logDetails.setVisibility(logging.isChecked() ? View.VISIBLE : View.GONE);
        logging.setOnCheckedChangeListener((view, enabled) -> {
            prefs.edit().putBoolean("recordLogs", enabled).apply();
            logDetails.setVisibility(enabled ? View.VISIBLE : View.GONE);
            if (enabled && !busy) submitRoute(this::status);
        });
        logDetails.addView(text("Mihomo 日志记录开关在重启代理后生效。", 13, muted));
        space(logDetails, 8);
        button(logDetails, "刷新状态与日志", false, v -> submitRoute(this::status));
        button(logDetails, "清除日志", false, v -> submit(status, "正在清除日志…", this::clearLogs, status::setText));
        button(logDetails, "检查流量路由", false,
            v -> submit(status, "正在读取路由和流量统计…", this::diagnose, status::setText));
        space(logDetails, 8);
        status = text("启动后的诊断信息会显示在这里。", 13, muted);
        status.setTextIsSelectable(true);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        status.setLineSpacing(dp(3), 1f);
        ScrollView logBox = new ScrollView(this);
        logBox.setId(0x51510005);
        logBox.setBackground(rounded(fieldColor, 14));
        logBox.setNestedScrollingEnabled(true);
        logBox.setFillViewport(true);
        logBox.addView(status, new ScrollView.LayoutParams(-1, -2));
        logDetails.addView(logBox, new LinearLayout.LayoutParams(-1, dp(200)));
        LinearLayout about = card(panels[3], "关于");
        about.addView(text("RootRoute · " + appVersion(), 16, foreground));
        space(about, 6);
        about.addView(text("Mihomo 1.19.31 · Android Root 透明代理", 14, muted));
        space(about, 10);
        about.addView(text("作者 · FirmPluvia", 15, foreground));
        space(about, 8);
        button(about, "项目 · FirmPluvia/RootRoute", false, v -> {
            Intent project = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/FirmPluvia/RootRoute"));
            try {
                startActivity(project);
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(this, "请在浏览器打开 github.com/FirmPluvia/RootRoute", Toast.LENGTH_SHORT).show();
            }
        });
        boolean coldStart = !checkedOnColdStart;
        showTab(saved == null ? 0 : Math.max(0, Math.min(TABS.length - 1, saved.getInt("tab"))));
        checkedOnColdStart = true;
        if (saved == null) panels[0].requestFocus();
        else {
            routeStartedAt = saved.getLong("routeStartedAt", -1);
            updateRouteButton(saved.getString("routeState", "尚未检查"));
            scheduleDuration();
            status.setText(saved.getString("status", "启动后的诊断信息会显示在这里。"));
        }
        if (coldStart) submitRoute(this::status);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("tab", tabIndex);
        state.putLong("routeStartedAt", routeStartedAt);
        state.putString("routeState", routeState.getText().toString());
        state.putString("status", status.getText().toString());
        super.onSaveInstanceState(state);
    }

    private void showTab(int index) {
        if (tabIndex == 1 && index != 1 && nodeSearch != null) hideKeyboard(nodeSearch);
        tabIndex = index;
        for (int i = 0; i < TABS.length; i++) {
            boolean selected = i == index;
            pages[i].setVisibility(selected ? View.VISIBLE : View.GONE);
            tabs[i].setSelected(selected);
            int color = selected ? accent : muted;
            tabs[i].setTextColor(color);
            tabs[i].setCompoundDrawables(null, icon(new String[] {"dashboard", "globe", "file", "tools"}[i], color, 23), null, null);
            tabs[i].setBackground(new RippleDrawable(ColorStateList.valueOf(0x24176bef),
                rounded(android.graphics.Color.TRANSPARENT, 24), null));
        }
        positionNavIndicator(true);
        updateTrafficMonitor();
        scheduleDuration();
        if (index == 1 && checkedOnColdStart && !busy) loadGroups();
    }

    private void positionNavIndicator(boolean animate) {
        int width = (navCapsule.getWidth() - dp(10)) / TABS.length;
        if (width <= 0) return;
        ViewGroup.LayoutParams params = navIndicator.getLayoutParams();
        if (params.width != width) {
            params.width = width;
            navIndicator.setLayoutParams(params);
        }
        int slot = navCapsule.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? TABS.length - 1 - tabIndex : tabIndex;
        float target = dp(5) + width * slot;
        navIndicator.animate().cancel();
        if (animate) navIndicator.animate().translationX(target).setDuration(180).start();
        else navIndicator.setTranslationX(target);
    }

    private TextView trafficTile(LinearLayout row, String label) {
        LinearLayout tile = column();
        tile.setPadding(dp(12), dp(10), dp(12), dp(10));
        tile.setBackground(rounded(fieldColor, 16));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
        if (row.getChildCount() > 0) params.setMarginStart(dp(8));
        row.addView(tile, params);
        tile.addView(text(label, 13, muted));
        TextView value = text("—", 21, foreground);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tile.addView(value);
        return value;
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            return "";
        }
    }

    @Override protected void onStart() {
        super.onStart();
        foregroundActivity = true;
        updateTrafficMonitor();
        scheduleDuration();
        if (!busy) {
            worker.execute(() -> {
                try {
                    String result = status();
                    runOnUiThread(() -> {
                        if (foregroundActivity && !busy && !isDestroyed()) displayRouteResult(result);
                    });
                } catch (Exception ignored) {
                    // A foreground recheck must not interrupt file import or request Root repeatedly.
                }
            });
        }
    }

    @Override protected void onStop() {
        foregroundActivity = false;
        updateTrafficMonitor();
        trafficHandler.removeCallbacks(durationTicker);
        super.onStop();
    }

    private Switch settingsSwitch(String label, boolean checked) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextColor(foreground);
        toggle.setMinHeight(dp(48));
        toggle.setThumbTintList(toggleColors(0xffffffff, dark ? 0xffc5c8d0 : 0xffffffff));
        toggle.setTrackTintList(toggleColors(accent, dark ? 0xff5d6270 : 0xffa5adbd));
        toggle.setChecked(checked);
        return toggle;
    }

    private void updateTrafficCard() {
        trafficCard.setVisibility(prefs.getBoolean("showTraffic", false) ? View.VISIBLE : View.GONE);
        trafficNote.setVisibility(prefs.getBoolean("sessionTraffic", false) ? View.GONE : View.VISIBLE);
    }

    private void updateTrafficMonitor() {
        boolean shouldMonitor = foregroundActivity && tabIndex == 0
            && prefs.getBoolean("showTraffic", false) && prefs.getBoolean("sessionTraffic", false);
        if (shouldMonitor == (trafficTask != null)) return;
        int generation = ++trafficGeneration;
        if (shouldMonitor) {
            trafficTask = trafficWorker.submit(() -> readTraffic(generation));
        } else {
            trafficTask.cancel(true);
            trafficTask = null;
            uploadTotal.setText("—");
            downloadTotal.setText("—");
        }
    }

    private void readTraffic(int generation) {
        String lastSession = null;
        while (generation == trafficGeneration && !Thread.currentThread().isInterrupted()) {
            String secret = prefs.getString("controllerSecret", "");
            if (!secret.equals(lastSession)) {
                lastSession = secret;
                publishTraffic(generation, secret, "—", "—");
            }
            if (!secret.isEmpty()) {
                HttpURLConnection conn = null;
                try {
                    conn = openController("GET", "/traffic", secret);
                    conn.setRequestProperty("Connection", "close");
                    conn.setReadTimeout(3000);
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line = in.readLine();
                        if (line == null) throw new IOException("Mihomo 未返回会话流量");
                        JSONObject data = new JSONObject(line);
                        publishTraffic(generation, secret, formatBytes(data.getLong("upTotal")), formatBytes(data.getLong("downTotal")));
                    }
                } catch (IOException | org.json.JSONException ignored) {
                    // Keep the last valid totals while the controller reconnects.
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
            try {
                Thread.sleep(secret.isEmpty() ? 900 : 500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void publishTraffic(int generation, String session, String up, String down) {
        trafficHandler.post(() -> {
            if (generation == trafficGeneration && foregroundActivity && tabIndex == 0
                    && session.equals(prefs.getString("controllerSecret", ""))) {
                uploadTotal.setText(up);
                downloadTotal.setText(down);
            }
        });
    }

    private void scheduleDuration() {
        trafficHandler.removeCallbacks(durationTicker);
        durationTicker.run();
    }

    private void updateDuration() {
        boolean running = routeStartedAt >= 0 && "运行中".contentEquals(routeState.getText());
        runDuration.setVisibility(running ? View.VISIBLE : View.GONE);
        if (!running) return;
        long seconds = Math.max(0, (SystemClock.elapsedRealtime() - routeStartedAt) / 1000);
        long hours = seconds / 3600;
        long minutes = (seconds / 60) % 60;
        String time = hours == 0 ? String.format(Locale.ROOT, "%02d:%02d", minutes, seconds % 60)
            : String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds % 60);
        runDuration.setText("已运行 " + time);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB", "EiB"};
        double value = bytes / 1024.0;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private int dp(int size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(String content, int size, int color) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private TextView feedback(LinearLayout panel, String content) {
        TextView message = text(content, 14, muted);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        message.setFreezesText(true);
        message.setPadding(dp(2), dp(4), dp(2), 0);
        panel.addView(message);
        return message;
    }

    private ColorStateList toggleColors(int checked, int unchecked) {
        return new ColorStateList(new int[][] {
            {-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}
        }, new int[] {dark ? 0xff474a55 : 0xffc4c9d3, checked, unchecked});
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private void space(LinearLayout panel, int height) {
        panel.addView(new Space(this), new LinearLayout.LayoutParams(1, dp(height)));
    }

    private LinearLayout card(LinearLayout panel, String label) {
        LinearLayout card = column();
        card.setBackground(rounded(cardColor, 24));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.bottomMargin = dp(14);
        panel.addView(card, layout);
        TextView heading = text(label, 17, foreground);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(heading);
        space(card, 10);
        return card;
    }

    private Button styledButton(String name, boolean primary, View.OnClickListener click) {
        Button button = new Button(this);
        button.setText(name);
        button.setTextSize(15);
        button.setTextColor(new ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[0]},
            new int[] {primary ? 0xffdce4f5 : muted, primary ? 0xffffffff : accent}));
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setStateListAnimator(null);
        button.setMinHeight(dp(48));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x30ffffff : 0x20176bef),
            rounded(primary ? 0xff175ee4 : tint, 16), null));
        button.setOnClickListener(click);
        return button;
    }

    private Button button(LinearLayout panel, String name, boolean primary, View.OnClickListener click) {
        Button button = styledButton(name, primary, click);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.bottomMargin = dp(6);
        panel.addView(button, layout);
        buttons.add(button);
        return button;
    }

    private final class Picker {
        final Dialog dialog = new Dialog(MainActivity.this);
        final LinearLayout panel = column();
        final ListView list = new ListView(MainActivity.this);
        final Set<Integer> disabledRows = new HashSet<>();
        final TextView emptyMessage;
        final boolean scrollableResults;
        final ArrayAdapter<String> adapter;

        Picker(String title, String subtitle, String[] rows, int choiceMode) {
            scrollableResults = rows != null && rows.length == 0;
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setCanceledOnTouchOutside(true);
            panel.setPadding(dp(20), dp(20), dp(20), dp(16));
            panel.setBackground(rounded(cardColor, 28));
            TextView heading = text(title, 22, foreground);
            heading.setMaxLines(2);
            heading.setEllipsize(TextUtils.TruncateAt.END);
            heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            panel.addView(heading);
            if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
            TextView description = text(subtitle, 14, muted);
            description.setPadding(0, dp(4), 0, dp(12));
            panel.addView(description);
            list.setChoiceMode(choiceMode);
            list.setBackground(rounded(fieldColor, 18));
            list.setClipToOutline(true);
            list.setPadding(dp(4), dp(4), dp(4), dp(4));
            list.setDivider(null);
            list.setSelector(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            adapter = new ArrayAdapter<String>(MainActivity.this, 0, new ArrayList<String>()) {
                @Override public boolean areAllItemsEnabled() { return false; }
                @Override public boolean isEnabled(int position) { return !disabledRows.contains(position); }

                @Override public View getView(int position, View recycled, ViewGroup parent) {
                    CheckedTextView row = (CheckedTextView) recycled;
                    if (row == null) {
                        row = new CheckedTextView(MainActivity.this);
                        row.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
                        row.setMinHeight(dp(52));
                        row.setPaddingRelative(dp(14), dp(9), dp(12), dp(9));
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        row.setTextColor(foreground);
                        row.setTextSize(15);
                        row.setMaxLines(3);
                        row.setEllipsize(TextUtils.TruncateAt.END);
                        StateListDrawable background = new StateListDrawable();
                        if (choiceMode == ListView.CHOICE_MODE_SINGLE) {
                            background.addState(new int[] {android.R.attr.state_checked}, rounded(tint, 13));
                        }
                        background.addState(new int[0], rounded(fieldColor, 13));
                        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24176bef), background, null));
                        row.setCheckMarkDrawable(new ChoiceMark(choiceMode));
                    }
                    String value = getItem(position);
                    int lineBreak = value.indexOf('\n');
                    SpannableString label = new SpannableString(value);
                    if (lineBreak >= 0 && lineBreak + 1 < value.length()) {
                        label.setSpan(new ForegroundColorSpan(muted), lineBreak + 1, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        label.setSpan(new RelativeSizeSpan(.82f), lineBreak + 1, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    row.setText(label);
                    row.setEnabled(isEnabled(position));
                    row.setTextColor(isEnabled(position) ? foreground : muted);
                    row.setChecked(list.isItemChecked(position));
                    return row;
                }
            };
            list.setAdapter(adapter);
            panel.addView(list, scrollableResults
                ? new LinearLayout.LayoutParams(-1, 0, 1f) : new LinearLayout.LayoutParams(-1, -2));
            emptyMessage = text("当前没有可选项目", 14, muted);
            emptyMessage.setPadding(dp(14), dp(16), dp(14), dp(16));
            emptyMessage.setBackground(rounded(fieldColor, 18));
            panel.addView(emptyMessage, new LinearLayout.LayoutParams(-1, -2));
            list.setEmptyView(emptyMessage);
            if (rows == null) {
                panel.removeView(list);
                panel.removeView(emptyMessage);
            } else {
                setRows(Arrays.asList(rows), Collections.emptySet());
            }
        }

        void setRows(List<String> rows, Set<Integer> disabled) {
            list.clearChoices();
            disabledRows.clear();
            disabledRows.addAll(disabled);
            adapter.setNotifyOnChange(false);
            adapter.clear();
            adapter.addAll(rows);
            adapter.notifyDataSetChanged();
            if (scrollableResults) {
                int screen = getResources().getConfiguration().screenHeightDp;
                int maxHeight = Math.max(64, Math.min(400, screen - 260));
                ViewGroup.LayoutParams params = list.getLayoutParams();
                params.height = dp(Math.min(maxHeight, rows.size() * 60 + 8));
                list.setLayoutParams(params);
            }
            list.setSelection(0);
        }

        LinearLayout actions(String cancel, String confirm, Runnable onConfirm) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            Button close = styledButton(cancel, false, view -> dialog.dismiss());
            row.addView(close, new LinearLayout.LayoutParams(0, -2, 1f));
            if (confirm != null) {
                Button save = styledButton(confirm, true, view -> {
                    onConfirm.run();
                    dialog.dismiss();
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
                params.setMarginStart(dp(8));
                row.addView(save, params);
            }
            return row;
        }

        void show() {
            dialog.setContentView(panel);
            Window window = dialog.getWindow();
            if (window == null) return;
            window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(.42f);
            dialog.show();
            int screenWidth = getResources().getConfiguration().screenWidthDp;
            window.setLayout(dp(Math.min(440, Math.max(240, screenWidth - 32))), -2);
        }
    }

    private final class ChoiceMark extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int mode;
        private boolean checked;

        ChoiceMark(int mode) { this.mode = mode; }

        @Override public int getIntrinsicWidth() { return dp(26); }
        @Override public int getIntrinsicHeight() { return dp(26); }
        @Override public boolean isStateful() { return true; }

        @Override protected boolean onStateChange(int[] states) {
            boolean next = false;
            for (int state : states) if (state == android.R.attr.state_checked) next = true;
            if (checked == next) return false;
            checked = next;
            invalidateSelf();
            return true;
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float x = bounds.exactCenterX();
            float y = bounds.exactCenterY();
            float scale = Math.min(bounds.width(), bounds.height()) / 26f;
            paint.setStrokeWidth(1.8f * scale);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(checked ? accent : muted);
            paint.setStyle(Paint.Style.STROKE);
            if (mode == ListView.CHOICE_MODE_NONE) {
                path.reset();
                path.moveTo(x - 2 * scale, y - 4 * scale);
                path.lineTo(x + 2 * scale, y);
                path.lineTo(x - 2 * scale, y + 4 * scale);
                if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
                    canvas.save();
                    canvas.scale(-1, 1, x, y);
                    canvas.drawPath(path, paint);
                    canvas.restore();
                } else {
                    canvas.drawPath(path, paint);
                }
            } else if (mode == ListView.CHOICE_MODE_SINGLE) {
                canvas.drawCircle(x, y, 8 * scale, paint);
                if (checked) {
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(x, y, 4 * scale, paint);
                }
            } else {
                if (checked) paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(x - 8 * scale, y - 8 * scale, x + 8 * scale, y + 8 * scale,
                    5 * scale, 5 * scale, paint);
                if (checked) {
                    paint.setColor(dark ? surface : 0xffffffff);
                    paint.setStyle(Paint.Style.STROKE);
                    path.reset();
                    path.moveTo(x - 4 * scale, y);
                    path.lineTo(x - scale, y + 3 * scale);
                    path.lineTo(x + 4 * scale, y - 3 * scale);
                    canvas.drawPath(path, paint);
                }
            }
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private int outboundMode() {
        return Math.max(0, Math.min(MODE_LABELS.length - 1, prefs.getInt("outboundMode", 0)));
    }

    private void chooseOutboundMode() {
        String[] options = {
            "规则\n按照配置规则选择出站",
            "全局\n被接管的连接使用 GLOBAL 代理组",
            "自定义\n默认按规则，长按应用可改为全局"
        };
        Picker picker = new Picker("出站模式", "重启代理后生效", options, ListView.CHOICE_MODE_SINGLE);
        picker.list.setItemChecked(outboundMode(), true);
        picker.list.setOnItemClickListener((parent, view, index, id) -> {
            prefs.edit().putInt("outboundMode", index).apply();
            outboundModeButton.setText("出站模式 · " + MODE_LABELS[index]);
            picker.dialog.dismiss();
        });
        space(picker.panel, 12);
        picker.panel.addView(picker.actions("关闭", null, null));
        picker.show();
    }

    private void updateSelection() {
        selection.setText("选择代理应用（" + packages.size() + "）");
    }

    private static final class AppOption {
        final String packageName;
        final String name;
        final String searchKey;
        final String row;
        final boolean system;
        final boolean eligible;

        AppOption(ApplicationInfo app, String name, boolean eligible) {
            packageName = app.packageName;
            this.name = name;
            this.eligible = eligible;
            system = (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            searchKey = (name + "\n" + packageName).toLowerCase(Locale.ROOT);
            row = name + "\n" + packageName + (eligible ? "" : " · 系统 UID");
        }
    }

    private List<AppOption> loadAppOptions() {
        PackageManager manager = getPackageManager();
        List<AppOption> result = new ArrayList<>();
        for (ApplicationInfo app : manager.getInstalledApplications(0)) {
            if (getPackageName().equals(app.packageName)) continue;
            String label = app.packageName;
            try {
                CharSequence value = app.loadLabel(manager);
                if (value != null) label = value.toString();
            } catch (RuntimeException ignored) {
                // Keep the app searchable by package name even if its label cannot be read.
            }
            result.add(new AppOption(app, label, canProxy(app)));
        }
        result.sort(Comparator.comparing(app -> app.name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private void selectApps() {
        boolean custom = outboundMode() == 2;
        List<AppOption> apps = new ArrayList<>();
        Set<String> pending = new HashSet<>(packages);
        Set<String> pendingGlobal = new HashSet<>(prefs.getStringSet("globalPackages", Collections.emptySet()));
        List<AppOption> visible = new ArrayList<>();
        Picker picker = new Picker("选择代理应用", custom
            ? "默认走规则，长按应用可切换为全局。保存后重启代理。"
            : "勾选后保存，重启代理生效", new String[0], ListView.CHOICE_MODE_MULTIPLE);
        LinearLayout filters = column();
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setHint("搜索应用名称或包名");
        search.setTextSize(15);
        search.setTextColor(foreground);
        search.setHintTextColor(muted);
        search.setMinHeight(dp(48));
        search.setPadding(dp(14), dp(8), dp(14), dp(8));
        search.setBackground(rounded(fieldColor, 14));
        filters.addView(search, new LinearLayout.LayoutParams(-1, -2));
        Switch showSystem = new Switch(this);
        showSystem.setText("显示系统应用");
        showSystem.setTextColor(foreground);
        showSystem.setMinHeight(dp(46));
        showSystem.setSwitchMinWidth(dp(48));
        showSystem.setThumbTintList(toggleColors(0xffffffff, dark ? 0xffc5c8d0 : 0xffffffff));
        showSystem.setTrackTintList(toggleColors(accent, dark ? 0xff5d6270 : 0xffa5adbd));
        showSystem.setChecked(prefs.getBoolean("showSystemApps", false));
        filters.addView(showSystem, new LinearLayout.LayoutParams(-1, -2));
        Button bulk = styledButton("全选", false, null);
        filters.addView(bulk, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams filterLayout = new LinearLayout.LayoutParams(-1, -2);
        filterLayout.bottomMargin = dp(8);
        picker.panel.addView(filters, 2, filterLayout);

        Runnable updateBulk = () -> {
            boolean any = visible.stream().anyMatch(app -> app.eligible);
            boolean all = any && visible.stream().filter(app -> app.eligible)
                .allMatch(app -> pending.contains(app.packageName));
            bulk.setEnabled(any);
            bulk.setText(all ? "取消全选" : "全选");
        };
        Runnable refresh = () -> {
            int first = picker.list.getFirstVisiblePosition();
            View firstRow = picker.list.getChildAt(0);
            int offset = firstRow == null ? picker.list.getPaddingTop() : firstRow.getTop();
            visible.clear();
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            List<AppOption> unselected = new ArrayList<>();
            for (AppOption app : apps) {
                if (!showSystem.isChecked() && app.system) continue;
                if (!query.isEmpty() && !app.searchKey.contains(query)) continue;
                if (app.eligible && pending.contains(app.packageName)) visible.add(app);
                else unselected.add(app);
            }
            visible.addAll(unselected);
            List<String> rows = new ArrayList<>(visible.size());
            Set<Integer> disabled = new HashSet<>();
            for (AppOption app : visible) {
                if (!app.eligible) disabled.add(rows.size());
                rows.add(app.row + (custom && pending.contains(app.packageName)
                    ? (pendingGlobal.contains(app.packageName) ? " · 全局" : " · 规则") : ""));
            }
            picker.setRows(rows, disabled);
            if (!rows.isEmpty()) picker.list.setSelectionFromTop(Math.min(first, rows.size() - 1), offset - picker.list.getPaddingTop());
            for (int i = 0; i < visible.size(); i++) {
                AppOption app = visible.get(i);
                if (app.eligible && pending.contains(app.packageName)) picker.list.setItemChecked(i, true);
            }
            updateBulk.run();
        };
        bulk.setOnClickListener(view -> {
            boolean select = visible.stream().filter(app -> app.eligible)
                .anyMatch(app -> !pending.contains(app.packageName));
            for (int i = 0; i < visible.size(); i++) {
                AppOption app = visible.get(i);
                if (!app.eligible) continue;
                if (select) pending.add(app.packageName);
                else {
                    pending.remove(app.packageName);
                    pendingGlobal.remove(app.packageName);
                }
            }
            refresh.run();
        });
        picker.list.setOnItemClickListener((parent, view, position, id) -> {
            String name = visible.get(position).packageName;
            if (picker.list.isItemChecked(position)) pending.add(name);
            else {
                pending.remove(name);
                pendingGlobal.remove(name);
            }
            refresh.run();
        });
        if (custom) {
            picker.list.setOnItemLongClickListener((parent, view, position, id) -> {
                AppOption app = visible.get(position);
                if (!app.eligible) return false;
                Picker mode = new Picker(app.name, "选择此应用的出站方式", new String[] {"规则", "全局"},
                    ListView.CHOICE_MODE_SINGLE);
                mode.list.setItemChecked(pendingGlobal.contains(app.packageName) ? 1 : 0, true);
                mode.list.setOnItemClickListener((p, item, index, itemId) -> {
                    pending.add(app.packageName);
                    if (index == 1) pendingGlobal.add(app.packageName);
                    else pendingGlobal.remove(app.packageName);
                    mode.dialog.dismiss();
                    refresh.run();
                });
                space(mode.panel, 12);
                mode.panel.addView(mode.actions("关闭", null, null));
                mode.show();
                return true;
            });
        }
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                refresh.run();
                picker.list.setSelectionFromTop(0, 0);
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        search.setOnEditorActionListener((view, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(search.getWindowToken(), 0);
            return true;
        });
        Window pickerWindow = picker.dialog.getWindow();
        if (pickerWindow != null) pickerWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        picker.panel.setFocusableInTouchMode(true);
        picker.panel.requestFocus();
        showSystem.setOnCheckedChangeListener((view, checked) -> {
            prefs.edit().putBoolean("showSystemApps", checked).apply();
            refresh.run();
        });
        search.setEnabled(false);
        showSystem.setEnabled(false);
        bulk.setEnabled(false);
        picker.emptyMessage.setText("正在读取应用列表…");
        space(picker.panel, 12);
        LinearLayout actions = picker.actions("取消", "保存", () -> {
            packages.clear();
            packages.addAll(pending);
            pendingGlobal.retainAll(pending);
            prefs.edit().putStringSet("packages", new HashSet<>(packages))
                .putStringSet("globalPackages", new HashSet<>(pendingGlobal)).apply();
            updateSelection();
        });
        View save = actions.getChildAt(1);
        save.setEnabled(false);
        picker.panel.addView(actions);
        picker.show();
        appListWorker.execute(() -> {
            try {
                List<AppOption> loaded = loadAppOptions();
                runOnUiThread(() -> {
                    if (isDestroyed() || !picker.dialog.isShowing()) return;
                    apps.addAll(loaded);
                    picker.emptyMessage.setText("没有符合条件的应用");
                    search.setEnabled(true);
                    showSystem.setEnabled(true);
                    save.setEnabled(true);
                    refresh.run();
                });
            } catch (RuntimeException e) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && picker.dialog.isShowing()) picker.emptyMessage.setText("无法读取应用列表，请重新打开");
                });
            }
        });
    }

    private boolean canProxy(ApplicationInfo app) {
        return app.uid >= 10000 && app.uid != Process.myUid();
    }

    private Drawable icon(String name, int color, int size) {
        int id = getResources().getIdentifier("rr_" + name, "drawable", getPackageName());
        Drawable drawable = getDrawable(id).mutate();
        drawable.setTint(color);
        drawable.setBounds(0, 0, dp(size), dp(size));
        return drawable;
    }

    private Button toolbarButton(LinearLayout row, String symbol, String description, View.OnClickListener click) {
        Button action = styledButton("", false, click);
        action.setContentDescription(description);
        action.setMinWidth(0);
        action.setMinimumWidth(0);
        action.setPadding(0, 0, 0, 0);
        action.setCompoundDrawables(null, icon(symbol, foreground, 22), null, null);
        action.setGravity(Gravity.CENTER);
        action.setPadding(0, dp(11), 0, 0);
        action.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24176bef), rounded(cardColor, 24), null));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(44), dp(44));
        params.setMarginStart(dp(4));
        row.addView(action, params);
        buttons.add(action);
        return action;
    }

    private EditText searchField(String hint) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(15);
        field.setTextColor(foreground);
        field.setHintTextColor(muted);
        field.setHint(hint);
        field.setPadding(dp(14), dp(10), dp(14), dp(10));
        field.setBackground(rounded(fieldColor, 16));
        return field;
    }

    private void hideKeyboard(View view) {
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(view.getWindowToken(), 0);
        view.clearFocus();
    }

    private void toggleNodeSearch() {
        if (nodeSearch.getVisibility() == View.VISIBLE) {
            hideKeyboard(nodeSearch);
            nodeSearch.setText("");
            nodeSearch.setVisibility(View.GONE);
        } else {
            nodeSearch.setVisibility(View.VISIBLE);
            nodeList.setSelection(0);
            nodeSearch.requestFocus();
            nodeSearch.post(() -> ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .showSoftInput(nodeSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT));
        }
    }

    private void setupNodeList() {
        nodeList.setItemsCanFocus(true);
        nodeAdapter = new BaseAdapter() {
            @Override public int getCount() { return (nodeRows.size() + 1) / 2; }
            @Override public Object getItem(int index) { return nodeRows.get(index * 2); }
            @Override public long getItemId(int index) { return index; }
            @Override public boolean isEnabled(int index) { return false; }

            @Override public View getView(int index, View recycled, ViewGroup parent) {
                LinearLayout row = (LinearLayout) recycled;
                if (row == null) {
                    row = new LinearLayout(MainActivity.this);
                    row.setPadding(0, 0, 0, dp(9));
                    for (int i = 0; i < 2; i++) {
                        LinearLayout tile = column();
                        tile.setPadding(dp(13), dp(11), dp(11), dp(7));
                        tile.setFocusable(true);
                        TextView title = text("", 16, foreground);
                        title.setMaxLines(2);
                        title.setEllipsize(TextUtils.TruncateAt.END);
                        tile.addView(title, new LinearLayout.LayoutParams(-1, 0, 1f));
                        TextView protocol = text("", 13, muted);
                        protocol.setSingleLine();
                        protocol.setEllipsize(TextUtils.TruncateAt.END);
                        tile.addView(protocol);
                        TextView delay = text("", 13, accent);
                        delay.setMinHeight(dp(26));
                        delay.setGravity(Gravity.CENTER_VERTICAL);
                        tile.addView(delay);
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(114), 1f);
                        if (i == 1) params.setMarginStart(dp(9));
                        row.addView(tile, params);
                    }
                }
                for (int i = 0; i < 2; i++) {
                    LinearLayout tile = (LinearLayout) row.getChildAt(i);
                    int member = index * 2 + i;
                    tile.setVisibility(member < nodeRows.size() ? View.VISIBLE : View.INVISIBLE);
                    if (member < nodeRows.size()) bindNodeTile(tile, nodeRows.get(member));
                }
                return row;
            }
        };
        nodeList.setAdapter(nodeAdapter);
    }

    private void bindNodeTile(LinearLayout tile, String name) {
        boolean selected = name.equals(selectedNode);
        tile.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24176bef), rounded(selected ? tint : cardColor, 22), null));
        TextView title = (TextView) tile.getChildAt(0);
        title.setText(name);
        title.setTextColor(selected ? accent : foreground);
        JSONObject entry = proxyGroups.optJSONObject(name);
        TextView protocol = (TextView) tile.getChildAt(1);
        String type = entry == null ? "" : entry.optString("type");
        String current = entry == null ? "" : entry.optString("now");
        protocol.setText(current.isEmpty() ? type : type + " · " + current);
        JSONObject delays = groupDelays.get(proxyGroup);
        int delay = delays == null ? 0 : delays.optInt(name, 0);
        TextView latency = (TextView) tile.getChildAt(2);
        latency.setText(delay > 0 ? delay + " ms" : "");
        latency.setTextColor(delay > 0 && delay < 300 ? (dark ? 0xff7fd59b : 0xff237a3b) : accent);
        latency.setCompoundDrawables(delay > 0 ? null : icon("bolt", accent, 14), null, null, null);
        latency.setContentDescription(delay > 0 ? name + " " + delay + " 毫秒，重新测试" : "测试 " + name + " 时延");
        latency.setOnClickListener(view -> testNodeDelay(name));
        tile.setSelected(selected);
        tile.setOnClickListener(view -> selectNode(name));
    }

    private void selectNode(String name) {
        if (busy || name.equals(selectedNode) || proxyGroup.isEmpty()) return;
        String group = proxyGroup;
        submit(nodeFeedback, "正在切换节点…", () -> {
            controller("PUT", "/proxies/" + Uri.encode(group), new JSONObject().put("name", name));
            return controller("GET", "/proxies/" + Uri.encode(group), null);
        }, result -> {
            try {
                proxyGroups.put(group, result);
                renderGroup(group);
            } catch (org.json.JSONException e) {
                nodeFeedback.setText("已切换，请刷新节点");
            }
        });
    }

    private String delayQuery() {
        return "?url=" + Uri.encode("https://www.gstatic.com/generate_204") + "&timeout=3500&expected=204";
    }

    private void testNodeDelay(String name) {
        if (busy || proxyGroup.isEmpty()) return;
        String group = proxyGroup;
        submit(nodeFeedback, "正在测试节点…",
            () -> controller("GET", "/proxies/" + Uri.encode(name) + "/delay" + delayQuery(), null).getInt("delay"),
            value -> {
                JSONObject delays = groupDelays.computeIfAbsent(group, ignored -> new JSONObject());
                try {
                    delays.put(name, value);
                } catch (org.json.JSONException ignored) {}
                updateNodeRows();
                nodeFeedback.setText(name + " · " + value + " ms");
            });
    }

    private List<String> manualGroups(JSONObject proxies) {
        List<String> groups = new ArrayList<>();
        Iterator<String> names = proxies.keys();
        while (names.hasNext()) {
            String name = names.next();
            JSONObject group = proxies.optJSONObject(name);
            if (group != null && (!"GLOBAL".equals(name) || prefs.getBoolean("sessionGlobal", false))
                    && "Selector".equals(group.optString("type"))) groups.add(name);
        }
        groups.sort(String.CASE_INSENSITIVE_ORDER);
        return groups;
    }

    private void loadGroups() {
        if (busy) return;
        String session = prefs.getString("controllerSecret", "");
        if (!session.equals(proxySession)) {
            proxySession = session;
            groupDelays.clear();
            proxyGroups = new JSONObject();
            renderGroup("");
        }
        if (session.isEmpty()) {
            nodeFeedback.setText("启动代理后可查看并切换节点");
            return;
        }
        submit(nodeFeedback, "正在读取节点…", () -> controller("GET", "/proxies", null).getJSONObject("proxies"), proxies -> {
            proxyGroups = proxies;
            List<String> groups = manualGroups(proxies);
            String preferred = prefs.getString("nodeGroup", "");
            renderGroup(groups.contains(preferred) ? preferred : groups.isEmpty() ? "" : groups.get(0));
        });
    }

    private void renderGroupChips() {
        groupChips.removeAllViews();
        for (String name : manualGroups(proxyGroups)) {
            boolean selected = name.equals(proxyGroup);
            Button chip = styledButton(name, selected, view -> {
                if (busy || name.equals(proxyGroup)) return;
                prefs.edit().putString("nodeGroup", name).apply();
                renderGroup(name);
                nodeList.setSelection(0);
            });
            chip.setTextSize(14);
            chip.setMinimumWidth(0);
            chip.setMinHeight(dp(40));
            chip.setPadding(dp(15), dp(5), dp(15), dp(5));
            chip.setSelected(selected);
            chip.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30176bef),
                rounded(selected ? 0xff087cf0 : cardColor, 22), null));
            if (!selected) chip.setTextColor(foreground);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.setMarginEnd(dp(8));
            groupChips.addView(chip, params);
            if (selected) chip.post(() -> groupScroll.smoothScrollTo(Math.max(0, chip.getLeft() - dp(8)), 0));
        }
        groupScroll.setVisibility(groupChips.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void nodeOptions() {
        String[] choices = {"默认顺序", "按名称排序", "按时延排序"};
        Picker picker = new Picker("代理显示选项", "选择节点排序方式", choices, ListView.CHOICE_MODE_SINGLE);
        if (nodeSort >= 0 && nodeSort < choices.length) picker.list.setItemChecked(nodeSort, true);
        picker.list.setOnItemClickListener((parent, view, index, id) -> {
            picker.dialog.dismiss();
            nodeSort = index;
            prefs.edit().putInt("nodeSort", nodeSort).apply();
            updateNodeRows();
        });
        space(picker.panel, 12);
        picker.panel.addView(picker.actions("关闭", null, null));
        picker.show();
    }

    private void renderGroup(String name) {
        proxyGroup = name;
        JSONObject group = proxyGroups.optJSONObject(name);
        selectedNode = group == null ? "" : group.optString("now");
        renderGroupChips();
        updateNodeRows();
    }

    private void updateNodeRows() {
        nodeRows.clear();
        JSONObject group = proxyGroups.optJSONObject(proxyGroup);
        JSONArray members = group == null ? null : group.optJSONArray("all");
        String query = nodeSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        for (int i = 0; members != null && i < members.length(); i++) {
            String name = members.optString(i);
            if (name.toLowerCase(Locale.ROOT).contains(query)) nodeRows.add(name);
        }
        if (nodeSort == 1) {
            nodeRows.sort(String.CASE_INSENSITIVE_ORDER);
        } else if (nodeSort == 2) {
            JSONObject delays = groupDelays.get(proxyGroup);
            nodeRows.sort(Comparator.comparingInt(name -> {
                int delay = delays == null ? 0 : delays.optInt(name, 0);
                return delay > 0 ? delay : Integer.MAX_VALUE;
            }));
        }
        nodeAdapter.notifyDataSetChanged();
        nodeFeedback.setText(group == null ? "暂无可手动切换的代理组"
            : nodeRows.isEmpty() ? "没有匹配的节点" : nodeRows.size() + " 个节点");
    }

    private void testGroupDelay() {
        if (proxyGroup.isEmpty()) {
            nodeFeedback.setText("请先启动代理并选择有节点的代理组");
            return;
        }
        String group = proxyGroup;
        submit(nodeFeedback, "正在测试当前组时延…",
            () -> controller("GET", "/group/" + Uri.encode(group) + "/delay" + delayQuery(), null),
            result -> {
                groupDelays.put(group, result);
                updateNodeRows();
                int successful = 0;
                Iterator<String> names = result.keys();
                while (names.hasNext()) if (result.optInt(names.next(), 0) > 0) successful++;
                nodeFeedback.setText("时延测试完成 · " + successful + " 个节点返回时延");
            });
    }

    private HttpURLConnection openController(String method, String path, String secret) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://" + CONTROLLER + path)
            .openConnection(java.net.Proxy.NO_PROXY);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod(method);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(path.startsWith("/group/") ? 12000 : 5000);
        conn.setRequestProperty("Authorization", "Bearer " + secret);
        return conn;
    }

    private JSONObject controller(String method, String path, JSONObject body) throws Exception {
        String secret = prefs.getString("controllerSecret", "");
        if (secret.isEmpty()) throw new IOException("请停止并重新启动 Root 代理后再选择节点");
        HttpURLConnection conn = openController(method, path, secret);
        try {
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = conn.getResponseCode();
            if (code == 401) throw new IOException("节点控制会话已失效，请重启 Root 代理");
            if (code == 204) return new JSONObject();
            if (code != 200) throw new IOException("核心返回 HTTP " + code + "，请刷新代理组后重试");
            try (InputStream in = conn.getInputStream()) {
                return new JSONObject(new String(read(in, MAX_CONFIG), StandardCharsets.UTF_8));
            }
        } catch (ConnectException e) {
            throw new IOException("无法连接 Mihomo，请启动或重启 Root 代理");
        } finally {
            conn.disconnect();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 1 && result == RESULT_OK && data != null && data.getData() != null) {
            submit(profileFeedback, "正在读取本地配置…", () -> {
                try (InputStream in = getContentResolver().openInputStream(data.getData())) {
                    if (in == null) throw new IOException("无法打开配置");
                    saveProfile(new File(getFilesDir(), "profile.yaml"), normalizeProfile(in));
                    prefs.edit().remove("activeSubscription").apply();
                    return "本地配置已保存，重启代理后生效。";
                }
            }, this::showSubscriptionResult);
        }
    }

    private List<String> subscriptions() {
        List<String> addresses = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(prefs.getString("subscriptions", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                String address = saved.optString(i);
                if (!address.isEmpty() && !addresses.contains(address)) addresses.add(address);
            }
        } catch (org.json.JSONException ignored) {}
        String legacy = prefs.getString("url", "");
        if (!legacy.isEmpty() && !addresses.contains(legacy)) addresses.add(legacy);
        return addresses;
    }

    private String subscriptionKey(String address) {
        return "subscription-" + UUID.nameUUIDFromBytes(address.getBytes(StandardCharsets.UTF_8));
    }

    private File subscriptionFile(String address) {
        return new File(getFilesDir(), subscriptionKey(address) + ".yaml");
    }

    private String subscriptionName(String address) {
        String host = Uri.parse(address).getHost();
        return prefs.getString(subscriptionKey(address) + ".name", host == null ? "订阅" : host);
    }

    private void showSubscriptionResult(String message) {
        profileFeedback.setText(message);
        renderSubscriptions();
    }

    private void renderSubscriptions() {
        subscriptionCards.removeAllViews();
        List<String> addresses = subscriptions();
        if (subscriptionSort == 1) addresses.sort(Comparator.comparing(this::subscriptionName, String.CASE_INSENSITIVE_ORDER));
        if (subscriptionSort == 2) addresses.sort(Comparator.comparingLong((String address) ->
            prefs.getLong(subscriptionKey(address) + ".updated", 0)).reversed());
        String active = prefs.getString("activeSubscription", "");
        if (addresses.isEmpty()) {
            LinearLayout empty = card(subscriptionCards, "暂无订阅");
            empty.addView(text("点按右上角添加 HTTPS 订阅", 14, muted));
            empty.addView(styledButton("导入本地 YAML", false, view -> importLocalProfile()));
            return;
        }
        for (String address : addresses) {
            boolean selected = address.equals(active);
            String key = subscriptionKey(address);
            LinearLayout row = new LinearLayout(this);
            row.setPadding(dp(16), dp(10), dp(10), dp(10));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24176bef), rounded(cardColor, 22), null));
            row.setSelected(selected);
            row.setOnClickListener(view -> activateSubscription(address));
            row.setOnLongClickListener(view -> {
                if (!busy) subscriptionActions(address);
                return true;
            });
            LinearLayout content = column();
            LinearLayout heading = new LinearLayout(this);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = text(subscriptionName(address), 17, foreground);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setSingleLine();
            name.setEllipsize(TextUtils.TruncateAt.END);
            heading.addView(name, new LinearLayout.LayoutParams(-2, -2, 1));
            long expiry = prefs.getLong(key + ".expiry", 0);
            if (expiry > 0) {
                String day = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(expiry));
                TextView badge = text(day, 12, accent);
                badge.setPadding(dp(7), dp(3), dp(7), dp(3));
                badge.setBackground(rounded(tint, 12));
                LinearLayout.LayoutParams badgeLayout = new LinearLayout.LayoutParams(-2, -2);
                badgeLayout.setMarginStart(dp(6));
                heading.addView(badge, badgeLayout);
            }
            content.addView(heading);
            long quota = prefs.getLong(key + ".quota", 0);
            String details = quota > 0 ? formatBytes(prefs.getLong(key + ".used", 0)) + " / " + formatBytes(quota)
                : subscriptionFile(address).isFile() ? "已缓存" : "待下载";
            TextView detail = text(details, 13, muted);
            detail.setPadding(0, dp(3), 0, 0);
            content.addView(detail);
            row.addView(content, new LinearLayout.LayoutParams(0, -2, 1f));
            long updated = prefs.getLong(key + ".updated", 0);
            if (updated > 0) {
                TextView age = text(android.text.format.DateUtils.getRelativeTimeSpanString(updated,
                    System.currentTimeMillis(), 60000).toString(), 12, muted);
                age.setPadding(dp(7), 0, dp(2), 0);
                row.addView(age);
            }
            if (selected) {
                ImageView mark = new ImageView(this);
                mark.setContentDescription("当前订阅");
                mark.setImageDrawable(icon("check", 0xffffffff, 18));
                mark.setPadding(dp(4), dp(4), dp(4), dp(4));
                mark.setBackground(rounded(accent, 14));
                row.addView(mark, new LinearLayout.LayoutParams(dp(26), dp(26)));
            }
            Button more = toolbarButton(row, "more", "配置操作", view -> subscriptionActions(address));
            // The card list is recreated after a change; detached row buttons are not global controls.
            buttons.remove(more);
            more.setOnClickListener(view -> { if (!busy) subscriptionActions(address); });
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
            layout.bottomMargin = dp(9);
            subscriptionCards.addView(row, layout);
        }
    }

    private void importLocalProfile() {
        if (busy) return;
        Intent file = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(file, 1);
    }

    private void editSubscription(String address) {
        boolean adding = address == null;
        Picker picker = new Picker(adding ? "添加订阅" : "修改订阅名称",
            adding ? "填写 HTTPS 订阅地址，名称可以稍后修改" : "名称仅用于在 RootRoute 中识别订阅",
            null, ListView.CHOICE_MODE_NONE);
        EditText name = searchField("订阅名称");
        name.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (!adding) name.setText(subscriptionName(address));
        picker.panel.addView(name);
        EditText link = searchField("HTTPS 订阅地址");
        link.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        if (adding) {
            space(picker.panel, 8);
            picker.panel.addView(link);
        }
        space(picker.panel, 12);
        LinearLayout actions = picker.actions("取消", adding ? "下载" : "保存", () -> {});
        Button confirm = (Button) actions.getChildAt(1);
        confirm.setOnClickListener(view -> {
            String label = name.getText().toString().trim();
            if (!adding) {
                if (label.isEmpty()) {
                    name.setError("请输入订阅名称");
                    return;
                }
                prefs.edit().putString(subscriptionKey(address) + ".name", label).apply();
                picker.dialog.dismiss();
                showSubscriptionResult("订阅名称已更新");
                return;
            }
            String value = link.getText().toString().trim();
            if (!"https".equalsIgnoreCase(Uri.parse(value).getScheme()) || Uri.parse(value).getHost() == null) {
                link.setError("请输入有效的 HTTPS 订阅地址");
                return;
            }
            picker.dialog.dismiss();
            submit(profileFeedback, "正在下载订阅…", () -> download(value, label), this::showSubscriptionResult);
        });
        picker.panel.addView(actions);
        if (adding) {
            Button local = styledButton("导入本地 YAML", false, view -> {
                picker.dialog.dismiss();
                importLocalProfile();
            });
            space(picker.panel, 8);
            picker.panel.addView(local, new LinearLayout.LayoutParams(-1, -2));
        }
        picker.show();
    }

    private void subscriptionSortOptions() {
        String[] choices = {"添加顺序", "按名称排序", "最近更新优先"};
        Picker picker = new Picker("配置排序", "选择配置卡片的排列方式", choices, ListView.CHOICE_MODE_SINGLE);
        if (subscriptionSort >= 0 && subscriptionSort < choices.length) picker.list.setItemChecked(subscriptionSort, true);
        picker.list.setOnItemClickListener((parent, view, index, id) -> {
            subscriptionSort = index;
            prefs.edit().putInt("subscriptionSort", index).apply();
            picker.dialog.dismiss();
            renderSubscriptions();
        });
        space(picker.panel, 12);
        picker.panel.addView(picker.actions("关闭", null, null));
        picker.show();
    }

    private void subscriptionActions(String address) {
        Picker picker = new Picker(subscriptionName(address), "配置操作",
            new String[] {"修改名称", "更新订阅"}, ListView.CHOICE_MODE_NONE);
        picker.list.setOnItemClickListener((parent, view, index, id) -> {
            picker.dialog.dismiss();
            if (index == 0) editSubscription(address);
            else submit(profileFeedback, "正在更新订阅…",
                () -> download(address, "", false), this::showSubscriptionResult);
        });
        space(picker.panel, 12);
        picker.panel.addView(picker.actions("关闭", null, null));
        picker.show();
    }

    private void refreshSubscriptions() {
        List<String> addresses = subscriptions();
        if (addresses.isEmpty()) {
            profileFeedback.setText("请先添加订阅");
            return;
        }
        submit(profileFeedback, "正在更新全部订阅…", () -> {
            int updated = 0;
            boolean activeUpdated = false;
            String active = prefs.getString("activeSubscription", "");
            for (String address : addresses) {
                try {
                    download(address, "", false);
                    updated++;
                    activeUpdated |= address.equals(active);
                } catch (IOException ignored) {
                    // A failed source should not prevent other configurations from updating.
                }
            }
            return "更新完成 · 成功 " + updated + "/" + addresses.size()
                + (activeUpdated ? " · 重启代理应用当前配置" : "");
        }, this::showSubscriptionResult);
    }

    private void activateSubscription(String address) {
        if (busy || address.equals(prefs.getString("activeSubscription", ""))) return;
        submit(profileFeedback, "正在切换订阅…", () -> {
            File cached = subscriptionFile(address);
            if (!cached.isFile()) return download(address, "");
            byte[] data;
            try (InputStream in = new FileInputStream(cached)) {
                data = read(in, MAX_CONFIG);
            }
            saveProfile(new File(getFilesDir(), "profile.yaml"), data);
            prefs.edit().putString("activeSubscription", address).apply();
            return "已切换订阅，重启代理后生效。";
        }, this::showSubscriptionResult);
    }

    private void storeSubscriptionMetadata(SharedPreferences.Editor editor, String key, String header) {
        editor.putLong(key + ".updated", System.currentTimeMillis());
        if (header == null) return;
        Map<String, Long> values = new HashMap<>();
        for (String part : header.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) continue;
            try {
                long value = Long.parseLong(pair[1].trim());
                if (value >= 0) values.put(pair[0].trim().toLowerCase(Locale.ROOT), value);
            } catch (NumberFormatException ignored) {}
        }
        long expiry = values.getOrDefault("expire", 0L);
        long upload = values.getOrDefault("upload", 0L);
        long download = values.getOrDefault("download", 0L);
        editor.putLong(key + ".quota", values.getOrDefault("total", 0L))
            .putLong(key + ".used", upload > Long.MAX_VALUE - download ? Long.MAX_VALUE : upload + download)
            .putLong(key + ".expiry", expiry <= 253402300799L ? expiry * 1000 : 0);
    }

    private String download(String address, String label) throws Exception {
        return download(address, label, true);
    }

    private String download(String address, String label, boolean activate) throws IOException {
        URL source;
        try {
            source = new URL(address);
        } catch (MalformedURLException e) {
            throw new IOException("请输入完整的 HTTPS 订阅地址");
        }
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!"https".equals(source.getProtocol())) throw new IOException("订阅需要 HTTPS");
            HttpURLConnection conn = (HttpURLConnection) source.openConnection(java.net.Proxy.NO_PROXY);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "mihomo/1.19.31 Clash.Meta");
            conn.setRequestProperty("Accept", "application/yaml, text/yaml, text/plain, */*");
            try {
                int code = conn.getResponseCode();
                if (code == 200) {
                    runOnUiThread(() -> profileFeedback.setText("已连接，正在下载并解析配置…"));
                    try (InputStream in = conn.getInputStream()) {
                        byte[] data = normalizeProfile(in);
                        saveProfile(subscriptionFile(address), data);
                        boolean affectsActive = activate || address.equals(prefs.getString("activeSubscription", ""));
                        if (affectsActive) saveProfile(new File(getFilesDir(), "profile.yaml"), data);
                        List<String> addresses = subscriptions();
                        if (!addresses.contains(address)) addresses.add(address);
                        SharedPreferences.Editor editor = prefs.edit()
                            .putString("subscriptions", new JSONArray(addresses).toString());
                        if (activate) editor.putString("url", address).putString("activeSubscription", address);
                        String key = subscriptionKey(address);
                        if (!label.isEmpty()) editor.putString(key + ".name", label);
                        storeSubscriptionMetadata(editor, key, conn.getHeaderField("Subscription-Userinfo"));
                        editor.apply();
                        return activate ? "订阅已保存并选中，重启代理后生效。"
                            : affectsActive ? "当前订阅已更新，重启代理后生效。" : "订阅已更新。";
                    }
                }
                if (Arrays.asList(301, 302, 303, 307, 308).contains(code) && conn.getHeaderField("Location") != null) {
                    source = new URL(source, conn.getHeaderField("Location"));
                } else {
                    throw new IOException("订阅服务器返回 HTTP " + code);
                }
            } catch (SocketTimeoutException e) {
                throw new IOException("连接或下载超时，请检查订阅服务器是否可访问");
            } catch (UnknownHostException e) {
                throw new IOException("订阅域名解析失败，请检查地址和网络");
            } catch (javax.net.ssl.SSLException e) {
                throw new IOException("无法建立 HTTPS 连接，请检查证书和设备时间");
            } catch (ConnectException e) {
                throw new IOException("无法连接订阅服务器，请检查网络");
            } finally {
                conn.disconnect();
            }
        }
        throw new IOException("订阅重定向次数过多");
    }

    private Yaml profileReader() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(40);
        options.setNestingDepthLimit(35);
        return new Yaml(new SafeConstructor(options));
    }

    private byte[] normalizeProfile(InputStream in) throws IOException {
        byte[] bytes = read(in, MAX_CONFIG);
        Object raw;
        try {
            raw = profileReader().load(new ByteArrayInputStream(bytes));
        } catch (RuntimeException e) {
            throw new IOException("YAML 格式无效，请检查订阅是否返回 Clash 配置");
        }
        if (!(raw instanceof Map)) throw new IOException("订阅内容不是 Clash / Mihomo YAML，请使用服务商的 Clash 订阅");
        Map<?, ?> input = (Map<?, ?>) raw;
        if (!input.containsKey("proxies") && !input.containsKey("proxy-providers") && !input.containsKey("rules")) {
            throw new IOException("配置中没有节点、代理提供者或路由规则");
        }
        Map<String, Object> config = new LinkedHashMap<>();
        for (String key : CONFIG_KEYS) if (input.containsKey(key)) config.put(key, input.get(key));
        normalizeProviders(config, "proxy-providers", "proxy");
        normalizeProviders(config, "rule-providers", "rule");
        if (config.get("dns") instanceof Map) {
            Map<?, ?> original = (Map<?, ?>) config.get("dns");
            Map<Object, Object> dns = new LinkedHashMap<>(original);
            dns.remove("listen");
            dns.put("enhanced-mode", "redir-host");
            config.put("dns", dns);
        }
        if (!config.containsKey("rules")) supplyDefaultRule(config);
        config.put("tproxy-port", 45893);
        config.put("allow-lan", true);
        config.put("bind-address", "*");
        config.put("ipv6", true);
        config.put("mode", "rule");
        config.put("log-level", "warning");
        byte[] data = new Yaml().dump(config).getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_CONFIG) throw new IOException("配置超过大小限制");
        return data;
    }

    private void saveProfile(File destination, byte[] data) throws IOException {
        android.util.AtomicFile file = new android.util.AtomicFile(destination);
        FileOutputStream out = file.startWrite();
        try {
            out.write(data);
            file.finishWrite(out);
        } catch (Exception e) {
            file.failWrite(out);
            throw new IOException("无法保存配置");
        }
    }

    private void supplyDefaultRule(Map<String, Object> config) {
        if (config.get("proxy-groups") instanceof List) {
            for (Object group : (List<?>) config.get("proxy-groups")) {
                if (group instanceof Map && ((Map<?, ?>) group).get("name") instanceof String) {
                    config.put("rules", Collections.singletonList("MATCH," + ((Map<?, ?>) group).get("name")));
                    return;
                }
            }
        }
        List<String> nodes = new ArrayList<>();
        if (config.get("proxies") instanceof List) {
            for (Object proxy : (List<?>) config.get("proxies")) {
                if (proxy instanceof Map && ((Map<?, ?>) proxy).get("name") instanceof String) {
                    nodes.add((String) ((Map<?, ?>) proxy).get("name"));
                }
            }
        }
        Object providerValue = config.get("proxy-providers");
        Map<?, ?> providers = providerValue instanceof Map ? (Map<?, ?>) providerValue : Collections.emptyMap();
        if (nodes.isEmpty() && providers.isEmpty()) return;
        String name = "RootRoute";
        while (nodes.contains(name)) name += "-组";
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("name", name);
        group.put("type", "select");
        if (!nodes.isEmpty()) group.put("proxies", nodes);
        if (!providers.isEmpty()) group.put("use", new ArrayList<>(providers.keySet()));
        config.put("proxy-groups", Collections.singletonList(group));
        config.put("rules", Collections.singletonList("MATCH," + name));
    }

    private void normalizeProviders(Map<String, Object> config, String key, String prefix) throws IOException {
        if (!config.containsKey(key)) return;
        if (!(config.get(key) instanceof Map)) throw new IOException("提供者配置格式无效");
        Map<?, ?> providers = (Map<?, ?>) config.get(key);
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : providers.entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof Map)) {
                throw new IOException("提供者配置格式无效");
            }
            Map<Object, Object> provider = new LinkedHashMap<>((Map<?, ?>) entry.getValue());
            if ("http".equals(provider.get("type"))) {
                Object value = provider.get("url");
                if (!(value instanceof String) || !"https".equals(new URL((String) value).getProtocol())) {
                    throw new IOException("远程提供者需要 HTTPS 地址");
                }
                String format = "mrs".equals(provider.get("format")) ? "mrs" : "yaml";
                String slot = UUID.nameUUIDFromBytes((entry.getKey() + "\n" + value).getBytes(StandardCharsets.UTF_8)).toString();
                provider.put("path", "./" + prefix + "-" + slot + "." + format);
            } else if ("inline".equals(provider.get("type"))) {
                provider.remove("path");
                provider.remove("url");
            } else {
                throw new IOException("首版支持 HTTPS 和 inline 提供者");
            }
            normalized.put((String) entry.getKey(), provider);
        }
        config.put(key, normalized);
    }

    private String start() throws Exception {
        if (!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")) throw new IOException("需要 ARM64 Android");
        if (!((android.os.UserManager) getSystemService(USER_SERVICE)).isSystemUser()) throw new IOException("请在设备主用户中使用");
        int mode = outboundMode();
        boolean countTraffic = prefs.getBoolean("showTraffic", false);
        Set<String> configuredGlobal = prefs.getStringSet("globalPackages", Collections.emptySet());
        TreeSet<Integer> uids = new TreeSet<>();
        Set<Integer> globalUids = new HashSet<>();
        for (String pkg : packages) {
            try {
                ApplicationInfo app = getPackageManager().getApplicationInfo(pkg, 0);
                if (canProxy(app)) {
                    uids.add(app.uid);
                    if (mode == 2 && configuredGlobal.contains(pkg)) globalUids.add(app.uid);
                }
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
        }
        if (uids.isEmpty()) throw new IOException("请先选择至少一个已安装应用");
        File profile = new File(getFilesDir(), "profile.yaml");
        if (!profile.isFile()) throw new IOException("请先导入 YAML 或下载订阅");

        File script = unpack("route.sh", "route.sh", false);
        File core = new File(getFilesDir(), "mihomo-rr026");
        if (!core.isFile()) core = unpack("mihomo.gz", "mihomo-rr026", true);
        boolean redirectTcp = prefs.getBoolean("tcpCompatibility", true);
        StringBuilder args = new StringBuilder(redirectTcp ? " --redir" : "");
        for (int uid : uids) {
            args.append(' ').append(uid);
            if (globalUids.contains(uid)) args.append(":g");
        }
        File runtimeProfile = File.createTempFile("runtime-", ".yaml", getCacheDir());
        String secret = UUID.randomUUID().toString();
        try {
            writeRuntimeProfile(profile, runtimeProfile, secret, redirectTcp, mode, !globalUids.isEmpty());
            String start = "set -eu; test \"$(id -u)\" = 0; umask 077; mkdir -p " + ROOT
                + "; cd " + ROOT + "; boot=$(cat /proc/sys/kernel/random/boot_id); "
                + "lock=session-$boot; if ! mkdir \"$lock\"; then echo '已有代理会话，请先停止'; exit 1; fi; "
                + "trap 'rm -rf \"$lock\"' EXIT; "
                + "cp " + quote(script.getAbsolutePath()) + " route.sh; "
                + "cp " + quote(core.getAbsolutePath()) + " mihomo; chmod 700 mihomo; "
                + "cp " + quote(runtimeProfile.getAbsolutePath()) + " config.yaml; "
                + "export ROOTROUTE_TRAFFIC=" + (countTraffic ? "proxy" : "off") + "; "
                + "nohup /system/bin/sh ./route.sh " + ROOT + " run" + args + " </dev/null > launch.log 2>&1 & "
                + "trap - EXIT";
            root(start);
            prefs.edit().putString("controllerSecret", secret).putBoolean("sessionTraffic", countTraffic)
                .putBoolean("sessionGlobal", mode == 1).commit();
        } finally {
            runtimeProfile.delete();
        }
        String result = "";
        for (int i = 0; i < 30; i++) {
            Thread.sleep(1000);
            result = status();
            if (result.startsWith("运行中")) return result;
            if (result.startsWith("已停止")) {
                return "启动失败" + result.substring("已停止".length());
            }
        }
        return result;
    }

    private void writeRuntimeProfile(File profile, File runtimeProfile, String secret,
            boolean redirectTcp, int mode, boolean perAppGlobal) throws IOException {
        Map<String, Object> config;
        try (InputStream in = new FileInputStream(profile)) {
            config = profileReader().load(in);
        }
        config.put("external-controller", CONTROLLER);
        config.put("secret", secret);
        config.put("profile", Collections.singletonMap("store-selected", true));
        config.put("log-level", prefs.getBoolean("recordLogs", false) ? "info" : "silent");
        config.put("mode", mode == 1 ? "global" : "rule");
        if (mode == 1) configureGlobalGroup(config, "GLOBAL");
        List<Map<String, Object>> listeners = new ArrayList<>();
        if (redirectTcp) listeners.add(runtimeListener("DEFAULT-TPROXY", "redir", "127.0.0.1", 45895, null));
        if (perAppGlobal) {
            String group = configureGlobalGroup(config, "RootRoute 全局");
            listeners.add(runtimeListener("ROOTROUTE-GLOBAL-TPROXY", "tproxy", "::", 45896, group));
            if (redirectTcp) listeners.add(runtimeListener("ROOTROUTE-GLOBAL-REDIR", "redir", "127.0.0.1", 45897, group));
        }
        if (!listeners.isEmpty()) config.put("listeners", listeners);
        if (!config.containsKey("sniffer")) {
            Map<String, Object> protocols = new LinkedHashMap<>();
            protocols.put("HTTP", Collections.singletonMap("ports", Arrays.asList(80, 8080)));
            protocols.put("TLS", Collections.singletonMap("ports", Arrays.asList(443, 8443)));
            protocols.put("QUIC", Collections.singletonMap("ports", Collections.singletonList(443)));
            Map<String, Object> sniffer = new LinkedHashMap<>();
            sniffer.put("enable", true);
            sniffer.put("parse-pure-ip", true);
            sniffer.put("override-destination", false);
            sniffer.put("sniff", protocols);
            config.put("sniffer", sniffer);
        }
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(runtimeProfile), StandardCharsets.UTF_8)) {
            new Yaml().dump(config, writer);
        }
    }

    private Map<String, Object> runtimeListener(String name, String type, String address, int port, String globalGroup) {
        Map<String, Object> listener = new LinkedHashMap<>();
        listener.put("name", name);
        listener.put("type", type);
        listener.put("listen", address);
        listener.put("port", port);
        if (globalGroup != null) listener.put("proxy", globalGroup);
        return listener;
    }

    private String configureGlobalGroup(Map<String, Object> config, String desiredName) throws IOException {
        Set<String> excluded = new HashSet<>(Arrays.asList("direct", "reject", "reject-drop", "rejectdrop",
            "compatible", "pass", "passrule", "dns"));
        List<String> nodes = new ArrayList<>();
        Set<String> takenNames = new HashSet<>();
        if (config.get("proxies") instanceof List) {
            for (Object item : (List<?>) config.get("proxies")) {
                if (!(item instanceof Map)) continue;
                Map<?, ?> proxy = (Map<?, ?>) item;
                Object name = proxy.get("name");
                Object type = proxy.get("type");
                if (name instanceof String) takenNames.add((String) name);
                if (name instanceof String && type instanceof String
                        && !excluded.contains(((String) type).toLowerCase(Locale.ROOT))) nodes.add((String) name);
            }
        }
        Map<?, ?> providers = config.get("proxy-providers") instanceof Map
            ? (Map<?, ?>) config.get("proxy-providers") : Collections.emptyMap();
        if (nodes.isEmpty() && providers.isEmpty()) throw new IOException("全局模式需要至少一个代理节点或代理提供者");
        List<Object> groups = new ArrayList<>();
        if (config.get("proxy-groups") instanceof List) {
            for (Object item : (List<?>) config.get("proxy-groups")) {
                Object name = item instanceof Map ? ((Map<?, ?>) item).get("name") : null;
                if ("GLOBAL".equals(desiredName) && "GLOBAL".equals(name)) continue;
                if (name instanceof String) takenNames.add((String) name);
                groups.add(item);
            }
        }
        String groupName = desiredName;
        if ("GLOBAL".equals(desiredName) && takenNames.contains(desiredName)) {
            throw new IOException("节点名称 GLOBAL 与全局代理组冲突");
        }
        while (takenNames.contains(groupName)) groupName += " +";
        Map<String, Object> global = new LinkedHashMap<>();
        global.put("name", groupName);
        global.put("type", "select");
        global.put("empty-fallback", "REJECT");
        global.put("exclude-type", "Direct|Reject|RejectDrop|Compatible|Pass|PassRule|Dns");
        if (!nodes.isEmpty()) global.put("proxies", nodes);
        if (!providers.isEmpty()) global.put("use", new ArrayList<>(providers.keySet()));
        groups.add(global);
        config.put("proxy-groups", groups);
        return groupName;
    }

    private String diagnose() throws Exception {
        String result = root("if [ ! -f " + ROOT + "/route.sh ]; then echo '请先启动代理'; "
            + "elif grep -q '^diagnose)' " + ROOT + "/route.sh; then /system/bin/sh " + ROOT
            + "/route.sh " + ROOT + " diagnose; else echo '请停止并重启代理以启用诊断'; fi");
        if (!result.startsWith("本次启动后的累计包数")) return result;
        StringBuilder report = new StringBuilder(result);
        try {
            JSONObject snapshot = controller("GET", "/connections", null);
            if (prefs.getBoolean("showTraffic", false) && prefs.getBoolean("sessionTraffic", false)) {
                report.append("\n代理流量 · 上传 ").append(snapshot.optLong("uploadTotal"))
                    .append(" B · 下载 ").append(snapshot.optLong("downloadTotal")).append(" B");
            }
            JSONArray connections = snapshot.optJSONArray("connections");
            Set<String> routes = new LinkedHashSet<>();
            for (int i = 0; connections != null && i < connections.length() && routes.size() < 6; i++) {
                JSONObject connection = connections.optJSONObject(i);
                if (connection == null) continue;
                JSONArray chain = connection.optJSONArray("chains");
                if (chain == null || chain.length() == 0) continue;
                routes.add(connection.optString("rule", "未知规则") + " → " + chain.optString(0));
            }
            if (routes.isEmpty()) report.append("\n当前没有活动连接，可在被选应用发起新连接后重试");
            else for (String route : routes) report.append("\n  ").append(route);
        } catch (Exception e) {
            report.append("\n无法读取 Mihomo 流量统计：").append(e.getMessage());
        }
        return report.toString();
    }

    private File unpack(String asset, String name, boolean gzip) throws IOException {
        android.util.AtomicFile file = new android.util.AtomicFile(new File(getFilesDir(), name));
        try (InputStream raw = getAssets().open(asset);
             InputStream in = gzip ? new GZIPInputStream(raw) : raw) {
            FileOutputStream out = file.startWrite();
            try {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                file.finishWrite(out);
            } catch (IOException e) {
                file.failWrite(out);
                throw e;
            }
        }
        return file.getBaseFile();
    }

    private String status() throws Exception {
        return root("if [ -f " + ROOT + "/route.sh ]; then /system/bin/sh " + ROOT
            + "/route.sh " + ROOT + " status || exit $?; "
            + "ready=" + ROOT + "/session-$(cat /proc/sys/kernel/random/boot_id)/ready; "
            + "if [ -f \"$ready\" ] && [ ! -s \"$ready\" ]; then "
            + "stamp=$(stat -c %Y \"$ready\" 2>/dev/null) && printf 'RR_STARTED_WALL=%s\\n' \"$stamp\" || :; fi; "
            + "else echo '已停止'; fi");
    }

    private String clearLogs() throws Exception {
        root("set -e; if [ -d " + ROOT + " ]; then cd " + ROOT + "; "
            + "for log in session.log launch.log; do if [ -f \"$log\" ]; then : > \"$log\"; fi; done; fi");
        return "日志已清除";
    }

    private String stop() throws Exception {
        root("if [ -f " + ROOT + "/route.sh ]; then /system/bin/sh " + ROOT
            + "/route.sh " + ROOT + " stop; fi");
        prefs.edit().remove("controllerSecret").remove("sessionTraffic").remove("sessionGlobal").apply();
        return status();
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private String root(String command) throws Exception {
        File output = File.createTempFile("root-", ".txt", getCacheDir());
        try {
            java.lang.Process child = new ProcessBuilder("su", "-c",
                "if [ \"$(id -u)\" != 0 ]; then echo '未获得 Root 权限'; exit 1; fi; " + command)
                .redirectErrorStream(true).redirectOutput(output).start();
            if (!child.waitFor(40, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                throw new IOException("Root 命令超时，请检查 Root 管理器授权");
            }
            String result;
            try (InputStream in = new FileInputStream(output)) {
                result = new String(read(in, 256 * 1024), StandardCharsets.UTF_8).trim();
            }
            if (child.exitValue() != 0) throw new IOException(result.isEmpty() ? "Root 命令失败" : result);
            return result;
        } finally {
            output.delete();
        }
    }

    private static byte[] read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int count;
        while ((count = in.read(buf)) != -1) {
            if (out.size() + count > limit) throw new IOException("输入内容超过大小限制");
            out.write(buf, 0, count);
        }
        return out.toByteArray();
    }

    private interface Task<T> { T run() throws Exception; }

    private void updateRouteButton(String state) {
        routeState.setText(state);
        routeRunning = "运行中".equals(state) || "启动或停止中".equals(state)
            || state.startsWith("守护进程已退出");
        proxyToggle.setText(routeRunning ? "关闭 Root 代理" : "开启 Root 代理");
    }

    private void submitRoute(Task<String> job) {
        submit(status, "处理中…", job, this::displayRouteResult);
    }

    private void displayRouteResult(String result) {
        String[] lines = result.split("\n", 2);
        String heading = lines[0];
        if (Arrays.asList("运行中", "已停止", "启动或停止中", "启动失败",
                "守护进程已退出，请点击停止清理路由").contains(heading)) {
            updateRouteButton(heading);
            boolean running = "运行中".equals(heading);
            if (!running) routeStartedAt = -1;
            if ("已停止".equals(heading) || "启动失败".equals(heading)) {
                prefs.edit().remove("sessionTraffic").remove("sessionGlobal").apply();
            }
            updateTrafficCard();
            updateTrafficMonitor();
            List<String> logLines = new ArrayList<>();
            if (lines.length == 2) {
                for (String line : lines[1].split("\n")) {
                    if (line.startsWith("RR_STARTED_ELAPSED=") || line.startsWith("RR_STARTED_WALL=")) {
                        if (running) readRouteStart(line);
                    } else {
                        logLines.add(line);
                    }
                }
            }
            scheduleDuration();
            status.setText(logLines.isEmpty() ? "暂无运行日志" : String.join("\n", logLines));
            if (tabIndex == 1) loadGroups();
        } else {
            status.setText(result);
        }
    }

    private void readRouteStart(String marker) {
        try {
            long seconds = Long.parseLong(marker.substring(marker.indexOf('=') + 1));
            long now = SystemClock.elapsedRealtime();
            if (seconds < 0 || seconds > Long.MAX_VALUE / 1000) return;
            long candidate = seconds * 1000;
            if (marker.startsWith("RR_STARTED_WALL=")) {
                long age = Math.max(0, System.currentTimeMillis() - candidate);
                candidate = now - age;
            }
            if (candidate >= 0 && candidate <= now) routeStartedAt = candidate;
        } catch (NumberFormatException ignored) {}
    }

    private <T> void submit(TextView feedback, String progress, Task<T> task, Consumer<T> onResult) {
        if (busy) return;
        busy = true;
        buttons.forEach(b -> b.setEnabled(false));
        tcpCompatibility.setEnabled(false);
        feedback.setText(progress);
        worker.execute(() -> {
            try {
                T result = task.run();
                runOnUiThread(() -> complete(() -> onResult.accept(result)));
            } catch (Exception e) {
                String message = e.getMessage();
                runOnUiThread(() -> complete(() -> feedback.setText("失败 · "
                    + (message == null ? e.getClass().getSimpleName() : message))));
            }
        });
    }

    private void complete(Runnable result) {
        if (isFinishing() || isDestroyed()) return;
        busy = false;
        buttons.forEach(b -> b.setEnabled(true));
        tcpCompatibility.setEnabled(true);
        result.run();
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        appListWorker.shutdownNow();
        trafficWorker.shutdownNow();
        trafficHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
