package ru.aielemental.agile.planning.poker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class PlanningPokerServer {
    private final static long testRoomLiveMillis = TimeUnit.MINUTES.toMillis(60);

    private final static String USERNAME = "username";
    private final static String ROOM_ID = "roomId";
    private final static String ESTIMATION = "estimation";
    private final static String DESCRIPTION = "description";

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final HttpServer server;
    private final String anonymousIndexHtml;
    private final String authorizedIndexHtml;
    private final String errorHtml;
    private final String roomHtml;

    public static void main(String[] args) throws Exception {
        System.out.println("Using first arg as port");
        int port = 8080;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        System.out.println("Server on port " + port);
        PlanningPokerServer planningPokerServer = new PlanningPokerServer(port);
        new Timer("Old rooms removal thread", true)
        .schedule(new TimerTask() {
            @Override
            public void run() {
                System.out.println("Old rooms removal thread start");
                System.out.println("Total rooms " + planningPokerServer.rooms.size());
                planningPokerServer.purgeOldRooms(System.currentTimeMillis(), TimeUnit.HOURS.toMillis(24));
                System.out.println("Old rooms removal thread exit");
            }
        }, TimeUnit.MINUTES.toMillis(5), TimeUnit.MINUTES.toMillis(5));
        System.out.println("Type anything to stop Poker Server. Allow for one second to stop");
        System.in.read();
        planningPokerServer.server.stop(1);
    }

    public PlanningPokerServer(int port) throws IOException, PrintableException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", htmlIndex());
        server.createContext("/api-add-room", apiAddRoom());
        server.createContext("/api-add-estimation", apiAddEstimation());
        server.createContext("/api-set-description", apiSetDescription());

        //self check
        createRoom(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(4) + testRoomLiveMillis);

        server.setExecutor(null); // creates a default executor

        anonymousIndexHtml = loadResource("/anonymousIndex.html");
        authorizedIndexHtml = loadResource("/authorizedIndex.html");
        errorHtml = loadResource("/error.html");
        roomHtml = loadResource("/room.html");
        server.start();
    }

    public HttpHandler htmlIndex() {
        return (HttpExchange t) -> {
            printExceptions(t, () -> {
                System.out.println("Get Index " + t.getRequestURI() + ": Start");
                String username = parseQueryParams(t).get(USERNAME);
                String response;
                if (username == null) {
                    redirectToLogin(t, "Please introduce yourself");
                    System.out.println("Get Index " + t.getRequestURI() + ": Request authorization");
                } else if ("".equals(username)) {
                    redirectToLogin(t, "Empty name not allowed. Please introduce yourself");
                    System.out.println("Get Index " + t.getRequestURI() + ": Empty name");
                } else {
                    response = authorizedIndexHtml
                            .replace("REPLACE-USERNAME", username)
                            .replace("REPLACE-EXISTING-ROOMS", roomURIs())
                            .replace("REPLACE-FORM-PREVENT-CACHE", "" + System.currentTimeMillis());
                    disableCaches(t);
                    t.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
                    OutputStream os = t.getResponseBody();
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                    os.close();
                    System.out.println("Get Index " + t.getRequestURI() + ": Done");
                }
            });
        };
    }

    public HttpHandler htmlRoom() {
        return (HttpExchange t) -> {
            printExceptions(t, () -> {
                System.out.println("Get Room " + t.getRequestURI() + ": Start");
                String username = parseQueryParams(t).get(USERNAME);
                if (username == null) {
                    redirectToLogin(t);
                    System.out.println("Get Room " + t.getRequestURI() + ": Request authorization");
                } else {
                    String roomId = t.getRequestURI().getPath().substring("/room-".length());
                    Room room = rooms.get(roomId);
                    room.getEstimations().putIfAbsent(username, "?");
                    boolean hideEstimations = "?".equals(room.getEstimations().get(username));
                    String response = roomHtml
                            .replaceAll("REPLACE-ROOM-ID", roomId)
                            .replaceAll("REPLACE-MY-ESTIMATION", room.getEstimations().get(username))
                            .replaceAll("REPLACE-USERNAME", username.replaceAll("\\+", " "))
                            .replaceAll("REPLACE-DESCRIPTION", room.getDescription().replaceAll("\\+", " "));
                    response = response.replace("REPLACE-OTHER-ESTIMATIONS-JSON", roomEstimationJson(new HashMap<>(room.getEstimations()), hideEstimations));
                    disableCaches(t);
                    t.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
                    OutputStream os = t.getResponseBody();
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                    os.close();
                    System.out.println("Get Room " + t.getRequestURI() + ": Done");
                }
            });
        };
    }

    public HttpHandler apiAddRoom() {
        return (HttpExchange t) -> {
            printExceptions(t, () -> {
                System.out.println("Api Add Room " + t.getRequestURI() + ": Start");
                String username = parseQueryParams(t).get(USERNAME);
                if (username == null) {
                    redirectToLogin(t);
                    System.out.println("Api Add Room " + t.getRequestURI() + ": Request authorization");
                } else {
                    String roomURI = createRoom(System.currentTimeMillis());
                    t.getResponseHeaders().set("Location", roomURI + "?username=" + username);
                    disableCaches(t);
                    t.sendResponseHeaders(301, -1);
                    System.out.println("New room " + roomURI + " total rooms " + rooms.size());
                    System.out.println("Api Add Room " + t.getRequestURI() + ": Done");
                }
            });
        };
    }

    public HttpHandler apiAddEstimation() {
        return (HttpExchange t) -> {
            printExceptions(t, () -> {
                System.out.println("Api Add Estimation " + t.getRequestURI() + ": Start");
                Map<String, String> params = parseQueryParams(t);
                String username = params.get(USERNAME);
                if (username == null) {
                    redirectToLogin(t);
                    System.out.println("Api Add Estimation " + t.getRequestURI() + ": Request authorization");
                } else {
                    String roomId = params.get(ROOM_ID);
                    String estimation = params.get(ESTIMATION);
                    Room room = rooms.get(roomId);
                    room.addEstimation(username, estimation);
                    t.getResponseHeaders().set("Location", roomURI(roomId) + "?username=" + username + "&estimation=" + estimation);
                    disableCaches(t);
                    t.sendResponseHeaders(301, -1);
                    System.out.println("New estimation in room " + roomId + " estimations " + room.getEstimations());
                    System.out.println("Api Add Estimation " + t.getRequestURI() + ": Done");
                }
            });
        };
    }

    public HttpHandler apiSetDescription() {
        return (HttpExchange t) -> {
            printExceptions(t, () -> {
                System.out.println("Api Set Room Description " + t.getRequestURI() + ": Start");
                Map<String, String> params = parseQueryParams(t);
                String username = params.get(USERNAME);
                if (username == null) {
                    redirectToLogin(t);
                    System.out.println("Api Set Room Description " + t.getRequestURI() + ": Request authorization");
                } else {
                    String roomId = params.get(ROOM_ID);
                    String description = params.get(DESCRIPTION);
                    Room room = rooms.get(roomId);
                    room.setDescription(description);
                    room.dropEstimations();
                    t.getResponseHeaders().set("Location", roomURI(roomId) + "?username=" + username + "&estimation=" + room.getEstimations().get(username));
                    disableCaches(t);
                    t.sendResponseHeaders(301, -1);
                    System.out.println("New estimation in room " + roomId + " estimations " + room.getEstimations());
                    System.out.println("Api Set Room Description " + t.getRequestURI() + ": Done");
                }
            });
        };
    }

    private void redirectToLogin(HttpExchange t) throws IOException {
        redirectToLogin(t, "");
    }

    private void redirectToLogin(HttpExchange t, String errorMessage) throws IOException {
        String response = anonymousIndexHtml.replace("REPLACE-ERROR-MESSAGE", errorMessage);
        disableCaches(t);
        t.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
        OutputStream os = t.getResponseBody();
        os.write(response.getBytes(StandardCharsets.UTF_8));
        os.close();
    }

    public String createRoom(long lastUpdate) throws PrintableException {
        String roomId = null;
        Room result = null;
        for (int i = 0; i < 10; i++) {
            roomId = randomMemorableRoomName();
            result = rooms.putIfAbsent(roomId, new Room("Change room description to what you are estimating", lastUpdate));
            if (result == null) {
                break;
            }
        }
        if (result != null) {
            throw new PrintableException("Could not create free room name");
        }
        String roomUrl = roomURI(roomId);
        server.createContext(roomUrl, htmlRoom());
        return roomUrl;
    }

    public void removeRoom(String roomId) {
        rooms.remove(roomId);
        server.removeContext(roomURI(roomId));
    }

    public void purgeOldRooms(long nowMillis, long maxAgeMillis) {
        for (String room: new HashSet<>(rooms.keySet())) {
            if (nowMillis - rooms.get(room).getLastUpdate() > maxAgeMillis) {
                removeRoom(room);
            }
        }
    }

    private String roomURIs() {
        List<String> roomNames = new ArrayList<>(rooms.keySet());
        return roomNames.stream().sorted().map(s -> '"' + roomURI(s) + '"').collect(Collectors.joining(", "));
    }

    private String roomEstimationJson(Map<String, String> estimations, boolean hidden) {
        return estimations.keySet().stream().sorted()
                .map(key -> "{\"username\":\"" + key.replaceAll("\\+", " ") + "\",\"estimation\":\"" + (hidden ? "?" : estimations.get(key)).replaceAll("\\+", " ") + "\"}")
                .collect(Collectors.joining(", "));
    }

    private String loadResource(String path) {
        InputStream in = getClass().getResourceAsStream(path);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        return new Scanner(reader).useDelimiter("\\Z").next();
    }

    private static String roomURI(String roomId) {
        return "/room-" + roomId;
    }

    //only single value per param
    private static Map<String, String> parseQueryParams(HttpExchange t) {
        if (t.getRequestURI().getQuery() == null) {
            return Collections.emptyMap();
        }
        String[] params = t.getRequestURI().getQuery().split("&");
        Map<String, String> result = new HashMap<>();
        for (String param : params) {
            String[] keyValue = param.split("=", 2);
            result.put(keyValue[0], keyValue[1]);
        }
        return result;
    }

    private static void disableCaches(HttpExchange t) {
        t.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        t.getResponseHeaders().set("Pragma", "no-cache");
        t.getResponseHeaders().set("Expires", "0");
    }

    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    public void printExceptions(HttpExchange t, ThrowingRunnable r) throws IOException {
        try {
            try {
                r.run();
            } catch (PrintableException printableException) {
                String response = errorHtml
                        .replace("REPLACE-ERROR-MESSAGE", printableException.getMessage());
                disableCaches(t);
                t.sendResponseHeaders(500, response.getBytes(StandardCharsets.UTF_8).length);
                OutputStream os = t.getResponseBody();
                os.write(response.getBytes(StandardCharsets.UTF_8));
                os.close();
                System.out.println("Response with error page " + printableException + ": Done");
                throw printableException;
            }
        } catch (Exception e) {
            e.printStackTrace();
            t.sendResponseHeaders(500, -1);
        }
    }

    private static class Room {
        private long lastUpdate;
        private String description;
        private final Map<String, String> estimations = new ConcurrentHashMap<>();

        public Room(String description, long lastUpdate) {
            this.description = description;
            this.lastUpdate = lastUpdate;
        }

        public long getLastUpdate() {
            return lastUpdate;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
            this.lastUpdate = System.currentTimeMillis();
        }

        public void addEstimation(String author, String estimation) {
            estimations.put(author, estimation);
            this.lastUpdate = System.currentTimeMillis();
        }

        public Map<String, String> getEstimations() {
            return estimations;
        }

        public void dropEstimations() {
            estimations.clear();
        }

        @Override
        public String toString() {
            return "Room{" +
                    "lastUpdate=" + lastUpdate +
                    ", topic='" + description + '\'' +
                    ", estimations=" + estimations +
                    '}';
        }
    }

    private static class PrintableException extends Exception {
        public PrintableException(String message) {
            super(message);
        }
    }

    private static final String[] randomLine1 = {"Azure", "Bronze", "Cobalt", "Denim", "Emerald", "Fuchsia", "Green", "Indigo", "Jade", "Lilac", "Maroon", "Orange", "Pink", "Quartz", "Ruby", "Sapphire", "Tangerine", "Violet", "White", "Yellow"};
    private static final String[] randomLine2 = {"Active", "Brave", "Calm", "Dreamer", "Enthusiastic", "Friendly", "Gentle", "Heroic", "Industrious", "Joyful", "Kind", "Lucky", "Mysterious", "Neat", "Organized", "Polite", "Quick", "Respectful", "Smart", "Tough", "Understanding", "Vivacious", "Wise"};
    private static final String[] randomLine3 = {"Ant", "Bear", "Cat", "Dog", "Eel", "Fox", "Goat", "Hyena", "Ibis", "Jellyfish", "Kiwi", "Lion", "Mink", "Newt", "Octopus", "Pug", "Quail", "Reindeer", "Seal", "Tuna", "Uguisu", "Vulture", "Wolf", "Xerus", "Yak", "Zebu"};
    private static final Random random = new Random();

    private String randomMemorableRoomName() {
        String randomRoom;
        do {
            String color = randomLine1[random.nextInt(randomLine1.length)];
            String trait = randomLine2[random.nextInt(randomLine2.length)];
            String animal = randomLine3[random.nextInt(randomLine3.length)];
            randomRoom = color + "-" + trait + "-" + animal;
        } while (rooms.containsKey(randomRoom));
        return randomRoom;
    }
}
