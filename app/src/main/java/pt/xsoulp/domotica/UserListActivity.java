package pt.xsoulp.domotica;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UserListActivity extends Activity {
    private static final String SETTINGS = "app_settings";
    private static final String SERVER_URL = "server_url";
    private static final String DEFAULT_SERVER_URL = "https://keys.lmpinto.pt";
    private static final List<String> WEEKDAYS = Arrays.asList(
            "monday", "tuesday", "wednesday", "thursday", "friday"
    );
    private static final List<String> ALL_DAYS = Arrays.asList(
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"
    );

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final List<ApiClient.User> users = new ArrayList<>();
    private LinearLayout usersContainer;
    private ProgressBar progressBar;
    private TextView messageText;
    private EditText searchInput;
    private SecretStore secretStore;
    private SharedPreferences settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_list);
        applySystemBarInsets();
        secretStore = new SecretStore(this);
        settings = getSharedPreferences(SETTINGS, MODE_PRIVATE);
        usersContainer = findViewById(R.id.usersContainer);
        progressBar = findViewById(R.id.progressBar);
        messageText = findViewById(R.id.messageText);
        searchInput = findViewById(R.id.searchInput);
        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        findViewById(R.id.newUserButton).setOnClickListener(view -> openUser(-1));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                renderUsers(value.toString());
            }
            @Override public void afterTextChanged(Editable value) { }
        });
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            android.graphics.Insets insets = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
            );
            view.setPadding(
                    dp(22) + insets.left,
                    dp(18) + insets.top,
                    dp(22) + insets.right,
                    dp(18) + insets.bottom
            );
            return windowInsets;
        });
        root.requestApplyInsets();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadUsers();
    }

    private void loadUsers() {
        if (!secretStore.hasBearer()) {
            showError("Bearer não configurado");
            return;
        }
        final String bearer;
        try {
            bearer = secretStore.getBearer();
        } catch (Exception error) {
            showError("Não foi possível ler o bearer");
            return;
        }
        progressBar.setVisibility(View.VISIBLE);
        messageText.setVisibility(View.GONE);
        String serverUrl = settings.getString(SERVER_URL, DEFAULT_SERVER_URL);
        networkExecutor.execute(() -> {
            ApiClient.UsersResult result = ApiClient.getUsers(serverUrl, bearer);
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                progressBar.setVisibility(View.GONE);
                if (!result.successful) {
                    showError(result.message);
                    return;
                }
                users.clear();
                users.addAll(result.users);
                renderUsers(searchInput.getText().toString());
            });
        });
    }

    private void renderUsers(String query) {
        if (usersContainer == null) {
            return;
        }
        usersContainer.removeAllViews();
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        for (ApiClient.User user : users) {
            if (!normalized.isEmpty() && !user.name.toLowerCase(Locale.ROOT).contains(normalized)) {
                continue;
            }
            usersContainer.addView(createUserCard(user));
        }
        if (usersContainer.getChildCount() == 0 && progressBar.getVisibility() != View.VISIBLE) {
            messageText.setText(normalized.isEmpty() ? "Sem utilizadores" : "Nenhum resultado");
            messageText.setTextColor(getColor(R.color.text_secondary));
            messageText.setVisibility(View.VISIBLE);
        } else {
            messageText.setVisibility(View.GONE);
        }
    }

    private View createUserCard(ApiClient.User user) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(15), dp(14), dp(15));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(10);
        card.setLayoutParams(cardParams);
        card.setBackground(roundRect(Color.WHITE, 18));
        card.setElevation(dp(1));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> openUser(user.id));

        TextView initials = new TextView(this);
        initials.setText(initials(user.name));
        initials.setTextSize(21);
        initials.setTextColor(getColor(R.color.text_primary));
        initials.setGravity(Gravity.CENTER);
        initials.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        initials.setBackground(roundRect(Color.rgb(231, 237, 244), 48));
        card.addView(initials, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        detailsParams.setMargins(dp(15), 0, dp(8), 0);
        card.addView(details, detailsParams);
        details.addView(text(user.name, 19, R.color.text_primary, true));
        details.addView(text("admin".equals(user.role) ? "Administrador" : "Utilizador", 14, R.color.text_secondary, false));
        if (!"admin".equals(user.role)) {
            details.addView(text(scheduleSummary(user.schedules), 13, R.color.text_secondary, false));
            details.addView(text(user.allowRemote ? "Remoto" : "Local apenas", 13, R.color.text_secondary, false));
        }

        TextView status = text(user.enabled ? "●  Ativo" : "●  Inativo", 14,
                user.enabled ? R.color.success : R.color.text_secondary, false);
        card.addView(status);
        TextView arrow = text("›", 32, R.color.text_primary, false);
        LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        arrowParams.setMargins(dp(9), 0, 0, 0);
        card.addView(arrow, arrowParams);
        return card;
    }

    private String scheduleSummary(List<ApiClient.Schedule> schedules) {
        if (schedules.isEmpty()) {
            return "Sem horário permitido";
        }
        List<String> parts = new ArrayList<>();
        for (ApiClient.Schedule schedule : schedules) {
            String days;
            if (schedule.allowedDays.equals(ALL_DAYS)) {
                days = "Todos os dias";
            } else if (schedule.allowedDays.equals(WEEKDAYS)) {
                days = "Seg–Sex";
            } else {
                List<String> labels = new ArrayList<>();
                for (String day : schedule.allowedDays) {
                    labels.add(dayLabel(day));
                }
                days = String.join(", ", labels);
            }
            parts.add(days + " " + schedule.startTime + "–" + schedule.endTime);
        }
        return String.join(" · ", parts);
    }

    private String dayLabel(String day) {
        switch (day) {
            case "monday": return "Seg";
            case "tuesday": return "Ter";
            case "wednesday": return "Qua";
            case "thursday": return "Qui";
            case "friday": return "Sex";
            case "saturday": return "Sáb";
            case "sunday": return "Dom";
            default: return day;
        }
    }

    private String initials(String name) {
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return "?";
        }
        String result = parts[0].substring(0, 1);
        if (parts.length > 1) {
            result += parts[parts.length - 1].substring(0, 1);
        }
        return result.toUpperCase(Locale.ROOT);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(getColor(color));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private void openUser(int userId) {
        Intent intent = new Intent(this, UserEditActivity.class);
        intent.putExtra(UserEditActivity.EXTRA_USER_ID, userId);
        startActivity(intent);
    }

    private void showError(String message) {
        progressBar.setVisibility(View.GONE);
        messageText.setText(message);
        messageText.setTextColor(getColor(R.color.danger));
        messageText.setVisibility(View.VISIBLE);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        networkExecutor.shutdownNow();
        super.onDestroy();
    }
}
