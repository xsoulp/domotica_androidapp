package pt.xsoulp.domotica;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UserEditActivity extends Activity {
    static final String EXTRA_USER_ID = "user_id";
    private static final String SETTINGS = "app_settings";
    private static final String SERVER_URL = "server_url";
    private static final String DEFAULT_SERVER_URL = "https://keys.lmpinto.pt";
    private static final String[] DAYS = {
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"
    };
    private static final String[] DAY_LABELS = {"Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom"};

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final List<ScheduleRow> scheduleRows = new ArrayList<>();
    private SecretStore secretStore;
    private SharedPreferences settings;
    private EditText nameInput;
    private TextView titleText;
    private TextView roleText;
    private TextView messageText;
    private Switch enabledSwitch;
    private Switch remoteSwitch;
    private LinearLayout periodsContainer;
    private View accessSection;
    private View scheduleSection;
    private Button deleteButton;
    private Button saveButton;
    private ProgressBar progressBar;
    private int userId;
    private String role = "user";

    private final class ScheduleRow {
        final LinearLayout root;
        final Button startButton;
        final Button endButton;
        final List<ToggleButton> dayButtons;

        ScheduleRow(LinearLayout root, Button startButton, Button endButton, List<ToggleButton> dayButtons) {
            this.root = root;
            this.startButton = startButton;
            this.endButton = endButton;
            this.dayButtons = dayButtons;
        }

        ApiClient.Schedule value() {
            List<String> selectedDays = new ArrayList<>();
            for (int index = 0; index < dayButtons.size(); index++) {
                if (dayButtons.get(index).isChecked()) {
                    selectedDays.add(DAYS[index]);
                }
            }
            return new ApiClient.Schedule(
                    selectedDays,
                    startButton.getText().toString(),
                    endButton.getText().toString()
            );
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_edit);
        applySystemBarInsets();
        secretStore = new SecretStore(this);
        settings = getSharedPreferences(SETTINGS, MODE_PRIVATE);
        userId = getIntent().getIntExtra(EXTRA_USER_ID, -1);
        nameInput = findViewById(R.id.nameInput);
        titleText = findViewById(R.id.titleText);
        roleText = findViewById(R.id.roleText);
        messageText = findViewById(R.id.messageText);
        enabledSwitch = findViewById(R.id.enabledSwitch);
        remoteSwitch = findViewById(R.id.remoteSwitch);
        periodsContainer = findViewById(R.id.periodsContainer);
        accessSection = findViewById(R.id.accessSection);
        scheduleSection = findViewById(R.id.scheduleSection);
        deleteButton = findViewById(R.id.deleteButton);
        saveButton = findViewById(R.id.saveButton);
        progressBar = findViewById(R.id.progressBar);

        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        findViewById(R.id.cancelButton).setOnClickListener(view -> finish());
        findViewById(R.id.addPeriodButton).setOnClickListener(view -> addScheduleRow(
                new ApiClient.Schedule(Arrays.asList(DAYS), "08:00", "20:00")
        ));
        saveButton.setOnClickListener(view -> save());
        deleteButton.setOnClickListener(view -> confirmDelete());

        if (userId < 0) {
            titleText.setText("Novo utilizador");
            deleteButton.setVisibility(View.GONE);
            enabledSwitch.setChecked(true);
            remoteSwitch.setChecked(false);
            addScheduleRow(new ApiClient.Schedule(Arrays.asList(DAYS), "00:00", "23:59"));
        } else {
            loadUser();
        }
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.rootScroll);
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            android.graphics.Insets insets = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
            );
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return windowInsets;
        });
        root.requestApplyInsets();
    }

    private void loadUser() {
        Credentials credentials = credentials();
        if (credentials == null) {
            return;
        }
        setBusy(true);
        networkExecutor.execute(() -> {
            ApiClient.UserResult result = ApiClient.getUser(
                    credentials.serverUrl,
                    credentials.bearer,
                    userId
            );
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                setBusy(false);
                if (!result.successful) {
                    showError(result.message);
                    return;
                }
                populate(result.user);
            });
        });
    }

    private void populate(ApiClient.User user) {
        role = user.role;
        nameInput.setText(user.name);
        enabledSwitch.setChecked(user.enabled);
        remoteSwitch.setChecked(user.allowRemote);
        boolean admin = "admin".equals(user.role);
        roleText.setText(admin ? "Administrador" : "Utilizador");
        accessSection.setVisibility(admin ? View.GONE : View.VISIBLE);
        scheduleSection.setVisibility(admin ? View.GONE : View.VISIBLE);
        periodsContainer.removeAllViews();
        scheduleRows.clear();
        for (ApiClient.Schedule schedule : user.schedules) {
            addScheduleRow(schedule);
        }
    }

    private void addScheduleRow(ApiClient.Schedule schedule) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams rootParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rootParams.bottomMargin = dp(10);
        root.setLayoutParams(rootParams);
        root.setBackground(roundRect(Color.rgb(247, 249, 252), 12));

        LinearLayout times = new LinearLayout(this);
        times.setGravity(Gravity.CENTER_VERTICAL);
        times.setOrientation(LinearLayout.HORIZONTAL);
        Button start = timeButton(schedule.startTime);
        Button end = timeButton(schedule.endTime);
        times.addView(start, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView separator = new TextView(this);
        separator.setText(" — ");
        separator.setTextSize(18);
        times.addView(separator);
        times.addView(end, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button remove = new Button(this);
        remove.setText("×");
        remove.setTextColor(getColor(R.color.danger));
        remove.setTextSize(22);
        remove.setBackgroundColor(Color.TRANSPARENT);
        times.addView(remove, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(times);

        HorizontalScrollView daysScroll = new HorizontalScrollView(this);
        daysScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout days = new LinearLayout(this);
        days.setOrientation(LinearLayout.HORIZONTAL);
        days.setPadding(0, dp(7), 0, 0);
        List<ToggleButton> buttons = new ArrayList<>();
        for (int index = 0; index < DAYS.length; index++) {
            ToggleButton button = new ToggleButton(this);
            button.setTextOn(DAY_LABELS[index]);
            button.setTextOff(DAY_LABELS[index]);
            button.setText(DAY_LABELS[index]);
            button.setTextSize(12);
            button.setAllCaps(false);
            button.setBackgroundResource(R.drawable.schedule_day_background);
            button.setBackgroundTintList(null);
            button.setTextColor(getColorStateList(R.color.schedule_day_text));
            button.setChecked(schedule.allowedDays.contains(DAYS[index]));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(54), dp(44));
            params.setMargins(0, 0, dp(4), 0);
            days.addView(button, params);
            buttons.add(button);
        }
        daysScroll.addView(days);
        root.addView(daysScroll);
        periodsContainer.addView(root);

        ScheduleRow row = new ScheduleRow(root, start, end, buttons);
        scheduleRows.add(row);
        remove.setOnClickListener(view -> {
            scheduleRows.remove(row);
            periodsContainer.removeView(root);
        });
    }

    private Button timeButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTextColor(getColor(R.color.text_primary));
        button.setBackgroundResource(R.drawable.input_background);
        button.setOnClickListener(view -> showTimePicker(button));
        return button;
    }

    private void showTimePicker(Button button) {
        String[] parts = button.getText().toString().split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        new TimePickerDialog(
                this,
                (picker, selectedHour, selectedMinute) -> button.setText(
                        String.format(Locale.ROOT, "%02d:%02d", selectedHour, selectedMinute)
                ),
                hour,
                minute,
                true
        ).show();
    }

    private void save() {
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) {
            nameInput.setError("Nome obrigatório");
            return;
        }
        List<ApiClient.Schedule> schedules = new ArrayList<>();
        for (ScheduleRow row : scheduleRows) {
            schedules.add(row.value());
        }
        ApiClient.User user = new ApiClient.User(
                userId,
                name,
                role,
                enabledSwitch.isChecked(),
                remoteSwitch.isChecked(),
                schedules
        );
        Credentials credentials = credentials();
        if (credentials == null) {
            return;
        }
        setBusy(true);
        networkExecutor.execute(() -> {
            ApiClient.UserResult result = userId < 0
                    ? ApiClient.createUser(credentials.serverUrl, credentials.bearer, user)
                    : ApiClient.updateUser(credentials.serverUrl, credentials.bearer, user);
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                setBusy(false);
                if (!result.successful) {
                    showError(result.message);
                } else if (userId < 0) {
                    showCreatedToken(result.createdToken);
                } else {
                    Toast.makeText(this, "Utilizador guardado", Toast.LENGTH_SHORT).show();
                    finish();
                }
            });
        });
    }

    private void showCreatedToken(String token) {
        if (token == null || token.isBlank()) {
            showError("Utilizador criado, mas o servidor não devolveu o bearer");
            return;
        }
        TextView tokenView = new TextView(this);
        tokenView.setText(token);
        tokenView.setTextIsSelectable(true);
        tokenView.setTypeface(Typeface.MONOSPACE);
        tokenView.setTextSize(14);
        tokenView.setPadding(dp(20), dp(16), dp(20), 0);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Bearer criado")
                .setMessage("Guarda este bearer agora. Não será possível consultá-lo novamente.")
                .setView(tokenView)
                .setNegativeButton("Copiar", null)
                .setPositiveButton("Concluído", (ignored, which) -> finish())
                .setCancelable(false)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setOnClickListener(view -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("Bearer", token));
                    Toast.makeText(this, "Bearer copiado", Toast.LENGTH_SHORT).show();
                }));
        dialog.show();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Eliminar utilizador?")
                .setMessage("O bearer deste utilizador deixará de funcionar imediatamente.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Eliminar", (dialog, which) -> deleteUser())
                .show();
    }

    private void deleteUser() {
        Credentials credentials = credentials();
        if (credentials == null) {
            return;
        }
        setBusy(true);
        networkExecutor.execute(() -> {
            ApiClient.Result result = ApiClient.deleteUser(
                    credentials.serverUrl,
                    credentials.bearer,
                    userId
            );
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                setBusy(false);
                if (!result.successful) {
                    showError(result.message);
                } else {
                    Toast.makeText(this, "Utilizador eliminado", Toast.LENGTH_SHORT).show();
                    finish();
                }
            });
        });
    }

    private Credentials credentials() {
        if (!secretStore.hasBearer()) {
            showError("Bearer não configurado");
            return null;
        }
        try {
            return new Credentials(
                    settings.getString(SERVER_URL, DEFAULT_SERVER_URL),
                    secretStore.getBearer()
            );
        } catch (Exception error) {
            showError("Não foi possível ler o bearer");
            return null;
        }
    }

    private void setBusy(boolean busy) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        saveButton.setEnabled(!busy);
        deleteButton.setEnabled(!busy);
    }

    private void showError(String message) {
        progressBar.setVisibility(View.GONE);
        messageText.setText(message);
        messageText.setVisibility(View.VISIBLE);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class Credentials {
        final String serverUrl;
        final String bearer;

        Credentials(String serverUrl, String bearer) {
            this.serverUrl = serverUrl;
            this.bearer = bearer;
        }
    }

    @Override
    protected void onDestroy() {
        networkExecutor.shutdownNow();
        super.onDestroy();
    }
}
