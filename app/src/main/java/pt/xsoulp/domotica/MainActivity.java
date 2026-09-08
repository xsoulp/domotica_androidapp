package pt.xsoulp.domotica;

import android.Manifest;
import android.app.Activity;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int LOCATION_PERMISSION_REQUEST = 10;
    private static final long MAX_CACHED_LOCATION_AGE_NS = 20_000_000_000L;
    private static final float MAX_CACHED_LOCATION_ACCURACY_M = 100.0f;
    private static final String SETTINGS = "app_settings";
    private static final String SERVER_URL = "server_url";
    private static final String DEFAULT_SERVER_URL = "https://keys.lmpinto.pt";
    private static final String LEGACY_SERVER_HOST = "192.168.1.112";

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SharedPreferences settings;
    private SecretStore secretStore;
    private TextView statusText;
    private TextView connectivityText;
    private TextView residenceStatus;
    private TextView aptActionTitle;
    private TextView aptActionState;
    private TextView bldActionTitle;
    private TextView bldActionState;
    private ProgressBar progressBar;
    private View openApartmentButton;
    private View openBuildingButton;
    private View lockButton;
    private View unlockButton;
    private CancellationSignal locationCancellation;
    private boolean aptCanOpen;
    private boolean bldCanOpen;
    private int capabilitiesGeneration;

    private interface LocationCallback {
        void onLocation(Location location);

        void onError(String message);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applySystemBarInsets();

        settings = getSharedPreferences(SETTINGS, MODE_PRIVATE);
        String configuredServer = settings.getString(SERVER_URL, DEFAULT_SERVER_URL);
        if (configuredServer != null && configuredServer.contains(LEGACY_SERVER_HOST)) {
            settings.edit().putString(SERVER_URL, DEFAULT_SERVER_URL).apply();
        }
        secretStore = new SecretStore(this);
        statusText = findViewById(R.id.statusText);
        connectivityText = findViewById(R.id.connectivityText);
        residenceStatus = findViewById(R.id.residenceStatus);
        aptActionTitle = findViewById(R.id.aptActionTitle);
        aptActionState = findViewById(R.id.aptActionState);
        bldActionTitle = findViewById(R.id.bldActionTitle);
        bldActionState = findViewById(R.id.bldActionState);
        progressBar = findViewById(R.id.progressBar);

        openApartmentButton = findViewById(R.id.openApartmentButton);
        openBuildingButton = findViewById(R.id.openBuildingButton);
        unlockButton = findViewById(R.id.unlockButton);
        lockButton = findViewById(R.id.lockButton);
        openApartmentButton.setOnClickListener(view -> beginOperation("/apt_door/open", "Abrir apartamento"));
        openBuildingButton.setOnClickListener(view -> beginOperation("/bld_door", "Abrir prédio"));
        unlockButton.setOnClickListener(view -> beginOperation("/apt_door/unlock", "Destrancar"));
        lockButton.setOnClickListener(view -> beginOperation("/apt_door/lock", "Trancar"));
        findViewById(R.id.settingsButton).setOnClickListener(view -> showSettings());

        disableOpening("A verificar…");
        requestLocationPermissionIfNeeded();
        if (!secretStore.hasBearer()) {
            statusText.setText("Configura o bearer nas Definições");
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

    private void beginOperation(String path, String title) {
        if (!secretStore.hasBearer()) {
            showSettings();
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    LOCATION_PERMISSION_REQUEST
            );
            setStatus("É necessário permitir a localização", true);
            return;
        }
        if (!hasNetwork()) {
            setStatus("Sem ligação de rede", true);
            return;
        }

        BiometricManager manager = getSystemService(BiometricManager.class);
        if (manager == null || manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                != BiometricManager.BIOMETRIC_SUCCESS) {
            setStatus("Impressão digital indisponível ou não configurada", true);
            return;
        }

        BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                .setTitle(title)
                .setSubtitle("Confirma com a impressão digital")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Cancelar", getMainExecutor(), (dialog, which) -> {
                })
                .build();

        prompt.authenticate(
                new CancellationSignal(),
                getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        obtainLocationAndSend(path);
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence message) {
                        if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                            setStatus(message.toString(), true);
                        }
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        setStatus("Impressão digital não reconhecida", true);
                    }
                }
        );
    }

    private void obtainLocationAndSend(String path) {
        setBusy(true, "A obter localização precisa…");
        obtainCurrentLocation(new LocationCallback() {
            @Override
            public void onLocation(Location location) {
                send(path, location);
            }

            @Override
            public void onError(String message) {
                setBusy(false, message);
                statusText.setTextColor(getColor(R.color.danger));
            }
        });
    }

    private void obtainCurrentLocation(LocationCallback callback) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            callback.onError("Permissão de localização em falta");
            return;
        }

        LocationManager manager = getSystemService(LocationManager.class);
        if (manager == null || !manager.isLocationEnabled()) {
            callback.onError("Ativa a localização");
            return;
        }

        Location cachedLocation = findRecentLocation(manager);
        if (cachedLocation != null) {
            callback.onLocation(cachedLocation);
            return;
        }

        String provider;
        if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            provider = LocationManager.NETWORK_PROVIDER;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && manager.hasProvider(LocationManager.FUSED_PROVIDER)
                && manager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
            provider = LocationManager.FUSED_PROVIDER;
        } else if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            provider = LocationManager.GPS_PROVIDER;
        } else {
            callback.onError("Nenhum serviço de localização disponível");
            return;
        }

        CancellationSignal cancellation = new CancellationSignal();
        locationCancellation = cancellation;
        Runnable timeout = () -> {
            if (!cancellation.isCanceled()) {
                cancellation.cancel();
                if (locationCancellation == cancellation) {
                    locationCancellation = null;
                }
                callback.onError("Não foi possível obter a localização");
            }
        };
        mainHandler.postDelayed(timeout, 15_000);

        manager.getCurrentLocation(
                provider,
                cancellation,
                getMainExecutor(),
                location -> {
                    mainHandler.removeCallbacks(timeout);
                    if (locationCancellation == cancellation) {
                        locationCancellation = null;
                    }
                    if (location == null) {
                        callback.onError("Localização indisponível");
                    } else {
                        callback.onLocation(location);
                    }
                }
        );
    }

    private Location findRecentLocation(LocationManager manager) {
        Location best = null;
        String[] providers = {
                LocationManager.FUSED_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER
        };
        long now = SystemClock.elapsedRealtimeNanos();
        for (String provider : providers) {
            try {
                Location location = manager.getLastKnownLocation(provider);
                if (location == null || !location.hasAccuracy()
                        || location.getAccuracy() > MAX_CACHED_LOCATION_ACCURACY_M
                        || now - location.getElapsedRealtimeNanos() > MAX_CACHED_LOCATION_AGE_NS) {
                    continue;
                }
                if (best == null
                        || location.getElapsedRealtimeNanos() > best.getElapsedRealtimeNanos()) {
                    best = location;
                }
            } catch (IllegalArgumentException | SecurityException ignored) {
                // Provider is unavailable or location permission was revoked.
            }
        }
        return best;
    }

    private void refreshCapabilities() {
        int generation = ++capabilitiesGeneration;
        aptCanOpen = false;
        bldCanOpen = false;
        disableOpening("A verificar…");

        if (!secretStore.hasBearer()) {
            capabilitiesFailed("Configura o bearer nas Definições");
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            capabilitiesFailed("Permissão de localização em falta");
            return;
        }
        if (!hasNetwork()) {
            capabilitiesFailed("Sem ligação de rede");
            return;
        }

        final String bearer;
        try {
            bearer = secretStore.getBearer();
        } catch (Exception error) {
            capabilitiesFailed("Não foi possível ler o bearer");
            return;
        }

        if (locationCancellation != null) {
            locationCancellation.cancel();
            locationCancellation = null;
        }
        progressBar.setVisibility(View.VISIBLE);
        setStatus("A verificar acessos…", false);
        obtainCurrentLocation(new LocationCallback() {
            @Override
            public void onLocation(Location location) {
                if (generation != capabilitiesGeneration || isDestroyed()) {
                    return;
                }
                String serverUrl = settings.getString(SERVER_URL, DEFAULT_SERVER_URL);
                networkExecutor.execute(() -> {
                    ApiClient.CapabilitiesResult result = ApiClient.getCapabilities(
                            serverUrl,
                            bearer,
                            location
                    );
                    runOnUiThread(() -> {
                        if (generation != capabilitiesGeneration || isDestroyed()) {
                            return;
                        }
                        progressBar.setVisibility(View.GONE);
                        if (!result.successful) {
                            capabilitiesFailed(result.message);
                            return;
                        }
                        applyDoorCapability(
                                "APT",
                                openApartmentButton,
                                aptActionTitle,
                                aptActionState,
                                result.apartment
                        );
                        applyDoorCapability(
                                "BLD",
                                openBuildingButton,
                                bldActionTitle,
                                bldActionState,
                                result.building
                        );
                        aptCanOpen = result.apartment.canOpen;
                        bldCanOpen = result.building.canOpen;
                        setStatus("Acessos atualizados", false);
                    });
                });
            }

            @Override
            public void onError(String message) {
                if (generation == capabilitiesGeneration) {
                    capabilitiesFailed(message);
                }
            }
        });
    }

    private void applyDoorCapability(
            String doorName,
            View button,
            TextView title,
            TextView state,
            ApiClient.DoorCapability capability
    ) {
        String distance = formatDistance(capability.distanceM);
        if (!capability.canOpen) {
            title.setText(doorName + " indisponível");
            state.setText(distance.isEmpty() ? "INDISPONÍVEL" : distance);
            button.setContentDescription("Abertura " + doorName + " indisponível");
        } else if (capability.mode.equals("remote")) {
            title.setText("Abrir " + doorName + "\nremotamente");
            state.setText(distance.isEmpty() ? "REMOTO" : "REMOTO · " + distance);
            button.setContentDescription("Abrir " + doorName + " remotamente");
        } else {
            title.setText("Abrir " + doorName);
            state.setText(distance.isEmpty() ? "LOCAL" : "LOCAL · " + distance);
            button.setContentDescription("Abrir " + doorName);
        }
        button.setEnabled(capability.canOpen);
        button.setAlpha(capability.canOpen ? 1.0f : 0.45f);
    }

    private String formatDistance(Double distanceM) {
        if (distanceM == null) {
            return "";
        }
        if (distanceM < 1_000.0) {
            return Math.round(distanceM) + " m";
        }
        return String.format(Locale.getDefault(), "%.1f km", distanceM / 1_000.0);
    }

    private void disableOpening(String state) {
        aptCanOpen = false;
        bldCanOpen = false;
        aptActionTitle.setText("APT indisponível");
        aptActionState.setText(state);
        bldActionTitle.setText("BLD indisponível");
        bldActionState.setText(state);
        openApartmentButton.setEnabled(false);
        openBuildingButton.setEnabled(false);
        openApartmentButton.setAlpha(0.45f);
        openBuildingButton.setAlpha(0.45f);
    }

    private void capabilitiesFailed(String message) {
        progressBar.setVisibility(View.GONE);
        disableOpening("INDISPONÍVEL");
        setStatus(message, true);
    }

    private void send(String path, Location location) {
        final String bearer;
        try {
            bearer = secretStore.getBearer();
        } catch (Exception error) {
            setBusy(false, "Não foi possível ler o bearer");
            statusText.setTextColor(getColor(R.color.danger));
            return;
        }

        String serverUrl = settings.getString(SERVER_URL, DEFAULT_SERVER_URL);
        setBusy(true, "A executar…");
        networkExecutor.execute(() -> {
            ApiClient.Result result = ApiClient.post(serverUrl, path, bearer, location);
            runOnUiThread(() -> {
                setBusy(false, result.successful ? "Concluído" : result.message);
                statusText.setTextColor(getColor(
                        result.successful ? R.color.primary_dark : R.color.danger
                ));
            });
        });
    }

    private boolean hasNetwork() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager == null) {
            return false;
        }
        Network network = manager.getActiveNetwork();
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void showSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean online = hasNetwork();
        connectivityText.setText(online ? "Ligado ao serviço" : "Sem ligação ao serviço");
        connectivityText.setTextColor(getColor(online ? R.color.success : R.color.danger));
        residenceStatus.setText(online ? "Minha Residência\n●  Online" : "Minha Residência\n●  Sem ligação");
        refreshCapabilities();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            refreshCapabilities();
        }
    }

    private void requestLocationPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    LOCATION_PERMISSION_REQUEST
            );
        }
    }

    private void setBusy(boolean busy, String message) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        lockButton.setEnabled(!busy);
        unlockButton.setEnabled(!busy);
        openApartmentButton.setEnabled(!busy && aptCanOpen);
        openBuildingButton.setEnabled(!busy && bldCanOpen);
        setStatus(message, false);
    }

    private void setStatus(String message, boolean error) {
        statusText.setText(message);
        statusText.setTextColor(getColor(error ? R.color.danger : R.color.text_secondary));
    }

    @Override
    protected void onDestroy() {
        capabilitiesGeneration++;
        if (locationCancellation != null) {
            locationCancellation.cancel();
        }
        networkExecutor.shutdownNow();
        super.onDestroy();
    }
}
