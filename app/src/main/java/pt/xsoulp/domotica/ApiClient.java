package pt.xsoulp.domotica;

import android.location.Location;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class ApiClient {
    static final class DoorCapability {
        final boolean canOpen;
        final String mode;
        final Double distanceM;

        DoorCapability(boolean canOpen, String mode, Double distanceM) {
            this.canOpen = canOpen;
            this.mode = mode;
            this.distanceM = distanceM;
        }
    }

    static final class CapabilitiesResult {
        final boolean successful;
        final String message;
        final DoorCapability apartment;
        final DoorCapability building;

        CapabilitiesResult(
                boolean successful,
                String message,
                DoorCapability apartment,
                DoorCapability building
        ) {
            this.successful = successful;
            this.message = message;
            this.apartment = apartment;
            this.building = building;
        }

        static CapabilitiesResult failure(String message) {
            return new CapabilitiesResult(false, message, null, null);
        }
    }

    static final class Result {
        final boolean successful;
        final String message;

        Result(boolean successful, String message) {
            this.successful = successful;
            this.message = message;
        }
    }

    static Result post(String baseUrl, String path, String bearer, Location location) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(trimTrailingSlash(baseUrl) + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(50_000);
            connection.setRequestProperty("Authorization", "Bearer " + bearer);
            connection.setRequestProperty("X-Latitude", Double.toString(location.getLatitude()));
            connection.setRequestProperty("X-Longitude", Double.toString(location.getLongitude()));
            connection.setRequestProperty("Accept", "application/json");
            connection.setFixedLengthStreamingMode(0);
            connection.setDoOutput(true);
            connection.connect();

            int code = connection.getResponseCode();
            String body = readBody(code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream());
            String message = responseMessage(body, code);
            return new Result(code >= 200 && code < 300, message);
        } catch (Exception error) {
            String message = error.getMessage();
            return new Result(false, message == null ? "Falha de ligação" : message);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static CapabilitiesResult getCapabilities(
            String baseUrl,
            String bearer,
            Location location
    ) {
        HttpURLConnection connection = null;
        try {
            String query = "/access/capabilities?lat=" + location.getLatitude()
                    + "&lon=" + location.getLongitude();
            URL url = new URL(trimTrailingSlash(baseUrl) + query);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("Authorization", "Bearer " + bearer);
            connection.setRequestProperty("Accept", "application/json");
            connection.connect();

            int code = connection.getResponseCode();
            String body = readBody(code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream());
            if (code < 200 || code >= 300) {
                return CapabilitiesResult.failure(responseMessage(body, code));
            }

            JSONObject doors = new JSONObject(body).getJSONObject("doors");
            DoorCapability apartment = parseDoor(doors.getJSONObject("APT"));
            DoorCapability building = parseDoor(doors.getJSONObject("BLD"));
            return new CapabilitiesResult(true, "", apartment, building);
        } catch (Exception error) {
            String message = error.getMessage();
            return CapabilitiesResult.failure(
                    message == null ? "Falha ao obter acessos disponíveis" : message
            );
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static DoorCapability parseDoor(JSONObject json) throws Exception {
        boolean canOpen = json.getBoolean("can_open");
        String mode = json.getString("mode");
        if (!mode.equals("local") && !mode.equals("remote")) {
            throw new IllegalArgumentException("Modo de acesso desconhecido");
        }
        Double distanceM = null;
        if (json.has("distance_m") && !json.isNull("distance_m")) {
            double value = json.getDouble("distance_m");
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException("Distância inválida");
            }
            distanceM = value;
        }
        return new DoorCapability(canOpen, mode, distanceM);
    }

    private static String trimTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String readBody(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }

    private static String responseMessage(String body, int code) {
        try {
            JSONObject json = new JSONObject(body);
            if (json.has("error")) {
                return json.getString("error");
            }
            if (json.has("output") && !json.getString("output").isBlank()) {
                return json.getString("output");
            }
        } catch (Exception ignored) {
            // Fall back to the raw response below.
        }
        return body.isBlank() ? "Resposta HTTP " + code : body;
    }

    private ApiClient() {
    }
}
