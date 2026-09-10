package pt.xsoulp.domotica;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
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
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
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
    private TextView bldActionTitle;
    private ProgressBar progressBar;
    private View openApartmentButton;
    private View aptInteractionLayer;
    private View openBuildingButton;
    private View usersButton;
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
        bldActionTitle = findViewById(R.id.bldActionTitle);
        progressBar = findViewById(R.id.progressBar);

        openApartmentButton = findViewById(R.id.openApartmentButton);
        aptInteractionLayer = findViewById(R.id.aptInteractionLayer);
        openBuildingButton = findViewById(R.id.openBuildingButton);
        usersButton = findViewById(R.id.usersButton);
        aptInteractionLayer.setOnClickListener(view -> {
            if (aptCanOpen) {
                beginOperation("/apt_door/open", "Abrir apartamento");
            } else {
                setStatus("Abertura APT indisponível", true);
            }
        });
        aptInteractionLayer.setOnLongClickListener(view -> {
            showAptActions();
            return true;
        });
        openBuildingButton.setOnClickListener(view -> beginOperation("/bld_door", "Abrir prédio"));
        findViewById(R.id.settingsButton).setOnClickListener(view -> showSettings());
        usersButton.setOnClickListener(view -> startActivity(
                new Intent(this, UserListActivity.class)
        ));
        usersButton.setVisibility(View.GONE);

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
                            usersButton.setVisibility(View.GONE);
                            capabilitiesFailed(result.message);
                            return;
                        }
                        usersButton.setVisibility(
                                result.user != null && result.user.isAdmin()
                                        ? View.VISIBLE
                                        : View.GONE
                        );
                        applyDoorCapability(
                                "APT",
                                openApartmentButton,
                                aptActionTitle,
                                result.apartment
                        );
                        applyDoorCapability(
                                "BLD",
                                openBuildingButton,
                                bldActionTitle,
                                result.building
                        );
                        aptCanOpen = result.apartment.canOpen;
                        bldCanOpen = result.building.canOpen;
                        updateResidenceStatus(
                                true,
                                nearestDistance(
                                        result.apartment.distanceM,
                                        result.building.distanceM
                                ),
                                false
                        );
                        aptInteractionLayer.setContentDescription(
                                result.apartment.canOpen
                                        ? "Abrir porta do apartamento. Manter premido para mais ações."
                                        : "Abertura APT indisponível. Manter premido para Lock ou Unlock."
                        );
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
            ApiClient.DoorCapability capability
    ) {
        String distance = formatDistance(capability.distanceM);
        title.setText(doorName);
        if (!capability.canOpen) {
            button.setContentDescription("Abertura " + doorName + " indisponível");
        } else if (capability.mode.equals("remote")) {
            button.setContentDescription(
                    "Abrir " + doorName + " remotamente" + (distance.isEmpty() ? "" : ", " + distance)
            );
        } else {
            button.setContentDescription(
                    "Abrir " + doorName + (distance.isEmpty() ? "" : ", " + distance)
            );
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

    private Double nearestDistance(Double apartmentDistanceM, Double buildingDistanceM) {
        if (apartmentDistanceM == null) {
            return buildingDistanceM;
        }
        if (buildingDistanceM == null) {
            return apartmentDistanceM;
        }
        return Math.min(apartmentDistanceM, buildingDistanceM);
    }

    private void updateResidenceStatus(boolean online, Double distanceM, boolean locating) {
        String state = online ? "Online" : "Sem ligação";
        String distance = formatDistance(distanceM);
        String suffix = locating
                ? "  •  A localizar…"
                : (distance.isEmpty() ? "" : "  •  " + distance);
        String text = "Minha Residência\n●  " + state + suffix;
        SpannableString styled = new SpannableString(text);
        int dot = text.indexOf('●');
        styled.setSpan(
                new ForegroundColorSpan(getColor(online ? R.color.success : R.color.danger)),
                dot,
                dot + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        residenceStatus.setText(styled);
    }

    private void disableOpening(String state) {
        aptCanOpen = false;
        bldCanOpen = false;
        aptActionTitle.setText("APT");
        bldActionTitle.setText("BLD");
        openApartmentButton.setContentDescription("Abertura APT indisponível: " + state);
        openBuildingButton.setContentDescription("Abertura BLD indisponível: " + state);
        openApartmentButton.setEnabled(false);
        openBuildingButton.setEnabled(false);
        openApartmentButton.setAlpha(0.45f);
        openBuildingButton.setAlpha(0.45f);
    }

    private void capabilitiesFailed(String message) {
        progressBar.setVisibility(View.GONE);
        disableOpening("INDISPONÍVEL");
        updateResidenceStatus(hasNetwork(), null, false);
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

    private void showAptActions() {
        if (!aptInteractionLayer.isEnabled()) {
            return;
        }

        Dialog sheet = new Dialog(this, R.style.AptBottomSheetDialog);
        sheet.setContentView(R.layout.dialog_apt_actions);
        sheet.setCanceledOnTouchOutside(true);

        View open = sheet.findViewById(R.id.sheetOpenButton);
        View lock = sheet.findViewById(R.id.sheetLockButton);
        View unlock = sheet.findViewById(R.id.sheetUnlockButton);
        open.setEnabled(aptCanOpen);
        open.setAlpha(aptCanOpen ? 1.0f : 0.42f);
        open.setContentDescription(aptCanOpen ? "Abrir porta do apartamento" : "Abertura APT indisponível");

        sheet.findViewById(R.id.closeSheetButton).setOnClickListener(view -> sheet.dismiss());
        open.setOnClickListener(view -> runSheetAction(
                sheet, "/apt_door/open", "Abrir apartamento"
        ));
        lock.setOnClickListener(view -> runSheetAction(
                sheet, "/apt_door/lock", "Trancar"
        ));
        unlock.setOnClickListener(view -> runSheetAction(
                sheet, "/apt_door/unlock", "Destrancar"
        ));

        sheet.show();
        Window window = sheet.getWindow();
        if (window != null) {
            window.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
            );
            window.setGravity(Gravity.BOTTOM);
            window.setNavigationBarColor(getColor(R.color.background));
        }
    }

    private void runSheetAction(Dialog sheet, String path, String title) {
        sheet.dismiss();
        beginOperation(path, title);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean online = hasNetwork();
        connectivityText.setText(online ? "Ligado ao serviço" : "Sem ligação ao serviço");
        connectivityText.setTextColor(getColor(online ? R.color.success : R.color.danger));
        updateResidenceStatus(online, null, online);
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
        aptInteractionLayer.setEnabled(!busy);
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
