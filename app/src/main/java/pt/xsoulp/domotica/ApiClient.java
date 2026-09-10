package pt.xsoulp.domotica;

import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    static final class AuthenticatedUser {
        final int id;
        final String name;
        final String role;

        AuthenticatedUser(int id, String name, String role) {
            this.id = id;
            this.name = name;
            this.role = role;
        }

        boolean isAdmin() {
            return "admin".equals(role);
        }
    }

    static final class Schedule {
        final List<String> allowedDays;
        final String startTime;
        final String endTime;

        Schedule(List<String> allowedDays, String startTime, String endTime) {
            this.allowedDays = Collections.unmodifiableList(new ArrayList<>(allowedDays));
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    static final class User {
        final int id;
        final String name;
        final String role;
        final boolean enabled;
        final boolean allowRemote;
        final List<Schedule> schedules;

        User(
                int id,
                String name,
                String role,
                boolean enabled,
                boolean allowRemote,
                List<Schedule> schedules
        ) {
            this.id = id;
            this.name = name;
            this.role = role;
            this.enabled = enabled;
            this.allowRemote = allowRemote;
            this.schedules = Collections.unmodifiableList(new ArrayList<>(schedules));
        }
    }

    static final class CapabilitiesResult {
        final boolean successful;
        final String message;
        final DoorCapability apartment;
        final DoorCapability building;
        final AuthenticatedUser user;

        CapabilitiesResult(
                boolean successful,
                String message,
                DoorCapability apartment,
                DoorCapability building,
                AuthenticatedUser user
        ) {
            this.successful = successful;
            this.message = message;
            this.apartment = apartment;
            this.building = building;
            this.user = user;
        }

        static CapabilitiesResult failure(String message) {
            return new CapabilitiesResult(false, message, null, null, null);
        }
    }

    static final class UsersResult {
        final boolean successful;
        final String message;
        final List<User> users;

        UsersResult(boolean successful, String message, List<User> users) {
            this.successful = successful;
            this.message = message;
            this.users = users;
        }
    }

    static final class AccessEvent {
        final long id;
        final int userId;
        final String userName;
        final String door;
        final String action;
        final String openedAt;

        AccessEvent(
                long id,
                int userId,
                String userName,
                String door,
                String action,
                String openedAt
        ) {
            this.id = id;
            this.userId = userId;
            this.userName = userName;
            this.door = door;
            this.action = action;
            this.openedAt = openedAt;
        }
    }

    static final class AccessHistoryResult {
        final boolean successful;
        final String message;
        final List<AccessEvent> entries;

        AccessHistoryResult(boolean successful, String message, List<AccessEvent> entries) {
            this.successful = successful;
            this.message = message;
            this.entries = entries;
        }
    }

    static final class UserResult {
        final boolean successful;
        final String message;
        final User user;
        final String createdToken;

        UserResult(boolean successful, String message, User user, String createdToken) {
            this.successful = successful;
            this.message = message;
            this.user = user;
            this.createdToken = createdToken;
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

    private static final class HttpResponse {
        final int code;
        final String body;

        HttpResponse(int code, String body) {
            this.code = code;
            this.body = body;
        }

        boolean successful() {
            return code >= 200 && code < 300;
        }
    }

    static Result post(String baseUrl, String path, String bearer, Location location) {
        try {
            HttpResponse response = request(
                    baseUrl,
                    path,
                    "POST",
                    bearer,
                    null,
                    location,
                    50_000
            );
            return new Result(response.successful(), responseMessage(response.body, response.code));
        } catch (Exception error) {
            return new Result(false, errorMessage(error, "Falha de ligação"));
        }
    }

    static CapabilitiesResult getCapabilities(
            String baseUrl,
            String bearer,
            Location location
    ) {
        try {
            String query = "/access/capabilities?lat=" + location.getLatitude()
                    + "&lon=" + location.getLongitude();
            HttpResponse response = request(baseUrl, query, "GET", bearer, null, null, 10_000);
            if (!response.successful()) {
                return CapabilitiesResult.failure(responseMessage(response.body, response.code));
            }
            JSONObject root = new JSONObject(response.body);
            JSONObject doors = root.getJSONObject("doors");
            JSONObject jsonUser = root.getJSONObject("user");
            AuthenticatedUser user = new AuthenticatedUser(
                    jsonUser.getInt("id"),
                    jsonUser.getString("name"),
                    jsonUser.getString("role")
            );
            return new CapabilitiesResult(
                    true,
                    "",
                    parseDoor(doors.getJSONObject("APT")),
                    parseDoor(doors.getJSONObject("BLD")),
                    user
            );
        } catch (Exception error) {
            return CapabilitiesResult.failure(
                    errorMessage(error, "Falha ao obter acessos disponíveis")
            );
        }
    }

    static UsersResult getUsers(String baseUrl, String bearer) {
        try {
            HttpResponse response = request(
                    baseUrl, "/admin/users", "GET", bearer, null, null, 10_000
            );
            if (!response.successful()) {
                return new UsersResult(
                        false,
                        responseMessage(response.body, response.code),
                        Collections.emptyList()
                );
            }
            JSONArray array = new JSONObject(response.body).getJSONArray("users");
            List<User> users = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                users.add(parseUser(array.getJSONObject(index)));
            }
            return new UsersResult(true, "", Collections.unmodifiableList(users));
        } catch (Exception error) {
            return new UsersResult(
                    false,
                    errorMessage(error, "Falha ao obter utilizadores"),
                    Collections.emptyList()
            );
        }
    }

    static AccessHistoryResult getAccessHistory(String baseUrl, String bearer, int limit) {
        try {
            HttpResponse response = request(
                    baseUrl,
                    "/admin/access-history?limit=" + limit,
                    "GET",
                    bearer,
                    null,
                    null,
                    10_000
            );
            if (!response.successful()) {
                return new AccessHistoryResult(
                        false,
                        responseMessage(response.body, response.code),
                        Collections.emptyList()
                );
            }
            JSONArray array = new JSONObject(response.body).getJSONArray("entries");
            List<AccessEvent> entries = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.getJSONObject(index);
                entries.add(new AccessEvent(
                        item.getLong("id"),
                        item.getInt("user_id"),
                        item.getString("user_name"),
                        item.getString("door"),
                        item.optString("action", "open"),
                        item.getString("opened_at")
                ));
            }
            return new AccessHistoryResult(
                    true,
                    "",
                    Collections.unmodifiableList(entries)
            );
        } catch (Exception error) {
            return new AccessHistoryResult(
                    false,
                    errorMessage(error, "Falha ao obter histórico"),
                    Collections.emptyList()
            );
        }
    }

    static UserResult getUser(String baseUrl, String bearer, int userId) {
        return userRequest(baseUrl, "/admin/users/" + userId, "GET", bearer, null);
    }

    static UserResult createUser(String baseUrl, String bearer, User user) {
        return userRequest(baseUrl, "/admin/users", "POST", bearer, userPayload(user));
    }

    static UserResult updateUser(String baseUrl, String bearer, User user) {
        return userRequest(
                baseUrl,
                "/admin/users/" + user.id,
                "PUT",
                bearer,
                userPayload(user)
        );
    }

    static Result deleteUser(String baseUrl, String bearer, int userId) {
        try {
            HttpResponse response = request(
                    baseUrl,
                    "/admin/users/" + userId,
                    "DELETE",
                    bearer,
                    null,
                    null,
                    10_000
            );
            return new Result(response.successful(), responseMessage(response.body, response.code));
        } catch (Exception error) {
            return new Result(false, errorMessage(error, "Falha ao eliminar utilizador"));
        }
    }

    private static UserResult userRequest(
            String baseUrl,
            String path,
            String method,
            String bearer,
            JSONObject payload
    ) {
        try {
            HttpResponse response = request(
                    baseUrl, path, method, bearer, payload, null, 10_000
            );
            if (!response.successful()) {
                return new UserResult(
                        false,
                        responseMessage(response.body, response.code),
                        null,
                        null
                );
            }
            JSONObject json = new JSONObject(response.body);
            return new UserResult(
                    true,
                    "",
                    parseUser(json),
                    json.optString("token", null)
            );
        } catch (Exception error) {
            return new UserResult(
                    false,
                    errorMessage(error, "Falha ao guardar utilizador"),
                    null,
                    null
            );
        }
    }

    private static JSONObject userPayload(User user) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("name", user.name);
            payload.put("enabled", user.enabled);
            payload.put("allow_remote", user.allowRemote);
            JSONArray schedules = new JSONArray();
            for (Schedule schedule : user.schedules) {
                JSONObject item = new JSONObject();
                item.put("allowed_days", new JSONArray(schedule.allowedDays));
                item.put("start_time", schedule.startTime);
                item.put("end_time", schedule.endTime);
                schedules.put(item);
            }
            payload.put("schedules", schedules);
            return payload;
        } catch (Exception error) {
            throw new IllegalArgumentException("Dados de utilizador inválidos", error);
        }
    }

    private static User parseUser(JSONObject json) throws Exception {
        JSONArray jsonSchedules = json.getJSONArray("schedules");
        List<Schedule> schedules = new ArrayList<>();
        for (int index = 0; index < jsonSchedules.length(); index++) {
            JSONObject jsonSchedule = jsonSchedules.getJSONObject(index);
            JSONArray jsonDays = jsonSchedule.getJSONArray("allowed_days");
            List<String> days = new ArrayList<>();
            for (int dayIndex = 0; dayIndex < jsonDays.length(); dayIndex++) {
                days.add(jsonDays.getString(dayIndex));
            }
            schedules.add(new Schedule(
                    days,
                    jsonSchedule.getString("start_time"),
                    jsonSchedule.getString("end_time")
            ));
        }
        return new User(
                json.getInt("id"),
                json.getString("name"),
                json.getString("role"),
                json.getBoolean("enabled"),
                json.getBoolean("allow_remote"),
                schedules
        );
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

    private static HttpResponse request(
            String baseUrl,
            String path,
            String method,
            String bearer,
            JSONObject payload,
            Location location,
            int readTimeout
    ) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(trimTrailingSlash(baseUrl) + path)
                    .openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(readTimeout);
            connection.setRequestProperty("Authorization", "Bearer " + bearer);
            connection.setRequestProperty("Accept", "application/json");
            if (location != null) {
                connection.setRequestProperty(
                        "X-Latitude",
                        Double.toString(location.getLatitude())
                );
                connection.setRequestProperty(
                        "X-Longitude",
                        Double.toString(location.getLongitude())
                );
            }
            if (payload != null) {
                byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(data.length);
                connection.setDoOutput(true);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(data);
                }
            } else if ("POST".equals(method)) {
                connection.setFixedLengthStreamingMode(0);
                connection.setDoOutput(true);
            }
            int code = connection.getResponseCode();
            String body = readBody(code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream());
            return new HttpResponse(code, body);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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

    private static String errorMessage(Exception error, String fallback) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private ApiClient() {
    }
}
