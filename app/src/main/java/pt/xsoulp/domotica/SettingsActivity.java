package pt.xsoulp.domotica;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.biometrics.BiometricManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public final class SettingsActivity extends Activity {
    private static final String SETTINGS = "app_settings";
    private static final String SERVER_URL = "server_url";
    private static final String DEFAULT_SERVER_URL = "https://keys.lmpinto.pt";
    private static final String LEGACY_SERVER_HOST = "192.168.1.112";

    private SecretStore secretStore;
    private SharedPreferences settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        applySystemBarInsets();

        secretStore = new SecretStore(this);
        settings = getSharedPreferences(SETTINGS, MODE_PRIVATE);
        EditText bearerInput = findViewById(R.id.bearerInput);
        EditText serverInput = findViewById(R.id.serverInput);
        String configuredServer = settings.getString(SERVER_URL, DEFAULT_SERVER_URL);
        if (configuredServer != null && configuredServer.contains(LEGACY_SERVER_HOST)) {
            configuredServer = DEFAULT_SERVER_URL;
            settings.edit().putString(SERVER_URL, configuredServer).apply();
        }
        serverInput.setText(configuredServer);
        if (secretStore.hasBearer()) {
            bearerInput.setHint("Bearer guardado — deixa vazio para manter");
        }

        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        findViewById(R.id.saveButton).setOnClickListener(view -> {
            String bearer = bearerInput.getText().toString().trim();
            String server = serverInput.getText().toString().trim();
            if (!secretStore.hasBearer() && bearer.isEmpty()) {
                bearerInput.setError("Bearer obrigatório");
                return;
            }
            if (!server.startsWith("http://") && !server.startsWith("https://")) {
                serverInput.setError("Usa http:// ou https://");
                return;
            }
            try {
                if (!bearer.isEmpty()) {
                    secretStore.saveBearer(bearer);
                }
                settings.edit().putString(SERVER_URL, server).apply();
                Toast.makeText(this, "Definições guardadas", Toast.LENGTH_SHORT).show();
                finish();
            } catch (Exception error) {
                Toast.makeText(this, "Não foi possível guardar o bearer", Toast.LENGTH_LONG).show();
            }
        });

        updateStatuses();
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

    private void updateStatuses() {
        TextView networkStatus = findViewById(R.id.networkStatus);
        boolean online = hasNetwork();
        networkStatus.setText(online ? "●  Online" : "●  Offline");
        networkStatus.setTextColor(getColor(online ? R.color.success : R.color.danger));

        TextView biometricStatus = findViewById(R.id.biometricStatus);
        BiometricManager manager = getSystemService(BiometricManager.class);
        boolean active = manager != null
                && manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == BiometricManager.BIOMETRIC_SUCCESS;
        biometricStatus.setText(active ? "●  Ativa" : "●  Indisponível");
        biometricStatus.setTextColor(getColor(active ? R.color.success : R.color.danger));
    }

    private boolean hasNetwork() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager == null) {
            return false;
        }
        Network network = manager.getActiveNetwork();
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null;
    }
}
